'use strict';

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const root = document.documentElement;

const ICON = {
  play: 'M8 5v14l11-7z',
  pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
  next: 'M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z',
  prev: 'M6 6h2v12H6zm3.5 6l8.5 6V6z',
  shuffle: 'M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z',
  repeat: 'M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z',
  volume: 'M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z',
  search: 'M15.5 14h-.79l-.28-.27A6.471 6.471 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  music: 'M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z',
  album: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z',
  person: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
  add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  close: 'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  folder: 'M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z',
  theme: 'M20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69L23.31 12 20 8.69zM12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6zm0-10v8c2.21 0 4-1.79 4-4s-1.79-4-4-4z',
};
const svg = (n) => `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${ICON[n]}"/></svg>`;
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
$$('[data-i]').forEach((el) => (el.innerHTML = svg(el.dataset.i)));

const store = {
  get(k, d) { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* storage unavailable */ } },
};

// ---------------------------------------------------------------- state
const S = {
  tracks: [], view: 'tracks', filter: null, q: '',
  queue: [], i: -1, shuffle: false, repeat: 0, // repeat: 0 off, 1 all, 2 one
  folders: store.get('folders', []),
};
const audio = new Audio();
audio.volume = store.get('volume', 0.8);
const byPath = new Map();
const albumKey = (t) => `${t.albumArtist}\u0000${t.album}`;
const fmt = (s) => { s = Math.max(0, Math.floor(s || 0)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };
const hueOf = (str) => { let h = 0; for (const ch of str) h = (h * 31 + ch.charCodeAt(0)) % 360; return h; };
const plural = (n, a, b, c) => { const m = n % 100, k = n % 10; return m > 10 && m < 20 ? c : k === 1 ? a : k >= 2 && k <= 4 ? b : c; };

// ---------------------------------------------------------------- shapes (cookie cover)
const COOKIE_N = 9;
function cookie(amp) {
  const pts = [];
  for (let i = 0; i < 120; i++) {
    const a = (i / 120) * Math.PI * 2;
    const r = 1 - amp + amp * Math.cos(COOKIE_N * a);
    pts.push(`${(50 + 50 * r * Math.cos(a)).toFixed(2)}% ${(50 + 50 * r * Math.sin(a)).toFixed(2)}%`);
  }
  return `polygon(${pts.join(',')})`;
}
root.style.setProperty('--cookie', cookie(0.06));

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
        const w = (a * a + b * b) + 0.0005; // weigh vivid pixels heavily
        sa += a * w; sb += b * w; sw += w;
      }
      const a = sa / sw, b = sb / sw;
      resolve({ h: ((Math.atan2(b, a) * 180) / Math.PI + 360) % 360, c: Math.min(0.17, Math.max(0.012, Math.hypot(a, b) * 1.15)) });
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
  const k = albumKey(t);
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

// ---------------------------------------------------------------- library views
function sorted(list) {
  return list.sort((a, b) =>
    a.albumArtist.localeCompare(b.albumArtist) || a.album.localeCompare(b.album) ||
    a.disk - b.disk || a.no - b.no || a.title.localeCompare(b.title));
}
function visible() {
  let list = S.tracks;
  if (S.filter?.kind === 'album') list = list.filter((t) => albumKey(t) === S.filter.value);
  if (S.filter?.kind === 'artist') list = list.filter((t) => t.artist === S.filter.value || t.albumArtist === S.filter.value);
  if (S.q) {
    const q = S.q.toLowerCase();
    list = list.filter((t) => `${t.title} ${t.artist} ${t.album}`.toLowerCase().includes(q));
  }
  return list;
}
const groupBy = (list, keyFn) => { const m = new Map(); for (const t of list) { const k = keyFn(t); (m.get(k) ?? m.set(k, []).get(k)).push(t); } return m; };

