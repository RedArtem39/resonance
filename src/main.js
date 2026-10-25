const { app, BrowserWindow, ipcMain, dialog, protocol, net, shell, screen, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs/promises');
const { createWriteStream, createReadStream, existsSync } = require('fs');
const { spawn } = require('child_process');
const crypto = require('crypto');
const { pathToFileURL } = require('url');
const { Readable } = require('stream');
const { pipeline } = require('stream/promises');

// Local files are served through rmedia:// with CORS enabled so Web Audio (EQ, visualizer) gets real samples.
protocol.registerSchemesAsPrivileged([
  { scheme: 'rmedia', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true, bypassCSP: true } },
]);

const EXT = new Set(['.mp3', '.flac', '.ogg', '.opus', '.m4a', '.aac', '.wav', '.wma', '.webm']);
const AUDIO_MIME = {
  '.mp3': 'audio/mpeg', '.flac': 'audio/flac', '.ogg': 'audio/ogg', '.opus': 'audio/ogg', '.m4a': 'audio/mp4',
  '.aac': 'audio/aac', '.wav': 'audio/wav', '.wma': 'audio/x-ms-wma', '.webm': 'audio/webm',
};
const COVER_NAMES =['cover', 'folder', 'front', 'album'];
const COVER_EXTS = ['.jpg', '.jpeg', '.png', '.webp'];
const MIME = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp' };
const UA = 'Resonance/0.2 (offline music player)';

app.setAppUserModelId('io.github.redartem39.resonance'); // same id as the installer's shortcuts, so the taskbar groups them

let mmPromise;
const mm = () => (mmPromise ??= import('music-metadata'));
const mediaUrl = (f) => `rmedia://f/${encodeURIComponent(f)}`;
const downloadDir = () => path.join(app.getPath('music'), 'Resonance');
const binDir = () => path.join(app.getPath('userData'), 'bin');
const ytdlpPath = () => path.join(binDir(), 'yt-dlp.exe');

function createWindow() {
  const win = new BrowserWindow({
    width: 1360,
    height: 840,
    minWidth: 1040,
    minHeight: 680,
    backgroundColor: '#14121a',
    frame: false,
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), backgroundThrottling: false }, // keep the queue advancing while hidden in the tray
  });
  mainWin = win;
  win.setMenuBarVisibility(false);
  // closing the window hides it to the tray (music keeps playing); Quit in the tray menu really exits
  win.on('close', (e) => {
    if (quitting || !closeToTray || !tray) return;
    e.preventDefault();
    win.hide();
    if (!balloonShown) {
      balloonShown = true;
      tray.displayBalloon?.({ title: 'Resonance', content: 'Продолжаю играть в трее. Нажми на значок, чтобы открыть окно.', iconType: 'none' });
    }
  });
  win.on('session-end', () => { quitting = true; });
  const sendState = () => !win.isDestroyed() && win.webContents.send('win:state', win.isMaximized());
  win.on('maximize', sendState);
  win.on('unmaximize', sendState);
  // Header visibility is decided here, not in the page: a drag region swallows pointer events, but the OS cursor
  // position is always known. Show at the very top edge, keep while the pointer is on the header (incl. while dragging
  // the window by it), hide shortly after the pointer leaves.
  const BAR_H = 35;
  let barOn = false, away = 0;
  const poll = setInterval(() => {
    if (win.isDestroyed()) return clearInterval(poll);
    if (!win.isVisible() || win.isMinimized()) return;
    const p = screen.getCursorScreenPoint(), b = win.getBounds();
    const inX = p.x >= b.x && p.x < b.x + b.width, rel = p.y - b.y;
    let want = barOn;
    if (inX && rel >= -12 && rel <= 6 && win.isFocused()) { want = true; away = 0; }
    else if (barOn && (!inX || rel < -12 || rel > BAR_H + 14)) { if (++away >= 4) want = false; }
    else away = 0;
    if (want !== barOn) { barOn = want; win.webContents.send('bar:state', barOn); }
  }, 60);
  win.loadFile(path.join(__dirname, 'index.html'));
}

// ------------------------------------------------------------------ tray
let mainWin = null, tray = null, quitting = false, closeToTray = true, balloonShown = false;
const player = { playing: false, title: '', artist: '' }; // mirrored from the page, used for the tray menu and tooltip

