// Updates and rollback via GitHub Releases. Nothing is installed silently: the installer of the chosen version is downloaded,
// its SHA-256 is checked against the digest GitHub reports for the asset, and then it is started (it shows its own window).
// Rolling back is the same thing with an older release — the installer simply overwrites the program files; user data is kept.

const path = require('path');
const os = require('os');
const fs = require('fs/promises');
const { createWriteStream } = require('fs');
const crypto = require('crypto');
const { spawn } = require('child_process');
const { Readable } = require('stream');

const REPO = 'RedArtem39/resonance'; // GitHub "owner/name" whose Releases carry Resonance-Setup-<version>.exe
const UA = 'Resonance-updater';

const norm = (v) => String(v).replace(/^v/i, '').split('-')[0].split('.').map((n) => parseInt(n, 10) || 0);
function cmp(a, b) { const x = norm(a), y = norm(b); for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0); return 0; }

async function listReleases() {
  const res = await fetch(`${process.env.RESONANCE_UPDATE_API || 'https://api.github.com'}/repos/${REPO}/releases?per_page=20`, {
    headers: { 'User-Agent': UA, Accept: 'application/vnd.github+json' }, signal: AbortSignal.timeout(12000),
  });
  if (res.status === 404) return [];
  if (!res.ok) throw new Error(`GitHub answered ${res.status}`);
  return (await res.json())
    .filter((r) => !r.draft)
    .map((r) => {
      const a = (r.assets || []).find((x) => /^Resonance-Setup-.*\.exe$/i.test(x.name));
      return {
        version: String(r.tag_name).replace(/^v/i, ''), name: r.name || r.tag_name, date: r.published_at, prerelease: !!r.prerelease,
        notes: (r.body || '').trim().slice(0, 600),
        asset: a ? { name: a.name, url: a.browser_download_url, size: a.size, digest: a.digest || null } : null,
      };
    })
    .filter((r) => r.asset)
    .sort((a, b) => cmp(b.version, a.version));
}

module.exports = function register({ app, ipcMain }) {
  // updating only makes sense for an installed copy, not for `npm start`
  const installed = app.isPackaged && path.basename(process.execPath).toLowerCase() === 'resonance.exe';

  ipcMain.handle('app:info', () => ({ version: app.getVersion(), installed }));

  ipcMain.handle('upd:check', async () => {
    const current = app.getVersion();
    try {
      const releases = await listReleases();
      const stable = releases.filter((r) => !r.prerelease);
      const latest = stable[0] || null;
      return { ok: true, installed, current, releases, latest: latest?.version ?? null, hasUpdate: !!latest && cmp(latest.version, current) > 0 };
    } catch (err) {
      return { ok: false, installed, current, error: String(err.message || err) };
    }
  });

  ipcMain.handle('upd:install', async (e, version) => {
    if (!installed) throw new Error('Updates work only in the installed app');
    const rel = (await listReleases()).find((r) => r.version === version);
    if (!rel) throw new Error('That release is no longer available');
    const url = new URL(rel.asset.url);
    if (url.protocol !== 'https:' || url.hostname !== 'github.com') throw new Error('Unexpected download address');

    const dir = path.join(os.tmpdir(), 'resonance-update');
    await fs.mkdir(dir, { recursive: true });
    const file = path.join(dir, rel.asset.name.replace(/[^\w.\-]/g, '_'));

    const res = await fetch(url, { redirect: 'follow', headers: { 'User-Agent': UA } });
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    const total = Number(res.headers.get('content-length')) || rel.asset.size || 0;
    const hash = crypto.createHash('sha256');
    let got = 0, last = 0;
    await new Promise((resolve, reject) => {
      const body = Readable.fromWeb(res.body);
      const out = createWriteStream(file);
      body.on('data', (c) => {
        hash.update(c); got += c.length;
        const now = Date.now();
        if (total && now - last > 100 && !e.sender.isDestroyed()) { last = now; e.sender.send('upd:progress', Math.min(99, (got / total) * 100)); }
      });
      body.on('error', reject); out.on('error', reject); out.on('close', resolve);
      body.pipe(out);
    });

    const want = rel.asset.digest?.startsWith('sha256:') ? rel.asset.digest.slice(7).toLowerCase() : null;
    if (want && hash.digest('hex') !== want) { await fs.unlink(file).catch(() => {}); throw new Error('Checksum mismatch — the download was discarded'); }

    spawn(file, [], { detached: true, stdio: 'ignore' }).unref();
    // Resonance deliberately keeps running: the installer takes several seconds to open, and it stops this copy itself when you press Update.
    return { ok: true, verified: !!want };
  });
};