let shown = [];
function render() {
  const list = visible();
  shown = list;
  const titles = { tracks: 'Треки', albums: 'Альбомы', artists: 'Артисты' };
  const f = S.filter;
  $('#title').textContent = f ? f.label : titles[S.view];
  $('#sub').textContent = S.tracks.length ? `${list.length} ${plural(list.length, 'трек', 'трека', 'треков')}` : 'Библиотека пуста';
  $('#chips').innerHTML = f ? `<button class="chip" id="clearFilter">${svg('close')}${esc(f.kind === 'album' ? 'Альбом' : 'Артист')}</button>` : '';
  $$('.rail-item[data-view]').forEach((b) => b.classList.toggle('active', b.dataset.view === S.view));

  const c = $('#content');
  c.scrollTop = 0;
  if (!S.tracks.length) {
    c.innerHTML = `<div class="empty"><div class="blob">${svg('music')}</div><h3>Добавь свою музыку</h3>
      <p>Выбери папку с треками или перетащи её в окно. Поддерживаются mp3, flac, ogg, opus, m4a, wav.</p>
      <button class="btn" id="emptyAdd">${svg('folder')}Выбрать папку</button></div>`;
    return;
  }
  if (S.view === 'albums' && !f) {
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
    c.innerHTML = list.length ? `<div class="list">${list.map((t, i) => `
      <button class="row${t.path === S.queue[S.i] ? ' active' : ''}" data-i="${i}" data-path="${esc(t.path)}">
        <div class="art">${esc([...t.title][0]?.toUpperCase() ?? '♪')}</div>
        <div class="t"><b>${esc(t.title)}</b><span>${esc(t.artist)}</span></div>
        <div class="al">${esc(t.album)}</div><div class="d">${fmt(t.duration)}</div></button>`).join('')}</div>`
      : `<div class="empty"><h3>Ничего не найдено</h3><p>Попробуй другой запрос.</p></div>`;
  }
}

