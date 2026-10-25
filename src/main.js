const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs/promises');
const { pathToFileURL } = require('url');

const EXT = new Set(['.mp3', '.flac', '.ogg', '.opus', '.m4a', '.aac', '.wav', '.wma']);
const COVER_NAMES = ['cover', 'folder', 'front', 'album'];
const COVER_EXTS = ['.jpg', '.jpeg', '.png', '.webp'];
const MIME = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.webp': 'image/webp' };

let mmPromise;
const mm = () => (mmPromise ??= import('music-metadata'));

function createWindow() {
  const win = new BrowserWindow({
    width: 1320,
    height: 820,
    minWidth: 980,
    minHeight: 660,
    backgroundColor: '#14121a',
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: '#00000000', symbolColor: '#e6e0ee', height: 44 },
    webPreferences: { preload: path.join(__dirname, 'preload.js') },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'index.html'));
}

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

async function readTrack(f) {
  const base = path.basename(f, path.extname(f));
  const t = {
    path: f, url: pathToFileURL(f).href, title: base,
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
  return t;
}

ipcMain.handle('pick-folder', async () => {
  const r = await dialog.showOpenDialog({ properties: ['openDirectory'] });
  return r.canceled ? null : r.filePaths[0];
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
  } catch { /* fall through to folder art */ }
  const dir = path.dirname(f);
  for (const n of COVER_NAMES) {
    for (const x of COVER_EXTS) {
      try {
        const buf = await fs.readFile(path.join(dir, n + x));
        return `data:${MIME[x]};base64,${buf.toString('base64')}`;
      } catch { /* try next */ }
    }
  }
  return null;
});

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
