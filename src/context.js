'use strict';
// Right-click menus: tracks (library, search, favourites, queue, now playing), albums, artists, YouTube results and text fields.
// Renaming and deleting always ask for confirmation first. Deleting moves the file to the Recycle Bin, never a hard delete.
// Edits (names, albums) are stored in the app profile (overrides.json), not written into the audio files.

const KEY_SEP = String.fromCharCode(0x2016); // same separator as albumKey() in app.js
const q = (t) => `«${t.title}»`;
const inLibraryView = () => S.view !== 'eq' && S.view !== 'settings';

// ---------------------------------------------------------------- helpers
function toggleFavPath(p) {
  S.favs.has(p) ? S.favs.delete(p) : S.favs.add(p);
  store.set('favs', [...S.favs]);
  if (S.queue[S.i] === p) $('#fav').classList.toggle('on', S.favs.has(p));
  if (S.view === 'favs' && !S.q) render(true);
}

function playNext(p) {
  if (S.i < 0 || !S.queue.length) return enqueue(p); // enqueue starts playback when nothing is queued yet
  S.queue.splice(S.i + 1, 0, p);
  renderQueue();
  toast('Сыграет следующим', 1800);
}

function removeFromQueue(i) {
  S.queue.splice(i, 1);
  if (i < S.i) S.i--;
  renderQueue();
}

function afterEdit(t, titleChanged) {
  if (inLibraryView()) render(true);
  renderQueue();
  if (S.queue[S.i] === t.path) {
    $('#nTitle').textContent = t.title;
    $('#nArtist').textContent = `${t.artist} · ${t.album}`;
    pushTray();
    if (titleChanged) loadLyrics(t); // the lyrics lookup is keyed by title
  }
}

async function patchTrack(t, patch) {
  await window.api.trackUpdate(t.path, patch);
  Object.assign(t, patch);
  sorted(S.tracks);
  afterEdit(t, 'title' in patch);
}

function albumGroups() {
  return [...groupBy(S.tracks, albumKey)].map(([key, ts]) => ({
    key, name: ts[0].album, tracks: ts,
    sub: `${new Set(ts.map((x) => x.albumArtist)).size > 1 ? 'Разные артисты' : ts[0].albumArtist} · ${ts.length}`,
  }));
}

// ---------------------------------------------------------------- actions (each of the destructive ones asks first)
async function renameTrack(t) {
  const r = await modal({ title: 'Изменить название', text: 'Новое название трека:', input: { value: t.title }, ok: 'Далее' });
  if (!r.ok || !r.value || r.value === t.title) return;
  const c = await modal({ title: 'Переименовать трек?', text: `${q(t)} → «${r.value}»`, ok: 'Переименовать' });
  if (!c.ok) return;
  await patchTrack(t, { title: r.value });
  toast('Название изменено', 1800);
}

async function moveToAlbum(t) {
  const groups = albumGroups().filter((g) => g.key !== albumKey(t));
  const r = await modal({
    title: 'В альбом', text: `Куда переместить ${q(t)}?`, ok: 'Переместить',
    list: [{ label: '＋ Новый альбом…', sub: 'Создать свою подборку' }, ...groups.map((g) => ({ label: g.name, sub: g.sub }))],
  });
  if (!r.ok) return;
  if (r.index === 0) {
    const n = await modal({ title: 'Новый альбом', text: 'Название альбома:', input: { value: '' }, ok: 'Создать' });
    if (!n.ok) return;
    await patchTrack(t, { album: n.value, albumKey: 'user' + KEY_SEP + n.value });
  } else {
    const g = groups[r.index - 1];
    await patchTrack(t, { album: g.name, albumKey: g.key });
  }
  toast('Перемещено в альбом', 1800);
}

