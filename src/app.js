'use strict';

// ---------------------------------------------------------------- state
const S = {
  tracks: [], view: 'tracks', filter: null, q: '',
  queue: [], i: -1, shuffle: false, repeat: 0, // repeat: 0 off, 1 all, 2 one
  folders: store.get('folders', []),
  favs: new Set(store.get('favs', [])),
  set: { yt: true, autoplay: true, updates: true, tray: true, ...store.get('settings', {}) },
  yt: { q: '', status: 'idle', items: [] },
  dl: new Map(), // youtube id -> { state: 'dl' | 'done' | 'err', pct }
  dlDir: '',
};
audio.volume = store.get('volume', 0.8);
const byPath = new Map();
const ytIdx = new Map(); // youtube id -> local track (files downloaded by us are named <id>.<ext>)
const albumKey = (t) => `${t.albumArtist}\u0000${t.album}`;
const fileName = (p) => p.split(/[\\/]/).pop();
const trackAdded = (t) => {
  byPath.set(t.path, t);
  const m = fileName(t.path).match(/^([\w-]{11})\.\w+$/);
  if (m) ytIdx.set(m[1], t);
};

function toast(msg, ms = 3400) {
  let el = $('#toast');
  if (!el) { el = document.createElement('div'); el.id = 'toast'; el.className = 'toast'; document.body.append(el); }
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toast.t);
  toast.t = setTimeout(() => el.classList.remove('show'), ms);
}