function showWindow() {
  if (!mainWin || mainWin.isDestroyed()) return;
  if (mainWin.isMinimized()) mainWin.restore();
  mainWin.show();
  mainWin.focus();
}
const toggleWindow = () => (mainWin?.isVisible() && !mainWin.isMinimized() ? mainWin.hide() : showWindow());
const trayCommand = (cmd) => { if (mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('tray:cmd', cmd); };

const trayMenuTemplate = () => [
  { label: player.title ? `${player.title}${player.artist ? ' — ' + player.artist : ''}` : 'Ничего не играет', enabled: false },
  { type: 'separator' },
  { label: player.playing ? 'Пауза' : 'Играть', click: () => trayCommand('toggle') },
  { label: 'Следующий трек', click: () => trayCommand('next') },
  { label: 'Предыдущий трек', click: () => trayCommand('prev') },
  { type: 'separator' },
  { label: mainWin?.isVisible() ? 'Скрыть окно' : 'Показать окно', click: toggleWindow },
  { type: 'separator' },
  { label: 'Выйти', click: () => { quitting = true; app.quit(); } },
];

function updateTray() {
  if (!tray) return;
  tray.setContextMenu(Menu.buildFromTemplate(trayMenuTemplate()));
  const tip = player.title ? `Resonance — ${player.title}${player.artist ? ' — ' + player.artist : ''}` : 'Resonance';
  tray.setToolTip(tip.slice(0, 120)); // Windows truncates tooltips at ~128 characters
}

function createTray() {
  const icon = nativeImage.createFromPath(path.join(__dirname, '..', 'build', 'icon.png')).resize({ width: 32, height: 32, quality: 'best' });
  tray = new Tray(icon);
  tray.on('click', toggleWindow); // left click toggles the window, right click opens the menu
  mainWin.on('show', updateTray);
  mainWin.on('hide', updateTray);
  updateTray();
}

ipcMain.on('tray:state', (_, s) => { Object.assign(player, { playing: !!s.playing, title: s.title ?? player.title, artist: s.artist ?? player.artist }); updateTray(); });
ipcMain.on('tray:config', (_, c) => { closeToTray = !!c.closeToTray; });
app.on('before-quit', () => { quitting = true; });

// one player at a time: starting it again (shortcut, Start menu) brings the existing window back, even from the tray
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) app.quit();
else app.on('second-instance', showWindow);

// ------------------------------------------------------------------ library scanning
async function* walk(p) {
  let st;
  try { st = await fs.stat(p); } catch { return; }
  if (st.isDirectory()) {
    let entries = [];
    try { entries = await fs.readdir(p, { withFileTypes: true }); } catch { return; }
    for (const e of entries) yield* walk(path.join(p, e.name));
  } else if (EXT.has(path.extname(p).toLowerCase())) {
    yield p;
  }
}

async function readSidecar(f) {
  try { return JSON.parse(await fs.readFile(f.slice(0, -path.extname(f).length) + '.meta.json', 'utf8')); } catch { return null; }
}

async function readTrack(f) {
  const base = path.basename(f, path.extname(f));
  const t = {
    path: f, url: mediaUrl(f), title: base,
    artist: 'Неизвестный артист', album: 'Без альбома', albumArtist: 'Неизвестный артист',
    no: 0, disk: 0, year: 0, duration: 0,
  };
  try {
    const m = await (await mm()).parseFile(f, { skipCovers: true });
    const c = m.common;
    t.title = c.title || base;
    t.artist = c.artist || t.artist;
    t.album = c.album || t.album;
    t.albumArtist = c.albumartist || c.artist || t.albumArtist;
    t.no = c.track?.no || 0;
    t.disk = c.disk?.no || 0;
    t.year = c.year || 0;
    t.duration = m.format.duration || 0;
  } catch { /* keep filename-based fallback */ }
  const side = await readSidecar(f);
  if (side) {
    const meta = side.ytTitle ? splitMeta(side.ytTitle, side.ytAuthor || '') : side;
    t.title = meta.title || t.title;
    t.artist = meta.artist || t.artist;
    t.albumArtist = meta.artist || t.albumArtist;
    t.album = side.album || (t.album === 'Без альбома' ? 'YouTube' : t.album);
  }
  return t;
}

ipcMain.handle('pick-folder', async () => {
  const r = await dialog.showOpenDialog({ properties: ['openDirectory'] });
  return r.canceled ? null : r.filePaths[0];
});
ipcMain.handle('download-dir', async () => { await fs.mkdir(downloadDir(), { recursive: true }); return downloadDir(); });
ipcMain.handle('open-dir', (_, p) => shell.openPath(p));
const winOf = (e) => BrowserWindow.fromWebContents(e.sender);
ipcMain.on('win:min', (e) => winOf(e)?.minimize());
ipcMain.on('win:max', (e) => { const w = winOf(e); if (w) w.isMaximized() ? w.unmaximize() : w.maximize(); });
ipcMain.on('win:close', (e) => winOf(e)?.close());
ipcMain.handle('win:is-max', (e) => !!winOf(e)?.isMaximized());