async function renameAlbum(key) {
  const g = albumGroups().find((x) => x.key === key);
  if (!g) return;
  const r = await modal({ title: 'Переименовать альбом', text: 'Новое название:', input: { value: g.name }, ok: 'Далее' });
  if (!r.ok || !r.value || r.value === g.name) return;
  const c = await modal({ title: 'Переименовать альбом?', text: `«${g.name}» → «${r.value}» (треков: ${g.tracks.length})`, ok: 'Переименовать' });
  if (!c.ok) return;
  for (const t of g.tracks) { // the key is pinned, so the group stays together under its new name
    await window.api.trackUpdate(t.path, { album: r.value, albumKey: key });
    Object.assign(t, { album: r.value, albumKey: key });
  }
  sorted(S.tracks);
  afterEdit(g.tracks[0], false);
  toast('Альбом переименован', 1800);
}

function resetNowPlaying() {
  audio.pause(); audio.removeAttribute('src'); audio.load();
  S.i = -1;
  $('#nTitle').textContent = 'Ничего не играет';
  $('#nArtist').textContent = 'Выбери трек в библиотеке';
  $('#coverImg').classList.remove('on');
  $('#fav').classList.remove('on');
  loadLyrics(null);
  pushTray();
}

async function deleteTrack(t) {
  const r = await modal({ title: 'Удалить трек?', text: `${q(t)} будет перемещён в корзину Windows. Оттуда его можно восстановить.`, ok: 'Удалить в корзину', danger: true });
  if (!r.ok) return;
  const wasCurrent = S.queue[S.i] === t.path;
  if (wasCurrent) { audio.pause(); audio.removeAttribute('src'); audio.load(); } // release the file so it can be moved
  const res = await window.api.trackDelete(t.path);
  if (!res.ok) { toast(`Не удалось удалить: ${res.error}`, 6000); if (wasCurrent) load(); return; }

  S.tracks = S.tracks.filter((x) => x.path !== t.path);
  byPath.delete(t.path);
  for (const [id, tr] of ytIdx) if (tr.path === t.path) { ytIdx.delete(id); S.dl.delete(id); }
  if (S.favs.delete(t.path)) store.set('favs', [...S.favs]);

  const cur = S.queue[S.i], oldI = S.i;
  const before = S.queue.slice(0, Math.max(0, oldI)).filter((p) => p !== t.path).length; // where the next track lands after removal
  S.queue = S.queue.filter((p) => p !== t.path);
  if (wasCurrent) {
    if (S.queue.length) { S.i = Math.min(before, S.queue.length - 1); load(); } else resetNowPlaying();
  } else S.i = cur ? S.queue.indexOf(cur) : -1;

  if (inLibraryView()) render(true);
  renderQueue();
  if (S.q) { renderLoc(); }
  toast('Перемещено в корзину', 2500);
}

// ---------------------------------------------------------------- menu builders
function trackMenu(t, ctx = {}) {
  const fav = S.favs.has(t.path);
  const isCurrent = S.queue[S.i] === t.path;
  const items = [
    { label: 'Играть', run: () => (ctx.list ? playQueue(ctx.list, ctx.index) : playQueue([t], 0)) },
    { label: 'Играть следующим', run: () => playNext(t.path) },
    { label: 'Добавить в очередь', run: () => enqueue(t.path) },
  ];
  if (ctx.queueIndex !== undefined && ctx.queueIndex !== S.i) items.push({ label: 'Убрать из очереди', run: () => removeFromQueue(ctx.queueIndex) });
  items.push({ label: fav ? 'Убрать из избранного' : 'В избранное', run: () => toggleFavPath(t.path) }, { sep: true });
  if (isCurrent) items.push({ label: 'Подобрать текст…', run: () => { $('.tab[data-tab=lyrics]').click(); openPicker(); } });
  items.push(
    { label: 'В альбом…', run: () => moveToAlbum(t) },
    { label: 'Изменить название…', run: () => renameTrack(t) },
    { label: 'Показать в папке', run: () => window.api.reveal(t.path) },
    { sep: true },
    { label: 'Удалить…', danger: true, run: () => deleteTrack(t) },
  );
  return items;
}