// ---------------------------------------------------------------- dynamic colour from cover
function toOklab(r, g, b) {
  const lin = (v) => { v /= 255; return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
  r = lin(r); g = lin(g); b = lin(b);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}
function paletteFrom(src) {
  return new Promise((resolve) => {
    const im = new Image();
    im.onerror = () => resolve(null);
    im.onload = () => {
      const c = document.createElement('canvas');
      c.width = c.height = 24;
      const g = c.getContext('2d', { willReadFrequently: true });
      g.drawImage(im, 0, 0, 24, 24);
      const d = g.getImageData(0, 0, 24, 24).data;
      let sa = 0, sb = 0, sw = 0;
      for (let i = 0; i < d.length; i += 4) {
        const [a, b] = toOklab(d[i], d[i + 1], d[i + 2]);
        const w = a * a + b * b + 0.0005; // weigh vivid pixels heavily
        sa += a * w; sb += b * w; sw += w;
      }
      const a = sa / sw, b = sb / sw;
      resolve({ h: ((Math.atan2(b, a) * 180) / Math.PI + 360) % 360, c: clamp(Math.hypot(a, b) * 1.15, 0.012, 0.17) });
    };
    im.src = src;
  });
}
function setPalette(p) {
  root.style.setProperty('--h', p.h.toFixed(1));
  root.style.setProperty('--c', p.c.toFixed(3));
}
setPalette({ h: 285, c: 0.1 });

// ---------------------------------------------------------------- covers (cached per album)
const coverCache = new Map();
function getCover(t) {
  const k = ytIdx.has(fileName(t.path).split('.')[0]) ? t.path : albumKey(t);
  if (!coverCache.has(k)) coverCache.set(k, window.api.cover(t.path));
  return coverCache.get(k);
}
const lazyCovers = new IntersectionObserver((entries) => {
  for (const e of entries) {
    if (!e.isIntersecting) continue;
    lazyCovers.unobserve(e.target);
    const t = byPath.get(e.target.dataset.path);
    if (t) getCover(t).then((src) => { if (src) e.target.innerHTML = `<img src="${src}" alt="" loading="lazy">`; });
  }
}, { root: $('#content'), rootMargin: '300px' });

// ---------------------------------------------------------------- library data
function sorted(list) {
  return list.sort((a, b) =>
    a.albumArtist.localeCompare(b.albumArtist) || a.album.localeCompare(b.album) ||
    a.disk - b.disk || a.no - b.no || a.title.localeCompare(b.title));
}
const matchQ = (t, q) => `${t.title} ${t.artist} ${t.album}`.toLowerCase().includes(q);
function visible() {
  let list = S.tracks;
  if (S.view === 'favs') list = list.filter((t) => S.favs.has(t.path));
  if (S.filter?.kind === 'album') list = list.filter((t) => albumKey(t) === S.filter.value);
  if (S.filter?.kind === 'artist') list = list.filter((t) => t.artist === S.filter.value || t.albumArtist === S.filter.value);
  return list;
}
const groupBy = (list, keyFn) => { const m = new Map(); for (const t of list) { const k = keyFn(t); (m.get(k) ?? m.set(k, []).get(k)).push(t); } return m; };

// ---------------------------------------------------------------- views
const TITLES = { tracks: 'Треки', albums: 'Альбомы', artists: 'Артисты', favs: 'Избранное', eq: 'Звук', settings: 'Настройки' };
let shown = [];

const rowHtml = (t, i) => `
  <div class="row${t.path === S.queue[S.i] ? ' active' : ''}" role="button" tabindex="0" data-i="${i}" data-path="${esc(t.path)}">
    <div class="art">${esc([...t.title][0]?.toUpperCase() ?? '♪')}</div>
    <div class="t"><b>${esc(t.title)}</b><span>${esc(t.artist)}</span></div>
    <div class="al">${esc(t.album)}</div>
    <button class="rowq" title="В очередь" data-q="${esc(t.path)}">${svg('queueAdd')}</button>
    <div class="d">${fmt(t.duration)}</div></div>`;

function render(keepScroll = false) {
  const c = $('#content');
  const top = c.scrollTop;
  const f = S.filter;
  const searching = !!S.q;
  $('#title').textContent = searching ? 'Поиск' : f ? f.label : TITLES[S.view];
  $('#chips').innerHTML = f && !searching ? `<button class="chip" id="clearFilter">${svg('close')}${esc(f.kind === 'album' ? 'Альбом' : 'Артист')}</button>` : '';
  $$('.rail-item[data-view]').forEach((b) => b.classList.toggle('active', b.dataset.view === S.view && !searching));

  if (searching) renderSearch();
  else if (S.view === 'eq') { $('#sub').textContent = '10 полос · 31 Гц – 16 кГц'; renderEq(c); }
  else if (S.view === 'settings') { $('#sub').textContent = ''; renderSettings(c); }
  else renderLibrary(c);
  c.scrollTop = keepScroll ? top : 0;
}

function renderLibrary(c) {
  const list = visible();
  shown = list;
  const f = S.filter;
  $('#sub').textContent = S.tracks.length ? `${list.length} ${plural(list.length, 'трек', 'трека', 'треков')}` : 'Библиотека пуста';
  if (!S.tracks.length) {
    c.innerHTML = `<div class="empty"><div class="blob">${svg('music')}</div><h3>Добавь свою музыку</h3>
      <p>Выбери папку с треками, перетащи её в окно — или найди трек через поиск и скачай с YouTube.</p>
      <button class="btn" id="emptyAdd">${svg('folder')}Выбрать папку</button></div>`;
    return;
  }
  if (S.view === 'favs' && !list.length) {
    c.innerHTML = `<div class="empty"><div class="blob">${svg('heart')}</div><h3>Пока пусто</h3><p>Нажми на сердечко в плеере, чтобы добавить трек в избранное.</p></div>`;
  } else if (S.view === 'albums' && !f) {
    const groups = [...groupBy(list, albumKey)];
    c.innerHTML = `<div class="grid">${groups.map(([k, ts]) => `
      <button class="card" data-album="${esc(k)}" data-label="${esc(ts[0].album)}">
        <div class="img" data-path="${esc(ts[0].path)}">${svg('album')}</div>
        <b>${esc(ts[0].album)}</b><span>${esc(ts[0].albumArtist)}</span></button>`).join('')}</div>`;
    $$('.card .img', c).forEach((el) => lazyCovers.observe(el));
  } else if (S.view === 'artists' && !f) {
    const groups = [...groupBy(list, (t) => t.albumArtist)].sort((a, b) => a[0].localeCompare(b[0]));
    c.innerHTML = `<div class="agrid">${groups.map(([name, ts]) => `
      <button class="artist" data-artist="${esc(name)}" style="--ah:${hueOf(name)}">
        <div class="av">${esc([...name][0].toUpperCase())}</div>
        <div><b>${esc(name)}</b><span>${ts.length} ${plural(ts.length, 'трек', 'трека', 'треков')}</span></div></button>`).join('')}</div>`;
  } else {
    c.innerHTML = `<div class="list">${list.map(rowHtml).join('')}</div>`;
  }
}

// ---------------------------------------------------------------- search: local + (optional) YouTube
function renderSearch() {
  const c = $('#content');
  c.innerHTML = `<div id="loc"></div><div id="yt"></div>`;
  renderLoc();
  renderYt();
}
function renderLoc() {
  const q = S.q.toLowerCase();
  shown = S.tracks.filter((t) => matchQ(t, q));
  $('#sub').textContent = `${shown.length} в библиотеке${S.set.yt ? ' · YouTube включён' : ''}`;
  const el = $('#loc');
  if (!el) return;
  el.innerHTML = shown.length
    ? `<h3 class="sec">${svg('music')}В библиотеке</h3><div class="list">${shown.slice(0, 80).map(rowHtml).join('')}</div>`
    : S.set.yt ? '' : `<div class="empty"><h3>Ничего не найдено</h3><p>Включи поиск на YouTube в настройках, чтобы искать и в сети.</p></div>`;
}
const ytRowHtml = (v) => `
  <div class="yrow" role="button" tabindex="0" data-id="${v.id}">
    <div class="yth"><img src="${esc(v.thumb)}" alt="" loading="lazy"><span class="dur">${fmt(v.seconds)}</span></div>
    <div class="t"><b>${esc(v.title)}</b><span>${esc(v.author)}</span></div>
    <button class="ydl" data-id="${v.id}" tabindex="-1"></button></div>`;
function renderYt() {
  const el = $('#yt');
  if (!el || !S.set.yt) return;
  const y = S.yt;
  let body = '';
  if (y.status === 'loading' && !y.items.length) body = Array.from({ length: 4 }, () => '<div class="yrow sk"><div class="yth"></div><div class="t"><b></b><span></span></div></div>').join('');
  else if (y.status === 'err') body = `<p class="hint">Не удалось выполнить поиск: ${esc(y.err)}</p>`;
  else if (y.status === 'idle') body = S.q.length < 2 ? '<p class="hint">Введи хотя бы 2 символа</p>' : '';
  else body = y.items.map(ytRowHtml).join('') || '<p class="hint">Ничего не нашлось</p>';
  el.innerHTML = `<h3 class="sec">${svg('video')}YouTube</h3><div class="ylist${y.status === 'loading' ? ' stale' : ''}">${body}</div>`;
  y.items.forEach((v) => paintDl(v.id));
}
function paintDl(id) {
  const st = S.dl.get(id) ?? (ytIdx.has(id) ? { state: 'done' } : { state: 'idle' });
  $$(`.ydl[data-id="${id}"]`).forEach((b) => {
    b.dataset.st = st.state;
    b.style.setProperty('--pct', st.pct ?? 0);
    b.innerHTML = st.state === 'done' ? svg('play') : st.state === 'dl' ? `<span>${Math.round(st.pct ?? 0)}</span>` : svg(st.state === 'err' ? 'refresh' : 'download');
    b.title = { idle: 'Скачать и слушать', dl: 'Загрузка…', done: 'Играть', err: 'Ошибка — повторить' }[st.state];
  });
}
let ytTimer = 0, ytSeq = 0;
function queueYt() {
  clearTimeout(ytTimer);
  if (!S.set.yt || S.q.length < 2) { ytSeq++; S.yt = { q: '', status: 'idle', items: [] }; renderYt(); return; }
  S.yt = { ...S.yt, status: 'loading' };
  renderYt();
  const q = S.q;
  ytTimer = setTimeout(async () => {
    const seq = ++ytSeq;
    const r = await window.api.ytSearch(q);
    if (seq !== ytSeq) return;
    S.yt = r.error ? { q, status: 'err', items: [], err: r.error } : { q, status: 'ok', items: r };
    renderYt();
  }, 550);
}

async function ytGet(id) {
  const have = ytIdx.get(id);
  if (have) return playQueue([have], 0);
  if (S.dl.get(id)?.state === 'dl') return;
  const item = S.yt.items.find((v) => v.id === id);
  if (!item) return;
  S.dl.set(id, { state: 'dl', pct: 0 });
  paintDl(id);
  try {
    const t = await window.api.ytDownload({ id, title: item.title, author: item.author, useCookies: S.set.ytCookies !== false });
    trackAdded(t);
    if (!S.tracks.some((x) => x.path === t.path)) S.tracks.push(t);
    sorted(S.tracks);
    S.dl.set(id, { state: 'done' });
    if (S.set.autoplay) playQueue([t], 0);
  } catch (err) {
    S.dl.set(id, { state: 'err' });
    const msg = String(err.message || err).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
    const bot = /not a bot|Sign in to confirm/i.test(msg);
    toast(bot ? 'YouTube просит подтвердить, что ты не бот — это временная блокировка по IP из-за частых запросов. Подожди немного (обычно от получаса до пары часов) или включи вход через cookies: Настройки → YouTube: вход.' : `Не получилось скачать: ${msg}`, bot ? 10000 : 6000);
  }
  paintDl(id);
}
window.api.onYtProgress(({ id, pct }) => { const st = S.dl.get(id); if (st?.state === 'dl') { st.pct = pct; paintDl(id); } });
window.api.onYtStatus((msg) => toast(msg, 8000));

// ---------------------------------------------------------------- settings view
const switchRow = (id, title, desc, on) => `<label class="srow"><span>${title}<small>${desc}</small></span><input class="switch" type="checkbox" id="${id}"${on ? ' checked' : ''}></label>`;
function renderSettings(c) {
  c.innerHTML = `<div class="setwrap">
    <div class="panel">
      <h4>Поиск</h4>
      ${switchRow('setYt', 'Искать на YouTube', 'Выключено — только локальная библиотека. Включено — и YouTube, и локально.', S.set.yt)}
      ${switchRow('setAuto', 'Играть сразу после загрузки', 'Скачанный трек запускается автоматически.', S.set.autoplay)}
    </div>
    <div class="panel">
      <h4>Библиотека</h4>
      <div class="frow"><span><b>Загрузки</b><small>${esc(S.dlDir)}</small></span><button class="mini" data-open="${esc(S.dlDir)}">${svg('folder')}Открыть</button></div>
      ${S.folders.map((p, i) => `<div class="frow"><span><b>${esc(fileName(p) || p)}</b><small>${esc(p)}</small></span><button class="mini" data-rm="${i}" title="Убрать из библиотеки">${svg('trash')}</button></div>`).join('')}
      <button class="tonal" id="setAdd">${svg('add')}<span class="lbl">Добавить папку</span></button>
    </div>
    <div class="panel">
      <h4>Загрузчик</h4>
      <div class="frow"><span><b>yt-dlp</b><small id="ytVer">Проверяю…</small></span><button class="mini" id="setUpd">${svg('refresh')}<span>Обновить</span></button></div>
      <p class="hint">Скачивание с YouTube может противоречить условиям сервиса и авторским правам. Используй для контента, на который у тебя есть права.</p>
    </div>
    <div class="panel">
      <h4>YouTube: вход</h4>
      <p class="hint">Иногда YouTube блокирует загрузки по IP («подтвердите, что вы не бот»). Cookies запасного аккаунта это снимают. Используй <b>только запасной аккаунт</b>: cookies дают доступ к аккаунту, а автоматические загрузки могут его ограничить.</p>
      ${switchRow('setCookies', 'Использовать cookies при загрузке', 'Работает, когда файл cookies загружен.', S.set.ytCookies !== false)}
      <div class="frow"><span><b>Файл cookies</b><small id="ckStatus">Проверяю…</small></span><div class="btns"><button class="mini" id="ckImport">Импорт…</button><button class="mini" id="ckRemove">Удалить</button></div></div>
    </div>
    <div class="panel">
      <h4>Окно</h4>
      ${switchRow('setTray', 'Сворачивать в трей при закрытии', 'Крестик прячет окно в трей, музыка продолжает играть. Выйти можно из меню значка в трее.', S.set.tray)}
    </div>
    <div class="panel">
      <h4>Обновления</h4>
      <div class="frow"><span><b id="appVer">Resonance</b><small id="updStatus">Нажми «Проверить», чтобы посмотреть версии на GitHub.</small></span><button class="mini" id="updCheck">${svg('refresh')}<span>Проверить</span></button></div>
      ${switchRow('setUpdAuto', 'Проверять при запуске', 'Только запрос к GitHub — ничего не ставится без твоего нажатия.', S.set.updates)}
      <div id="verList"></div>
      <p class="hint">Версии берутся из GitHub Releases. Установка проверяет контрольную сумму файла. Откат ставит выбранную версию поверх текущей, библиотека и настройки сохраняются.</p>
    </div>
    <div class="panel">
      <h4>Оформление</h4>
      <div class="seg" id="setTheme"><button data-t="dark">Тёмная</button><button data-t="light">Светлая</button></div>
    </div>
  </div>`;
  $$('#setTheme button', c).forEach((b) => b.classList.toggle('on', b.dataset.t === root.dataset.theme));
  paintCookies();
  window.api.appInfo().then((i) => { const el = $('#appVer'); if (el) el.textContent = `Resonance ${i.version}${i.installed ? '' : ' (режим разработки)'}`; });
  if (S.upd) paintVersions();
  // yt-dlp is a one-file exe that takes a moment to start, so the version fills in after the panel is already up
  window.api.ytStatus().then((st) => {
    const el = $('#ytVer');
    if (!el) return;
    el.textContent = st.installed ? `Версия ${st.version}` : 'Скачается автоматически при первой загрузке';
    if (!st.installed) $('#setUpd span').textContent = 'Установить';
  });
}
const saveSet = () => store.set('settings', S.set);

async function paintCookies() {
  const el = $('#ckStatus');
  if (!el) return;
  const st = await window.api.ytCookiesStatus();
  el.textContent = st.present ? `Загружено: ${st.count} cookies` : 'Не загружены';
  const rm = $('#ckRemove'); if (rm) rm.disabled = !st.present;
}

// ---------------------------------------------------------------- updates / rollback (GitHub Releases, see src/update.js)
const cmpVer = (x, y) => { const a = String(x).split('.').map(Number), b = String(y).split('.').map(Number); for (let i = 0; i < 3; i++) if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) - (b[i] || 0); return 0; };
function paintVersions() {
  const u = S.upd, st = $('#updStatus'), list = $('#verList');
  if (!st || !list) return;
  if (!u.ok) { st.textContent = `Не удалось проверить: ${u.error}`; list.innerHTML = ''; return; }
  st.textContent = !u.releases.length ? 'На GitHub пока нет релизов.' : u.hasUpdate ? `Доступна версия ${u.latest} (у тебя ${u.current}).` : `Установлена последняя версия (${u.current}).`;
  list.innerHTML = u.releases.map((r) => {
    const c = cmpVer(r.version, u.current);
    const label = c > 0 ? 'Обновить' : c < 0 ? 'Откатить' : 'Переустановить';
    const firstLine = r.notes.split(/\r?\n/).find((l) => l.trim()) || '';
    const note = firstLine.replace(/[*_`#]/g, '').trim().slice(0, 90); // release notes are markdown; show them as plain text
    return `<div class="frow"><span><b>v${esc(r.version)}${c === 0 ? ' · сейчас' : ''}${r.prerelease ? ' · пре-релиз' : ''}</b><small>${new Date(r.date).toLocaleDateString()}${note ? ' · ' + esc(note) : ''}</small></span><button class="mini" data-ver="${esc(r.version)}"${u.installed ? '' : ' disabled title="Работает только в установленном приложении"'}>${label}</button></div>`;
  }).join('');
}
async function checkUpdates(manual) {
  const btn = $('#updCheck');
  if (btn) btn.disabled = true;
  S.upd = await window.api.updCheck();
  if (btn) btn.disabled = false;
  if (manual || S.view === 'settings') paintVersions();
  if (!manual && S.upd.ok && S.upd.installed && S.upd.hasUpdate) toast(`Доступно обновление ${S.upd.latest} — Настройки → Обновления`, 7000);
}
window.api.onUpdProgress((pct) => { const b = $('[data-ver].busy'); if (b) b.textContent = `${Math.round(pct)}%`; });
async function installVersion(btn) {
  btn.classList.add('busy'); $('[data-ver]').forEach((x) => (x.disabled = true));
  btn.textContent = '0%';
  try { await window.api.updInstall(btn.dataset.ver); toast('Установщик запущен — приложение сейчас закроется', 6000); }
  catch (err) { toast(`Не получилось: ${String(err.message || err).replace(/^Error invoking remote method '[^']+': (Error: )?/, '')}`, 7000); paintVersions(); }
}

// ---------------------------------------------------------------- scanning
let renderTimer = 0;
const renderSoon = () => { clearTimeout(renderTimer); renderTimer = setTimeout(() => { if (!S.q && S.view !== 'eq' && S.view !== 'settings') render(true); }, 200); };
window.api.onBatch((batch) => {
  for (const t of batch) { S.tracks.push(t); trackAdded(t); }
  $('#sub').textContent = `Сканирую… ${S.tracks.length}`;
  renderSoon();
});
async function rescan() {
  S.tracks = []; byPath.clear(); ytIdx.clear();
  await window.api.scan([...new Set([S.dlDir, ...S.folders].filter(Boolean))]);
  sorted(S.tracks);
  render();
}
async function addFolders(paths) {
  const added = paths.filter((p) => p && !S.folders.includes(p) && p !== S.dlDir);
  if (!added.length) return;
  S.folders.push(...added);
  store.set('folders', S.folders);
  await rescan();
}

// ---------------------------------------------------------------- playback
let loadToken = 0;
function playQueue(list, index) {
  S.queue = list.map((t) => t.path);
  S.i = index;
  load();
}
async function load() {
  const t = byPath.get(S.queue[S.i]);
  if (!t) return;
  const token = ++loadToken;
  audio.src = t.url;
  audio.playbackRate = SPEEDS[speedI];
  audio.play().catch(() => {});
  $('#nTitle').textContent = t.title;
  $('#nArtist').textContent = `${t.artist} · ${t.album}`;
  pushTray();
  $('#fav').classList.toggle('on', S.favs.has(t.path));
  $$('.row.active').forEach((r) => r.classList.remove('active'));
  $$('.row').find((r) => r.dataset.path === t.path)?.classList.add('active');
  renderQueue();
  loadLyrics(t);
  const src = await getCover(t);
  if (token !== loadToken) return;
  const img = $('#coverImg');
  img.classList.toggle('on', !!src);
  if (src) img.src = src;
  setPalette((src && (await paletteFrom(src))) || { h: hueOf(t.album + t.title), c: 0.09 });
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({ title: t.title, artist: t.artist, album: t.album, artwork: src ? [{ src }] : [] });
  }
}
function step(dir) {
  if (!S.queue.length) return;
  if (S.shuffle && S.queue.length > 1) {
    let n; do { n = Math.floor(Math.random() * S.queue.length); } while (n === S.i);
    S.i = n;
  } else {
    let n = S.i + dir;
    if (n >= S.queue.length || n < 0) {
      if (S.repeat === 0 && dir > 0) { audio.pause(); audio.currentTime = 0; return; }
      n = (n + S.queue.length) % S.queue.length;
    }
    S.i = n;
  }
  load();
}
const toggle = () => { if (S.i < 0) { if (shown.length) playQueue(shown, 0); return; } audio.paused ? audio.play() : audio.pause(); };
function enqueue(path) {
  if (!byPath.has(path)) return;
  if (S.i < 0) { S.queue = [path]; S.i = 0; load(); return; }
  S.queue.push(path);
  renderQueue();
  toast('Добавлено в очередь', 1800);
}
function renderQueue() {
  const el = $('#queue');
  if (!S.queue.length) { el.innerHTML = `<div class="lystate">${svg('music')}<p>Очередь пуста</p></div>`; return; }
  el.innerHTML = S.queue.map((p, i) => {
    const t = byPath.get(p);
    return t ? `<button class="qrow${i === S.i ? ' active' : ''}" data-qi="${i}"><span class="qn">${i === S.i ? svg('play') : i + 1}</span><span class="t"><b>${esc(t.title)}</b><span>${esc(t.artist)}</span></span><span class="d">${fmt(t.duration)}</span></button>` : '';
  }).join('');
  $('.qrow.active', el)?.scrollIntoView({ block: 'center' });
}

audio.addEventListener('ended', () => { if (S.repeat === 2) { audio.currentTime = 0; audio.play(); } else step(1); });
audio.addEventListener('play', syncPlay);
audio.addEventListener('pause', syncPlay);
// the tray menu/tooltip mirror what is playing
function pushTray() {
  const t = byPath.get(S.queue[S.i]);
  window.api.trayState({ playing: !audio.paused, title: t?.title ?? '', artist: t?.artist ?? '' });
}
window.api.onTrayCmd((cmd) => { if (cmd === 'toggle') toggle(); else if (cmd === 'next') step(1); else if (cmd === 'prev') $('#prev').click(); });

function syncPlay() {
  pushTray();
  $('#play').classList.toggle('playing', !audio.paused);
  $('#playIcon').innerHTML = svg(audio.paused ? 'play' : 'pause');
  if ('mediaSession' in navigator) navigator.mediaSession.playbackState = audio.paused ? 'paused' : 'playing';
}
if ('mediaSession' in navigator) {
  navigator.mediaSession.setActionHandler('play', toggle);
  navigator.mediaSession.setActionHandler('pause', toggle);
  navigator.mediaSession.setActionHandler('previoustrack', () => $('#prev').click());
  navigator.mediaSession.setActionHandler('nexttrack', () => step(1));
}

// ---------------------------------------------------------------- speed + sleep timer
const SPEEDS = [1, 1.25, 1.5, 0.75];
let speedI = 0;
$('#speed').onclick = () => {
  speedI = (speedI + 1) % SPEEDS.length;
  audio.playbackRate = SPEEDS[speedI];
  $('#speed').textContent = `${SPEEDS[speedI]}×`;
};
const SLEEPS = [0, 15, 30, 60];
let sleepI = 0, sleepEnd = 0, sleepTick = 0;
$('#sleep').onclick = () => {
  sleepI = (sleepI + 1) % SLEEPS.length;
  clearInterval(sleepTick);
  sleepEnd = SLEEPS[sleepI] ? Date.now() + SLEEPS[sleepI] * 60000 : 0;
  const paint = () => { $('#sleepTxt').textContent = sleepEnd ? `${Math.ceil((sleepEnd - Date.now()) / 60000)} мин` : 'Сон'; $('#sleep').classList.toggle('on', !!sleepEnd); };
  paint();
  if (!sleepEnd) return;
  sleepTick = setInterval(() => {
    if (Date.now() < sleepEnd) return paint();
    clearInterval(sleepTick); sleepEnd = 0; sleepI = 0; paint();
    const v0 = audio.volume; let k = 0;
    const fade = setInterval(() => { k++; audio.volume = Math.max(0, v0 * (1 - k / 30)); if (k >= 30) { clearInterval(fade); audio.pause(); audio.volume = v0; } }, 100);
  }, 1000);
};

// ---------------------------------------------------------------- frame loop: wavy bar, wavy ring around the cover, morphing cover, EQ spectrum, lyrics
const wave = $('#wave'), ring = $('#ring'), wrap = $('#coverWrap'), cover = $('#cover');
let amp = 0, coverAmp = -1, seeking = false, seekP = 0, seekSrc = '';
const progress = () => (seeking ? seekP : audio.duration ? audio.currentTime / audio.duration : 0);

function fit(cv, w, h) {
  const dpr = devicePixelRatio || 1;
  if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
  const g = cv.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  return g;
}

function drawBar(now, p) {
  const w = wave.clientWidth, h = wave.clientHeight;
  const g = fit(wave, w, h);
  const px = 4 + (w - 8) * p, mid = h / 2, primary = cssColor('--primary');
  g.lineCap = 'round'; g.lineWidth = 4;
  if (px + 8 < w - 4) {
    g.strokeStyle = cssColor('--surface-highest');
    g.beginPath(); g.moveTo(px + 8, mid); g.lineTo(w - 4, mid); g.stroke();
  }
  g.strokeStyle = primary;
  g.beginPath();
  const A = 4.5 * amp, phase = now / 260;
  for (let x = 4; x <= Math.max(4, px - 6); x += 2) {
    const y = mid + Math.sin((x / 26) * Math.PI * 2 - phase) * A;
    x === 4 ? g.moveTo(x, y) : g.lineTo(x, y);
  }
  g.stroke();
  g.fillStyle = primary;
  g.beginPath(); g.roundRect(px - 3, mid - 13, 6, 26, 3); g.fill();
}

const RING_LOBES = 30; // integer so the wave closes seamlessly on a full circle
function drawRing(now, p) {
  const size = wrap.clientWidth;
  if (!size) return;
  const g = fit(ring, size, size);
  const c = size / 2, R = size * 0.462, primary = cssColor('--primary');
  g.lineCap = 'round'; g.lineWidth = Math.max(3, size * 0.012);
  g.strokeStyle = cssColor('--surface-highest');
  g.beginPath(); g.arc(c, c, R, 0, Math.PI * 2); g.stroke();
  if (p <= 0.001) return;
  const A = size * 0.011 * amp, phase = now / 380, a0 = -Math.PI / 2, span = Math.PI * 2 * p;
  g.strokeStyle = primary;
  g.beginPath();
  const steps = Math.max(8, Math.ceil(span * 60));
  for (let i = 0; i <= steps; i++) {
    const th = a0 + (span * i) / steps;
    const r = R + Math.sin((th - a0) * RING_LOBES - phase) * A * Math.min(1, i / 12, (steps - i) / 12 + 0.25);
    const x = c + Math.cos(th) * r, y = c + Math.sin(th) * r;
    i ? g.lineTo(x, y) : g.moveTo(x, y);
  }
  g.stroke();
  const te = a0 + span;
  g.fillStyle = primary;
  g.beginPath(); g.arc(c + Math.cos(te) * R, c + Math.sin(te) * R, g.lineWidth * 1.9, 0, Math.PI * 2); g.fill();
}

function frame(now) {
  requestAnimationFrame(frame);
  const playing = !audio.paused;
  amp += ((playing ? 1 : 0) - amp) * 0.08;
  const p = progress();
  drawBar(now, p);
  if (document.body.dataset.tab === 'now') drawRing(now, p);
  if (document.body.dataset.tab === 'lyrics') tickLyrics(audio.currentTime);
  if (S.view === 'eq' && !S.q) drawEqViz();
  drawViz(now);

  const target = playing ? 0.06 : 0;
  const next = coverAmp < 0 ? target : coverAmp + (target - coverAmp) * 0.07;
  if (Math.abs(next - coverAmp) > 0.0004) { coverAmp = next; cover.style.clipPath = cookie(coverAmp); }

  if (!seeking) {
    $('#tCur').textContent = fmt(audio.currentTime);
    $('#tDur').textContent = fmt(audio.duration);
  }
}
requestAnimationFrame(frame);

// seeking: linear bar, or drag around the ring
const seekBar = (e) => { const r = wave.getBoundingClientRect(); seekP = clamp((e.clientX - r.left - 4) / (r.width - 8), 0, 1); };
const seekRing = (e) => { const r = ring.getBoundingClientRect(); let a = Math.atan2(e.clientY - r.top - r.height / 2, e.clientX - r.left - r.width / 2) + Math.PI / 2; if (a < 0) a += Math.PI * 2; seekP = a / (Math.PI * 2); };
const onRing = (e) => { const r = ring.getBoundingClientRect(); const d = Math.hypot(e.clientX - r.left - r.width / 2, e.clientY - r.top - r.height / 2); return Math.abs(d - r.width * 0.462) < r.width * 0.07; };
function seekStart(src, fn) {
  return (e) => {
    if (!audio.duration || (src === 'ring' && !onRing(e))) return;
    seeking = true; seekSrc = src; e.currentTarget.setPointerCapture(e.pointerId); fn(e);
    $('#tCur').textContent = fmt(seekP * audio.duration);
  };
}
const seekMove = (fn) => (e) => { if (seeking) { fn(e); $('#tCur').textContent = fmt(seekP * audio.duration); } };
const seekEnd = () => { if (!seeking) return; seeking = false; audio.currentTime = seekP * audio.duration; };
wave.addEventListener('pointerdown', seekStart('bar', seekBar));
wave.addEventListener('pointermove', seekMove(seekBar));
wave.addEventListener('pointerup', seekEnd);
ring.addEventListener('pointerdown', seekStart('ring', seekRing));
ring.addEventListener('pointermove', (e) => { if (seeking) seekMove(seekRing)(e); else ring.style.cursor = onRing(e) ? 'pointer' : 'default'; });
ring.addEventListener('pointerup', seekEnd);

// ---------------------------------------------------------------- UI wiring
$('#play').onclick = toggle;
$('#next').onclick = () => step(1);
$('#prev').onclick = () => { if (audio.currentTime > 3) audio.currentTime = 0; else step(-1); };
$('#shuffle').onclick = (e) => { S.shuffle = !S.shuffle; e.currentTarget.classList.toggle('on', S.shuffle); };
$('#repeat').onclick = (e) => {
  S.repeat = (S.repeat + 1) % 3;
  e.currentTarget.classList.toggle('on', S.repeat > 0);
  e.currentTarget.title = ['Повтор выключен', 'Повтор всех', 'Повтор трека'][S.repeat];
  e.currentTarget.innerHTML = svg(S.repeat === 2 ? 'repeatOne' : 'repeat');
};
$('#fav').onclick = (e) => {
  const p = S.queue[S.i];
  if (!p) return;
  S.favs.has(p) ? S.favs.delete(p) : S.favs.add(p);
  store.set('favs', [...S.favs]);
  e.currentTarget.classList.toggle('on', S.favs.has(p));
  if (S.view === 'favs' && !S.q) render(true);
};
const vol = $('#vol');
const syncVol = () => vol.style.setProperty('--p', `${vol.value * 100}%`);
vol.value = audio.volume; syncVol();
vol.oninput = () => { audio.volume = +vol.value; syncVol(); store.set('volume', audio.volume); };

$$('.tab').forEach((b) => (b.onclick = () => {
  document.body.dataset.tab = b.dataset.tab;
  $$('.tab').forEach((x) => x.classList.toggle('on', x === b));
  if (b.dataset.tab === 'queue') renderQueue();
}));

const pickFolder = async () => { const p = await window.api.pickFolder(); if (p) addFolders([p]); };
$('#add').onclick = pickFolder;
$('#q').oninput = (e) => {
  S.q = e.target.value.trim();
  if (!S.q) { ytSeq++; S.yt = { q: '', status: 'idle', items: [] }; return render(); }
  if (!$('#loc')) render(); else renderLoc();
  queueYt();
};
$$('.rail-item[data-view]').forEach((b) => (b.onclick = () => {
  S.view = b.dataset.view; S.filter = null;
  if (S.q) { S.q = ''; $('#q').value = ''; ytSeq++; }
  render();
}));

const activate = (e, handler) => { if (e.type === 'keydown' && e.key !== 'Enter') return; handler(); };
$('#content').addEventListener('click', handleContent);
$('#content').addEventListener('keydown', (e) => { if (e.key === 'Enter' && e.target.matches('.row,.yrow')) handleContent(e); });
async function handleContent(e) {
  const t = e.target;
  const q = t.closest('.rowq');
  if (q) { e.stopPropagation(); return enqueue(q.dataset.q); }
  const row = t.closest('.row');
  if (row) return playQueue(shown, +row.dataset.i);
  const yrow = t.closest('.yrow');
  if (yrow && yrow.dataset.id) return ytGet(yrow.dataset.id);
  const card = t.closest('.card[data-album]');
  if (card) { S.filter = { kind: 'album', value: card.dataset.album, label: card.dataset.label }; S.view = 'tracks'; return render(); }
  const ar = t.closest('.artist');
  if (ar) { S.filter = { kind: 'artist', value: ar.dataset.artist, label: ar.dataset.artist }; S.view = 'tracks'; return render(); }
  if (t.closest('#emptyAdd') || t.closest('#setAdd')) return pickFolder();
  const open = t.closest('[data-open]');
  if (open) return window.api.openDir(open.dataset.open);
  const rm = t.closest('[data-rm]');
  if (rm) { S.folders.splice(+rm.dataset.rm, 1); store.set('folders', S.folders); await rescan(); return; }
  if (t.closest('#setUpd')) {
    const btn = t.closest('#setUpd'); btn.disabled = true;
    try { const v = await window.api.ytUpdate(); $('#ytVer').textContent = `Версия ${v}`; toast('yt-dlp обновлён'); } catch (err) { toast(`Не удалось обновить: ${err.message || err}`, 6000); }
    btn.disabled = false; return;
  }
  if (t.closest('#ckImport')) {
    const r = await window.api.ytCookiesImport();
    if (r.ok) { S.set.ytCookies = true; saveSet(); const sw = $('#setCookies'); if (sw) sw.checked = true; toast(`Cookies загружены: ${r.count}`, 3000); }
    else if (!r.canceled) toast(`Не получилось: ${r.error}`, 6000);
    return paintCookies();
  }
  if (t.closest('#ckRemove')) { await window.api.ytCookiesRemove(); toast('Cookies удалены', 2500); return paintCookies(); }
  if (t.closest('#updCheck')) return checkUpdates(true);
  const vb = t.closest('[data-ver]');
  if (vb && !vb.disabled) return installVersion(vb);
  const th = t.closest('#setTheme button');
  if (th) { applyTheme(th.dataset.t); $$('#setTheme button').forEach((b) => b.classList.toggle('on', b === th)); }
}
$('#content').addEventListener('change', (e) => {
  if (e.target.id === 'setYt') { S.set.yt = e.target.checked; saveSet(); if (!S.set.yt) { ytSeq++; S.yt = { q: '', status: 'idle', items: [] }; } toast(S.set.yt ? 'Поиск на YouTube включён' : 'Только локальный поиск', 2000); }
  if (e.target.id === 'setAuto') { S.set.autoplay = e.target.checked; saveSet(); }
  if (e.target.id === 'setCookies') { S.set.ytCookies = e.target.checked; saveSet(); }
  if (e.target.id === 'setUpdAuto') { S.set.updates = e.target.checked; saveSet(); }
  if (e.target.id === 'setTray') { S.set.tray = e.target.checked; saveSet(); window.api.trayConfig({ closeToTray: S.set.tray }); }
});
$('#chips').addEventListener('click', (e) => { if (e.target.closest('#clearFilter')) { S.filter = null; render(); } });
$('#queue').addEventListener('click', (e) => {
  const r = e.target.closest('.qrow');
  if (r) { S.i = +r.dataset.qi; load(); }
});

const applyTheme = (t) => {
  root.dataset.theme = t; store.set('theme', t);
};
applyTheme(store.get('theme', 'dark'));

addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' && e.target.type !== 'range' && e.target.type !== 'checkbox') return;
  if (e.code === 'Space' && !e.target.closest('.row,.yrow,button')) { e.preventDefault(); toggle(); }
  if (e.code === 'ArrowRight' && e.ctrlKey) step(1);
  if (e.code === 'ArrowLeft' && e.ctrlKey) $('#prev').click();
  if (e.key === '/' && e.target.tagName !== 'INPUT') { e.preventDefault(); $('#q').focus(); }
});
addEventListener('dragover', (e) => e.preventDefault());
addEventListener('drop', (e) => {
  e.preventDefault();
  addFolders([...e.dataTransfer.files].map((f) => window.api.pathOf(f)));
});

// ---------------------------------------------------------------- window header (Zen-style: hidden until the pointer touches the top edge)
window.api.onBarState((on) => document.body.classList.toggle('bar-on', on)); // pointer tracking lives in main.js
$('#wMin').onclick = () => window.api.winMin();
$('#wMax').onclick = () => window.api.winMax();
$('#wClose').onclick = () => window.api.winClose();
const paintMax = (max) => { $('#wMax').innerHTML = max ? '<svg viewBox="0 0 16 16"><rect x="3.5" y="5.5" width="7" height="7" rx="1"/><path d="M5.5 5.5v-1a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-1"/></svg>' : '<svg viewBox="0 0 16 16"><rect x="3.5" y="3.5" width="9" height="9" rx="1"/></svg>'; $('#wMax').title = max ? 'Свернуть в окно' : 'Развернуть'; };
window.api.onWinState(paintMax);
window.api.winIsMax().then(paintMax);

// ---------------------------------------------------------------- boot
(async () => {
  syncPlay();
  window.api.trayConfig({ closeToTray: S.set.tray });
  S.dlDir = await window.api.downloadDir();
  renderQueue();
  await rescan();
  $('#q').focus();
  if (S.set.updates) setTimeout(() => checkUpdates(false), 4000); // quiet: only toasts when a newer release exists
})();