ipcMain.handle('scan', async (e, roots) => {
  let total = 0;
  let buf = [];
  const flush = async () => {
    if (!buf.length) return;
    const tracks = await Promise.all(buf.map(readTrack));
    total += tracks.length;
    if (!e.sender.isDestroyed()) e.sender.send('scan:batch', tracks);
    buf = [];
  };
  for (const root of roots) {
    for await (const f of walk(root)) {
      buf.push(f);
      if (buf.length >= 24) await flush();
    }
  }
  await flush();
  return total;
});

ipcMain.handle('cover', async (_, f) => {
  try {
    const lib = await mm();
    const m = await lib.parseFile(f);
    const pic = lib.selectCover(m.common.picture);
    if (pic) return `data:${pic.format};base64,${Buffer.from(pic.data).toString('base64')}`;
  } catch { /* fall through to sidecar / folder art */ }
  const dir = path.dirname(f);
  const own = path.basename(f, path.extname(f));
  for (const n of [own, ...COVER_NAMES]) {
    for (const x of COVER_EXTS) {
      try {
        const buf = await fs.readFile(path.join(dir, n + x));
        return `data:${MIME[x]};base64,${buf.toString('base64')}`;
      } catch { /* try next */ }
    }
  }
  return null;
});

// ------------------------------------------------------------------ lyrics (LRCLIB, cached on disk)
const lyricsDir = () => path.join(app.getPath('userData'), 'lyrics');
const lyricsFile = (q) => path.join(lyricsDir(), crypto.createHash('sha1').update(`${q.artist}|${q.title}|${Math.round(q.duration)}`).digest('hex') + '.json');

// Manual lookup: free-text search, returns candidates (with their lyrics) for the user to pick from.
ipcMain.handle('lyrics:search', async (_, text) => {
  try {
    const r = await fetch(`https://lrclib.net/api/search?q=${encodeURIComponent(String(text).slice(0, 200))}`, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(10000) });
    if (!r.ok) return { error: r.status === 503 ? 'Сервер LRCLIB перегружен, попробуй ещё раз через минуту' : `LRCLIB ответил ${r.status}` };
    return (await r.json())
      .filter((x) => x.syncedLyrics || x.plainLyrics)
      .slice(0, 15)
      .map((x) => ({ artist: x.artistName, title: x.trackName, album: x.albumName, duration: x.duration, synced: x.syncedLyrics || null, plain: x.plainLyrics || null }));
  } catch (err) { return { error: String(err.message || err) }; }
});

// Remember the user's choice for this exact track (artist|title|duration). `scale` stretches the timestamps (sped-up / slowed versions).
ipcMain.handle('lyrics:save', async (_, { track, synced, plain, scale }) => {
  const out = { synced: synced || null, plain: plain || null, scale: Number.isFinite(scale) && scale > 0.2 && scale < 5 ? scale : 1 };
  await fs.mkdir(lyricsDir(), { recursive: true });
  await fs.writeFile(lyricsFile(track), JSON.stringify(out));
  return out;
});

ipcMain.handle('lyrics', async (_, q) => {
  const dir = lyricsDir();
  const file = lyricsFile(q);
  try { return JSON.parse(await fs.readFile(file, 'utf8')); } catch { /* not cached */ }

  const get = async (url) => {
    const r = await fetch(url, { headers: { 'User-Agent': UA }, signal: AbortSignal.timeout(10000) });
    return r.ok ? r.json() : null;
  };
  const known = q.artist && !q.artist.startsWith('Неизвестный');
  try {
    let hit = null;
    if (known) {
      const p = new URLSearchParams({ artist_name: q.artist, track_name: q.title, duration: Math.round(q.duration) });
      if (q.album && q.album !== 'Без альбома' && q.album !== 'YouTube') p.set('album_name', q.album);
      hit = await get(`https://lrclib.net/api/get?${p}`);
    }
    if (!hit) {
      const list = await get(`https://lrclib.net/api/search?q=${encodeURIComponent(known ? `${q.artist} ${q.title}` : q.title)}`);
      hit = (list || [])
        .filter((x) => x.syncedLyrics || x.plainLyrics)
        .sort((a, b) => (!!b.syncedLyrics - !!a.syncedLyrics) || Math.abs(a.duration - q.duration) - Math.abs(b.duration - q.duration))[0] || null;
    }
    if (!hit) return null;
    const out = { synced: hit.syncedLyrics || null, plain: hit.plainLyrics || null };
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(file, JSON.stringify(out));
    return out;
  } catch { return null; }
});

