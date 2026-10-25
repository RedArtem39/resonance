// Resonance Setup — a custom installer written in Electron.
//
// It does not unpack a second copy of the app: the installer *is* an Electron runtime, so installing means copying its own runtime
// files next to Resonance's app.asar (shipped in resources/payload) and renaming the executable. Then it creates shortcuts and the
// "Apps & features" registry entry whose uninstall command is `Resonance.exe --uninstall` (handled inside the app, see src/uninstall.js).
//
// Command line (for scripting/tests):  --silent  --dir=<path>  --desktop  --startmenu  --launch  --hidden  --log=<file>

const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const os = require('os');
const fs = require('fs/promises');
const { createReadStream, createWriteStream, existsSync, appendFileSync } = require('fs');
const { spawn, execFile } = require('child_process');

app.setPath('userData', path.join(os.tmpdir(), 'resonance-setup')); // never touch the app's own profile (%APPDATA%\Resonance)

const args = process.argv.slice(1);
const flag = (n) => args.includes(`--${n}`);
const opt = (n) => { const a = args.find((x) => x.startsWith(`--${n}=`)); return a ? a.slice(n.length + 3) : null; };

const APP_USER_MODEL_ID = 'io.github.redartem39.resonance';
const REG_KEY = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Resonance';
const DEFAULT_DIR = path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'Programs', 'Resonance');
const PAYLOAD = app.isPackaged ? path.join(process.resourcesPath, 'payload') : path.join(__dirname, 'payload');
const VERSION = require('./package.json').version;
const LOG = opt('log');
const log = (m) => { if (LOG) try { appendFileSync(LOG, `${new Date().toISOString()} ${m}\n`); } catch { /* ignore */ } };

const run = (cmd, a) => new Promise((resolve, reject) =>
  execFile(cmd, a, { windowsHide: true }, (e, out, err) => (e ? reject(new Error((err || e.message).trim())) : resolve(out))));

// ------------------------------------------------------------------ existing installation
async function findExisting() {
  try {
    const out = await run('reg', ['query', REG_KEY, '/v', 'InstallLocation']);
    const dir = /InstallLocation\s+REG_SZ\s+(.+)/.exec(out)?.[1]?.trim();
    if (!dir || !existsSync(path.join(dir, 'Resonance.exe'))) return null;
    let version = '';
    try { version = /DisplayVersion\s+REG_SZ\s+(.+)/.exec(await run('reg', ['query', REG_KEY, '/v', 'DisplayVersion']))?.[1]?.trim() || ''; } catch { /* optional */ }
    return { dir, version };
  } catch { return null; }
}

// ------------------------------------------------------------------ install
async function listFiles(root, rel = '') {
  const out = [];
  for (const e of await fs.readdir(path.join(root, rel), { withFileTypes: true })) {
    const r = path.join(rel, e.name);
    if (e.isDirectory()) out.push(...(await listFiles(root, r)));
    else out.push({ rel: r, size: (await fs.stat(path.join(root, r))).size });
  }
  return out;
}

// the installer's own app and helper files must not end up in the installed app
const skip = (rel) => {
  const r = rel.toLowerCase();
  return r === path.join('resources', 'app.asar') || r.startsWith(path.join('resources', 'payload')) ||
    r === path.join('resources', 'elevate.exe') || r.endsWith('.log');
};

function copyWithProgress(src, dst, onBytes) {
  return new Promise((resolve, reject) => {
    const rs = createReadStream(src, { highWaterMark: 1 << 20 });
    const ws = createWriteStream(dst);
    rs.on('data', (c) => onBytes(c.length));
    rs.on('error', reject);
    ws.on('error', reject);
    ws.on('close', resolve);
    rs.pipe(ws);
  });
}

