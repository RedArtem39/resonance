// Uninstaller. Runs when the installed app is started as `Resonance.exe --uninstall` (registered in "Apps & features").
// Flags: --silent (no window)  --purge (also delete settings and cache; downloaded music is never touched)

const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const os = require('os');
const fs = require('fs/promises');
const { existsSync, writeFileSync } = require('fs');
const { execFile, spawn } = require('child_process');

// don't hold the real profile (%APPDATA%\Resonance) open — "purge" has to be able to delete it
app.setPath('userData', path.join(os.tmpdir(), 'resonance-uninstall'));

const SILENT = process.argv.includes('--silent');
const PURGE = process.argv.includes('--purge');
const REG_KEY = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Resonance';
const exe = process.execPath;
const dir = path.dirname(exe);

// The folder removal below is recursive — only ever do it for a real installed copy, never for a dev run from node_modules.
const looksInstalled = app.isPackaged && path.basename(exe).toLowerCase() === 'resonance.exe' && existsSync(path.join(dir, 'resources', 'app.asar'));

let removed = false;
const run = (cmd, a) => new Promise((resolve) => execFile(cmd, a, { windowsHide: true }, () => resolve()));

async function removeShortcuts() {
  const spots = [
    path.join(app.getPath('desktop'), 'Resonance.lnk'),
    path.join(app.getPath('appData'), 'Microsoft', 'Windows', 'Start Menu', 'Programs', 'Resonance.lnk'),
  ];
  for (const lnk of spots) {
    try {
      if (shell.readShortcutLink(lnk).target.toLowerCase() === exe.toLowerCase()) await fs.unlink(lnk); // only our own shortcuts
    } catch { /* not there */ }
  }
}

async function removeAll(purge) {
  await removeShortcuts();
  await run('reg', ['delete', REG_KEY, '/f']);
  if (purge) await fs.rm(path.join(app.getPath('appData'), 'Resonance'), { recursive: true, force: true }).catch(() => {});
  removed = true;
}

// The running exe can't delete its own folder: hand that to a detached script that waits for us to exit and retries
// (antivirus or the indexer can hold a handle for a moment, which used to leave an empty folder behind).
function scheduleDelete() {
  if (!removed || !looksInstalled) return;
  const script = path.join(os.tmpdir(), `resonance-uninstall-${process.pid}.cmd`);
  const body = [
    '@echo off',
    'ping 127.0.0.1 -n 3 >nul',
    'for /l %%i in (1,1,20) do (',
    `  rmdir /s /q "${dir}" 2>nul`,
    `  if not exist "${dir}" goto done`,
    '  ping 127.0.0.1 -n 2 >nul',
    ')',
    ':done',
    'del "%~f0"',
  ].join('\r\n');
  try { writeFileSync(script, body); } catch { return; }
  spawn('cmd.exe', ['/c', script], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
}

ipcMain.handle('un:info', () => ({ dir, locale: app.getLocale(), ok: looksInstalled }));
ipcMain.handle('un:run', async (_, purge) => { await removeAll(!!purge); return true; });
ipcMain.on('un:close', () => app.quit());
app.on('will-quit', scheduleDelete);

app.whenReady().then(async () => {
  if (!looksInstalled) { app.exit(2); return; }
  if (SILENT) { await removeAll(PURGE); app.quit(); return; }
  const win = new BrowserWindow({
    width: 640, height: 420, frame: false, resizable: false, maximizable: false, fullscreenable: false, backgroundColor: '#171413',
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'uninstall-preload.js') },
  });
  win.setMenuBarVisibility(false);
  win.loadFile(path.join(__dirname, 'uninstall.html'));
});
app.on('window-all-closed', () => app.quit());