function albumMenu(key) {
  const g = albumGroups().find((x) => x.key === key);
  if (!g) return null;
  return [
    { label: 'Открыть', run: () => { S.filter = { kind: 'album', value: key, label: g.name }; S.view = 'tracks'; render(); } },
    { label: 'Играть альбом', run: () => playQueue(g.tracks, 0) },
    { label: 'Добавить в очередь', run: () => { g.tracks.forEach((t) => S.queue.push(t.path)); if (S.i < 0) { S.i = 0; load(); } renderQueue(); toast(`В очередь: ${g.tracks.length}`, 1800); } },
    { sep: true },
    { label: 'Переименовать альбом…', run: () => renameAlbum(key) },
  ];
}

function artistMenu(name) {
  const ts = S.tracks.filter((t) => t.artist === name || t.albumArtist === name);
  return [
    { label: 'Открыть', run: () => { S.filter = { kind: 'artist', value: name, label: name }; S.view = 'tracks'; render(); } },
    { label: 'Играть всё', run: () => playQueue(ts, 0) },
    { label: 'Добавить в очередь', run: () => { ts.forEach((t) => S.queue.push(t.path)); if (S.i < 0 && ts.length) { S.i = 0; load(); } renderQueue(); toast(`В очередь: ${ts.length}`, 1800); } },
  ];
}

function youtubeMenu(id) {
  const url = `https://www.youtube.com/watch?v=${id}`;
  const local = ytIdx.get(id);
  const items = [
    { label: local ? 'Играть' : 'Скачать и слушать', run: () => ytGet(id) },
    { label: 'Открыть на YouTube', run: () => window.api.openUrl(url) },
    { label: 'Копировать ссылку', run: async () => { await window.api.copyText(url); toast('Ссылка скопирована', 1800); } },
  ];
  if (local) items.push({ sep: true }, { label: 'Удалить скачанный трек…', danger: true, run: () => deleteTrack(local) });
  return items;
}

function fieldMenu(field) {
  const sel = field.selectionStart !== field.selectionEnd;
  return [
    { label: 'Вырезать', disabled: !sel, run: () => window.api.editCmd('cut') },
    { label: 'Копировать', disabled: !sel, run: () => window.api.editCmd('copy') },
    { label: 'Вставить', run: () => window.api.editCmd('paste') },
    { sep: true },
    { label: 'Выделить всё', run: () => window.api.editCmd('selectAll') },
  ];
}

// ---------------------------------------------------------------- wiring
document.addEventListener('contextmenu', (e) => {
  const t = e.target;
  let items = null;

  const field = t.closest('input[type=text], input[type=search], textarea');
  if (field) items = fieldMenu(field);

  if (!items) {
    const row = t.closest('.row');
    const qrow = t.closest('.qrow');
    const card = t.closest('.card[data-album]');
    const artist = t.closest('.artist[data-artist]');
    const yrow = t.closest('.yrow[data-id]');
    const now = t.closest('.cover-wrap, .meta');
    if (row) { const tr = byPath.get(row.dataset.path); if (tr) items = trackMenu(tr, { list: shown, index: +row.dataset.i }); }
    else if (qrow) { const i = +qrow.dataset.qi, tr = byPath.get(S.queue[i]); if (tr) items = trackMenu(tr, { list: S.queue.map((p) => byPath.get(p)).filter(Boolean), index: i, queueIndex: i }); }
    else if (card) items = albumMenu(card.dataset.album);
    else if (artist) items = artistMenu(artist.dataset.artist);
    else if (yrow) items = youtubeMenu(yrow.dataset.id);
    else if (now) { const tr = byPath.get(S.queue[S.i]); if (tr) items = trackMenu(tr); }
  }

  if (items) { e.preventDefault(); showMenu(items, e.clientX, e.clientY); }
});