async function stopInstalled(exe) {
  const q = exe.replace(/'/g, "''");
  await run('powershell', ['-NoProfile', '-Command',
    `Get-Process -Name Resonance -ErrorAction SilentlyContinue | Where-Object { $_.Path -ieq '${q}' } | Stop-Process -Force`]).catch(() => {});
  await new Promise((r) => setTimeout(r, 500));
}

async function install(o, on) {
  // Electron's fs treats any *.asar path as an archive (so writing resources/app.asar fails) — switch that layer off while installing
  process.noAsar = true;
  try { return await doInstall(o, on); } finally { process.noAsar = false; }
}

async function doInstall(o, on) {
  const dir = path.resolve(o.dir);
  const exe = path.join(dir, 'Resonance.exe');
  const runtime = path.dirname(process.execPath);
  log(`install -> ${dir}`);

  on(0, 'prepare');
  if (existsSync(exe)) await stopInstalled(exe);
  await fs.mkdir(path.join(dir, 'resources'), { recursive: true });

  const files = (await listFiles(runtime)).filter((f) => !skip(f.rel));
  const asarSize = (await fs.stat(path.join(PAYLOAD, 'app.bin'))).size;
  const total = files.reduce((s, f) => s + f.size, 0) + asarSize || 1;
  let done = 0, last = 0, current = '';
  const tick = (n) => { done += n; const now = Date.now(); if (now - last > 40) { last = now; on(Math.min(94, (done / total) * 94), current); } };

  for (const f of files) {
    const isExe = path.join(runtime, f.rel).toLowerCase() === process.execPath.toLowerCase();
    const dest = path.join(dir, isExe ? 'Resonance.exe' : f.rel);
    current = isExe ? 'Resonance.exe' : f.rel;
    await fs.mkdir(path.dirname(dest), { recursive: true });
    try { await copyWithProgress(path.join(runtime, f.rel), dest, tick); }
    catch (e) {
      if (e.code === 'EBUSY' || e.code === 'EPERM' || e.code === 'EACCES') throw new Error(`BUSY:${current}`);
      throw e;
    }
  }

  current = 'resources/app.asar';
  await copyWithProgress(path.join(PAYLOAD, 'app.bin'), path.join(dir, 'resources', 'app.asar'), tick);
  for (const extra of ['LICENSE.txt']) await fs.copyFile(path.join(PAYLOAD, extra), path.join(dir, extra)).catch(() => {});
  await fs.copyFile(path.join(PAYLOAD, 'icon.ico'), path.join(dir, 'resources', 'icon.ico')).catch(() => {});

  on(96, 'shortcuts');
  const link = (file) => shell.writeShortcutLink(file, 'create', {
    target: exe, cwd: dir, icon: exe, iconIndex: 0, description: 'Resonance', appUserModelId: APP_USER_MODEL_ID,
  });
  if (o.desktop) link(path.join(app.getPath('desktop'), 'Resonance.lnk'));
  if (o.startmenu) {
    const sm = path.join(app.getPath('appData'), 'Microsoft', 'Windows', 'Start Menu', 'Programs');
    await fs.mkdir(sm, { recursive: true });
    link(path.join(sm, 'Resonance.lnk'));
  }

  on(98, 'register');
  const kb = Math.round(total / 1024);
  const values = [
    ['DisplayName', 'REG_SZ', 'Resonance'], ['DisplayVersion', 'REG_SZ', VERSION], ['Publisher', 'REG_SZ', 'RedArtem39'],
    ['InstallLocation', 'REG_SZ', dir], ['DisplayIcon', 'REG_SZ', `${exe},0`],
    ['UninstallString', 'REG_SZ', `"${exe}" --uninstall`], ['QuietUninstallString', 'REG_SZ', `"${exe}" --uninstall --silent`],
    ['NoModify', 'REG_DWORD', 1], ['NoRepair', 'REG_DWORD', 1], ['EstimatedSize', 'REG_DWORD', kb],
  ];
  for (const [name, type, value] of values) await run('reg', ['add', REG_KEY, '/v', name, '/t', type, '/d', String(value), '/f']);

  on(100, 'done');
  log('install ok');
  return { dir, exe };
}

const launch = (exe) => { spawn(exe, [], { detached: true, stdio: 'ignore', cwd: path.dirname(exe) }).unref(); };

// ------------------------------------------------------------------ window + IPC
let win;
function createWindow() {
  win = new BrowserWindow({
    width: 880, height: 560, frame: false, resizable: false, maximizable: false, fullscreenable: false,
    backgroundColor: '#171413', show: false,
    icon: path.join(__dirname, 'ui', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js') },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'ui', 'index.html'));
  win.once('ready-to-show', () => {
    if (flag('hidden')) { win.setOpacity(0); win.showInactive(); } else win.show(); // --hidden: for automated screenshots
  });
}

ipcMain.handle('setup:info', async () => {
  const existing = await findExisting();
  let license = '';
  try { license = await fs.readFile(path.join(PAYLOAD, 'LICENSE.txt'), 'utf8'); } catch { /* ignore */ }
  return { version: VERSION, defaultDir: existing?.dir || DEFAULT_DIR, existing, license, locale: app.getLocale() };
});
ipcMain.handle('setup:browse', async (_, current) => {
  const r = await dialog.showOpenDialog(win, { defaultPath: current, properties: ['openDirectory', 'createDirectory'] });
  if (r.canceled) return null;
  const p = r.filePaths[0];
  return path.basename(p).toLowerCase() === 'resonance' ? p : path.join(p, 'Resonance'); // keep the app in its own folder
});
ipcMain.handle('setup:install', async (e, o) => {
  try {
    const res = await install(o, (pct, label) => !e.sender.isDestroyed() && e.sender.send('setup:progress', { pct, label }));
    return { ok: true, ...res };
  } catch (err) {
    log(`install failed: ${err.message}`);
    return { ok: false, error: String(err.message || err) };
  }
});
ipcMain.handle('setup:launch', (_, exe) => launch(exe));
ipcMain.on('setup:close', () => app.quit());
ipcMain.on('setup:minimize', () => win?.minimize());

app.whenReady().then(async () => {
  if (flag('silent')) {
    try {
      const existing = await findExisting();
      const res = await install({ dir: opt('dir') || existing?.dir || DEFAULT_DIR, desktop: flag('desktop'), startmenu: flag('startmenu') }, () => {});
      if (flag('launch')) launch(res.exe);
      app.exit(0);
    } catch (err) { log(`silent install failed: ${err.message}`); app.exit(1); }
    return;
  }
  createWindow();
});
app.on('window-all-closed', () => app.quit());