// ------------------------------------------------------------------ YouTube: search + yt-dlp download
ipcMain.handle('yt:search', async (_, query) => {
  try {
    const r = await require('yt-search')({ query });
    return r.videos.slice(0, 12).map((v) => ({
      id: v.videoId, title: v.title, author: v.author?.name || '', seconds: v.seconds, thumb: v.thumbnail || v.image,
    }));
  } catch (err) { return { error: String(err.message || err) }; }
});

async function ensureYtdlp(onStatus) {
  if (existsSync(ytdlpPath())) return ytdlpPath();
  onStatus?.('Скачиваю yt-dlp…');
  await fs.mkdir(binDir(), { recursive: true });
  const res = await fetch('https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe', { redirect: 'follow' });
  if (!res.ok) throw new Error(`yt-dlp: HTTP ${res.status}`);
  const tmp = ytdlpPath() + '.part';
  await pipeline(Readable.fromWeb(res.body), createWriteStream(tmp));
  await fs.rename(tmp, ytdlpPath());
  return ytdlpPath();
}

const run = (exe, args, onLine) => new Promise((resolve, reject) => {
  // ELECTRON_RUN_AS_NODE: yt-dlp needs a JavaScript runtime to solve YouTube's player challenges; we hand it this very
  // executable (see --js-runtimes below), so users do not have to install Node.
  const p = spawn(exe, args, { windowsHide: true, env: { ...process.env, PYTHONUTF8: '1', ELECTRON_RUN_AS_NODE: '1' } });
  let out = '', err = '';
  const feed = (chunk, isErr) => {
    const s = chunk.toString('utf8');
    if (isErr) err += s; else out += s;
    s.split(/\r?\n/).forEach((l) => l && onLine?.(l));
  };
  p.stdout.on('data', (c) => feed(c, false));
  p.stderr.on('data', (c) => feed(c, true));
  p.on('error', reject);
  p.on('close', (code) => (code === 0 ? resolve(out) : reject(new Error(err.trim().split('\n').pop() || `yt-dlp exited with ${code}`))));
});

ipcMain.handle('yt:status', async () => {
  if (!existsSync(ytdlpPath())) return { installed: false };
  try { return { installed: true, version: (await run(ytdlpPath(), ['--version'])).trim() }; } catch { return { installed: true, version: '?' }; }
});
ipcMain.handle('yt:update', async (e) => {
  const exe = await ensureYtdlp((s) => e.sender.send('yt:note', s));
  await run(exe, ['-U']);
  return (await run(exe, ['--version'])).trim();
});

const cleanTitle = (s) => s.replace(/\s*[(\[](official|lyrics?|lyric video|audio|video|music video|hd|hq|4k|visuali[sz]er|remaster(ed)?)[^)\]]*[)\]]/gi, '').trim();
// Split "Artist - Title" on the first dash that is NOT inside brackets, so "Song (Hardstyle - Sped Up)" stays whole.
function splitMeta(title, author) {
  const t = cleanTitle(title);
  let depth = 0, cut = -1;
  for (let i = 1; i < t.length - 1; i++) {
    const ch = t[i];
    if ('([{'.includes(ch)) depth++;
    else if (')]}'.includes(ch)) depth = Math.max(0, depth - 1);
    else if (!depth && '-–—'.includes(ch) && t[i - 1] === ' ' && t[i + 1] === ' ') { cut = i; break; }
  }
  const artist = (cut > 0 ? t.slice(0, cut) : author).replace(/\s*-\s*Topic$|VEVO$/i, '').trim();
  return { artist, title: cut > 0 ? t.slice(cut + 1).trim() : t };
}

