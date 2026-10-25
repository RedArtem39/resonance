const { app, BrowserWindow, ipcMain, dialog, protocol, net, shell } = require('electron');
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
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: '#00000000', symbolColor: '#e6e0ee', height: 44 },
    webPreferences: { preload: path.join(__dirname, 'preload.js') },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'index.html'));
}

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
ipcMain.on('title-symbols', (e, color) => {
  BrowserWindow.fromWebContents(e.sender)?.setTitleBarOverlay({ color: '#00000000', symbolColor: color, height: 44 });
});

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
ipcMain.handle('lyrics', async (_, q) => {
  const dir = path.join(app.getPath('userData'), 'lyrics');
  const file = path.join(dir, crypto.createHash('sha1').update(`${q.artist}|${q.title}|${Math.round(q.duration)}`).digest('hex') + '.json');
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
  const p = spawn(exe, args, { windowsHide: true, env: { ...process.env, PYTHONUTF8: '1' } });
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

ipcMain.handle('yt:download', async (e, { id, title, author }) => {
  if (!/^[\w-]{11}$/.test(id)) throw new Error('Некорректный ID видео');
  const send = (ch, v) => !e.sender.isDestroyed() && e.sender.send(ch, v);
  const exe = await ensureYtdlp((s) => send('yt:note', s));
  const dir = downloadDir();
  await fs.mkdir(dir, { recursive: true });
  const args = [
    '-f', 'bestaudio[ext=m4a]/bestaudio', '--no-playlist', '--newline', '--progress', '--no-warnings',
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

app.whenReady().then(() => {
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
});
app.on('window-all-closed', () => app.quit());