// ---------------------------------------------------------------- scanning
let renderTimer = 0;
const renderSoon = () => { clearTimeout(renderTimer); renderTimer = setTimeout(render, 160); };
window.api.onBatch((batch) => {
  for (const t of batch) { S.tracks.push(t); byPath.set(t.path, t); }
  $('#sub').textContent = `Сканирую… ${S.tracks.length}`;
  renderSoon();
});
async function rescan() {
  if (!S.folders.length) return render();
  S.tracks = []; byPath.clear();
  await window.api.scan(S.folders);
  sorted(S.tracks);
  render();
}
async function addFolders(paths) {
  const added = paths.filter((p) => p && !S.folders.includes(p));
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
  audio.play().catch(() => {});
  $('#nTitle').textContent = t.title;
  $('#nArtist').textContent = `${t.artist} · ${t.album}`;
  $$('.row.active').forEach((r) => r.classList.remove('active'));
  $$('.row').find((r) => r.dataset.path === t.path)?.classList.add('active');
  const src = await getCover(t);
  if (token !== loadToken) return;
  const img = $('#coverImg');
  img.classList.toggle('on', !!src);
  if (src) img.src = src;
  setPalette((src && (await paletteFrom(src))) || { h: hueOf(t.album), c: 0.09 });
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

audio.addEventListener('ended', () => { if (S.repeat === 2) { audio.currentTime = 0; audio.play(); } else step(1); });
audio.addEventListener('play', syncPlay);
audio.addEventListener('pause', syncPlay);
function syncPlay() {
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

// ---------------------------------------------------------------- wavy progress + morphing cover
const wave = $('#wave');
const cover = $('#cover');
const probes = {};
for (const [k, v] of Object.entries({ primary: '--primary', track: '--surface-highest' })) {
  const el = document.createElement('i');
  el.style.cssText = `position:absolute;visibility:hidden;color:var(${v})`;
  document.body.append(el);
  probes[k] = el;
}
let amp = 0, coverAmp = -1, seeking = false, seekP = 0;
function frame(now) {
  requestAnimationFrame(frame);
  const playing = !audio.paused;
  amp += ((playing ? 1 : 0) - amp) * 0.08;

  const dpr = devicePixelRatio || 1;
  const w = wave.clientWidth, h = wave.clientHeight;
  if (wave.width !== Math.round(w * dpr)) { wave.width = Math.round(w * dpr); wave.height = Math.round(h * dpr); }
  const g = wave.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  const p = seeking ? seekP : audio.duration ? audio.currentTime / audio.duration : 0;
  const px = 4 + (w - 8) * p, mid = h / 2;
  const primary = getComputedStyle(probes.primary).color;
  g.lineCap = 'round'; g.lineWidth = 4;
  if (px + 8 < w - 4) {
    g.strokeStyle = getComputedStyle(probes.track).color;
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

  const target = playing ? 0.06 : 0.0;
  const next = coverAmp < 0 ? target : coverAmp + (target - coverAmp) * 0.07;
  if (Math.abs(next - coverAmp) > 0.0004) { coverAmp = next; cover.style.clipPath = cookie(coverAmp); }

  if (!seeking) {
    $('#tCur').textContent = fmt(audio.currentTime);
    $('#tDur').textContent = fmt(audio.duration);
  }
}
requestAnimationFrame(frame);

const seekFrom = (e) => { const r = wave.getBoundingClientRect(); seekP = Math.min(1, Math.max(0, (e.clientX - r.left - 4) / (r.width - 8))); $('#tCur').textContent = fmt(seekP * audio.duration); };
wave.addEventListener('pointerdown', (e) => { if (!audio.duration) return; seeking = true; wave.setPointerCapture(e.pointerId); seekFrom(e); });
wave.addEventListener('pointermove', (e) => { if (seeking) seekFrom(e); });
wave.addEventListener('pointerup', () => { if (!seeking) return; seeking = false; audio.currentTime = seekP * audio.duration; });

// ---------------------------------------------------------------- UI wiring
$('#play').onclick = toggle;
$('#next').onclick = () => step(1);
$('#prev').onclick = () => { if (audio.currentTime > 3) audio.currentTime = 0; else step(-1); };
$('#shuffle').onclick = (e) => { S.shuffle = !S.shuffle; e.currentTarget.classList.toggle('on', S.shuffle); };
$('#repeat').onclick = (e) => {
  S.repeat = (S.repeat + 1) % 3;
  e.currentTarget.classList.toggle('on', S.repeat > 0);
  e.currentTarget.title = ['Повтор выключен', 'Повтор всех', 'Повтор трека'][S.repeat];
};
const vol = $('#vol');
const syncVol = () => vol.style.setProperty('--p', `${vol.value * 100}%`);
vol.value = audio.volume; syncVol();
vol.oninput = () => { audio.volume = +vol.value; syncVol(); store.set('volume', audio.volume); };

$('#add').onclick = async () => { const p = await window.api.pickFolder(); if (p) addFolders([p]); };
$('#q').oninput = (e) => { S.q = e.target.value.trim(); render(); };
$$('.rail-item[data-view]').forEach((b) => (b.onclick = () => { S.view = b.dataset.view; S.filter = null; render(); }));

$('#content').addEventListener('click', async (e) => {
  const row = e.target.closest('.row');
  if (row) return playQueue(shown, +row.dataset.i);
  const card = e.target.closest('.card');
  if (card) { S.filter = { kind: 'album', value: card.dataset.album, label: card.dataset.label }; S.view = 'tracks'; return render(); }
  const ar = e.target.closest('.artist');
  if (ar) { S.filter = { kind: 'artist', value: ar.dataset.artist, label: ar.dataset.artist }; S.view = 'tracks'; return render(); }
  if (e.target.closest('#emptyAdd')) { const p = await window.api.pickFolder(); if (p) addFolders([p]); }
});
$('#chips').addEventListener('click', (e) => { if (e.target.closest('#clearFilter')) { S.filter = null; render(); } });

const themeBtn = $('#theme');
const applyTheme = (t) => { root.dataset.theme = t; store.set('theme', t); };
applyTheme(store.get('theme', 'dark'));
themeBtn.onclick = () => applyTheme(root.dataset.theme === 'dark' ? 'light' : 'dark');

addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' && e.target.type !== 'range') return;
  if (e.code === 'Space') { e.preventDefault(); toggle(); }
  if (e.code === 'ArrowRight' && e.ctrlKey) step(1);
  if (e.code === 'ArrowLeft' && e.ctrlKey) $('#prev').click();
});
addEventListener('dragover', (e) => e.preventDefault());
addEventListener('drop', (e) => {
  e.preventDefault();
  addFolders([...e.dataTransfer.files].map((f) => window.api.pathOf(f)));
});

syncPlay();
rescan();