// ---- optional YouTube login (cookies of a spare account), used when YouTube answers "confirm you're not a bot"
const cookieFile = () => path.join(app.getPath('userData'), 'cookies', 'youtube.txt');
const cookieInfo = async () => {
  try {
    const rows = (await fs.readFile(cookieFile(), 'utf8')).split(/\r?\n/).filter((l) => l && (!l.startsWith('#') || l.startsWith('#HttpOnly_')));
    return { present: rows.length > 0, count: rows.length };
  } catch { return { present: false, count: 0 }; }
};
ipcMain.handle('yt:cookies:status', cookieInfo);
ipcMain.handle('yt:cookies:import', async () => {
  const r = await dialog.showOpenDialog({ title: 'Файл cookies YouTube', properties: ['openFile'], filters: [{ name: 'Cookies (JSON / txt)', extensions: ['json', 'txt'] }, { name: 'Все файлы', extensions: ['*'] }] });
  if (r.canceled) return { ok: false, canceled: true };
  try {
    const { text, count } = require('./cookies').normalize(await fs.readFile(r.filePaths[0], 'utf8'));
    await fs.mkdir(path.dirname(cookieFile()), { recursive: true });
    await fs.writeFile(cookieFile(), text, { mode: 0o600 });
    return { ok: true, count };
  } catch (err) { return { ok: false, error: String(err.message || err) }; }
});
ipcMain.handle('yt:cookies:remove', async () => { await fs.rm(cookieFile(), { force: true }); return true; });

ipcMain.handle('yt:download', async (e, { id, title, author, useCookies }) => {
  if (!/^[\w-]{11}$/.test(id)) throw new Error('Некорректный ID видео');
  const send = (ch, v) => !e.sender.isDestroyed() && e.sender.send(ch, v);
  const exe = await ensureYtdlp((s) => send('yt:note', s));
  const dir = downloadDir();
  await fs.mkdir(dir, { recursive: true });
  const args = [
    '-f', 'bestaudio[ext=m4a]/bestaudio', '--no-playlist', '--newline', '--progress', '--no-warnings',
    '--js-runtimes', `node:${process.execPath}`,
    ...(useCookies !== false && existsSync(cookieFile()) ? ['--cookies', cookieFile()] : []),
    '--write-thumbnail', '--progress-template', 'download:%(progress._percent_str)s',
    '-o', path.join(dir, '%(id)s.%(ext)s'), `https://www.youtube.com/watch?v=${id}`,
  ];
  await run(exe, args, (l) => {
    const m = l.match(/^\s*([\d.]+)%/);
    if (m) send('yt:progress', { id, pct: parseFloat(m[1]) });
  });
  const file = (await fs.readdir(dir)).find((n) => n.startsWith(id + '.') && EXT.has(path.extname(n).toLowerCase()));
  if (!file) throw new Error('Файл не найден после загрузки');
  const full = path.join(dir, file);
  await fs.writeFile(path.join(dir, id + '.meta.json'), JSON.stringify({ ...splitMeta(title, author), ytTitle: title, ytAuthor: author, album: 'YouTube', source: `https://www.youtube.com/watch?v=${id}` }));
  return readTrack(full);
});

require('./update')({ app, ipcMain });

app.whenReady().then(() => {
  if (!gotLock) return;
  // The built-in file handler ignores Range (answers 200), which makes tracks unseekable — so serve 206s ourselves.
  protocol.handle('rmedia', async (req) => {
    const p = decodeURIComponent(new URL(req.url).pathname.slice(1));
    let size;
    try { size = (await fs.stat(p)).size; } catch { return new Response('not found', { status: 404 }); }
    const headers = {
      'content-type': AUDIO_MIME[path.extname(p).toLowerCase()] || 'application/octet-stream',
      'accept-ranges': 'bytes',
      'access-control-allow-origin': '*',
    };
    const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.get('range') || '');
    if (!m) return new Response(Readable.toWeb(createReadStream(p)), { status: 200, headers: { ...headers, 'content-length': String(size) } });
    let start = m[1] ? parseInt(m[1], 10) : Math.max(0, size - parseInt(m[2], 10));
    let end = m[1] && m[2] ? Math.min(parseInt(m[2], 10), size - 1) : size - 1;
    if (!m[1] && !m[2]) { start = 0; end = size - 1; }
    if (start > end || start >= size) return new Response(null, { status: 416, headers: { ...headers, 'content-range': `bytes */${size}` } });
    return new Response(Readable.toWeb(createReadStream(p, { start, end })), {
      status: 206,
      headers: { ...headers, 'content-range': `bytes ${start}-${end}/${size}`, 'content-length': String(end - start + 1) },
    });
  });
  createWindow();
  createTray();
});
app.on('window-all-closed', () => app.quit());

// handles for tests
module.exports = {
  get tray() { return tray; },
  get win() { return mainWin; },
  trayCommand,
  showWindow,
  menuLabels: () => trayMenuTemplate().map((i) => i.label || '---'),
};
