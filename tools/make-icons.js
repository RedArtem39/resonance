// Generates the app icon: build/icon.svg (source), build/icon.png, build/icon.ico (multi-size) and docs/icon.png.
// Run with:  npx electron tools/make-icons.js
//
// Concept: the same two shapes the app is built around — the scalloped "cookie" cover and the wavy progress ring —
// on a deep indigo tile, with a note cut into the cookie. The ring is drawn ~3/4 round, the way playback progress looks in the app.

const { app, BrowserWindow, nativeImage } = require('electron');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const C = 512; // canvas centre (1024 x 1024 viewBox)

const f = (n) => n.toFixed(2);

// Circle whose radius is modulated by `n` lobes: r = R * (1 - amp + amp * cos(n * theta))
function cookie(R, amp, n, rot = 0) {
  const pts = [];
  for (let i = 0; i < 360; i++) {
    const a = (i / 360) * Math.PI * 2;
    const r = R * (1 - amp + amp * Math.cos(n * (a - rot)));
    pts.push(`${f(C + r * Math.cos(a))},${f(C + r * Math.sin(a))}`);
  }
  return `M${pts.join('L')}Z`;
}

// Sine-modulated arc, fading its wave in/out near the ends so the caps stay round and calm
function waveArc(R, A, lobes, a0, span) {
  const pts = [];
  const steps = 420;
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const a = a0 + span * t;
    const env = Math.min(1, t * 14, (1 - t) * 14 + 0.35);
    const r = R + Math.sin((a - a0) * lobes) * A * env;
    pts.push([C + r * Math.cos(a), C + r * Math.sin(a)]);
  }
  return { d: 'M' + pts.map((p) => `${f(p[0])},${f(p[1])}`).join('L'), end: pts[pts.length - 1] };
}

const START = -Math.PI / 2 - 0.12;
const ring = waveArc(372, 20, 17, START, Math.PI * 2 * 0.76);

const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#170f3d"/>
      <stop offset="0.55" stop-color="#34196f"/>
      <stop offset="1" stop-color="#7c2a8f"/>
    </linearGradient>
    <radialGradient id="warm" cx="0.9" cy="0.95" r="0.7">
      <stop offset="0" stop-color="#ff7a59" stop-opacity="0.45"/>
      <stop offset="1" stop-color="#ff7a59" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="cool" cx="0.18" cy="0.12" r="0.65">
      <stop offset="0" stop-color="#8f7bff" stop-opacity="0.40"/>
      <stop offset="1" stop-color="#8f7bff" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="arc" gradientUnits="userSpaceOnUse" x1="140" y1="900" x2="880" y2="160">
      <stop offset="0" stop-color="#ffc79a"/>
      <stop offset="0.5" stop-color="#ff7aa8"/>
      <stop offset="1" stop-color="#b995ff"/>
    </linearGradient>
    <linearGradient id="cookie" x1="0.15" y1="0.05" x2="0.85" y2="0.95">
      <stop offset="0" stop-color="#f6efff"/>
      <stop offset="0.6" stop-color="#cdb9ff"/>
      <stop offset="1" stop-color="#a98cff"/>
    </linearGradient>
    <radialGradient id="shine" cx="0.32" cy="0.25" r="0.6">
      <stop offset="0" stop-color="#ffffff" stop-opacity="0.65"/>
      <stop offset="1" stop-color="#ffffff" stop-opacity="0"/>
    </radialGradient>
    <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
      <feGaussianBlur stdDeviation="14" result="b"/>
      <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
    <filter id="drop" x="-20%" y="-20%" width="140%" height="150%">
      <feDropShadow dx="0" dy="22" stdDeviation="22" flood-color="#0d0524" flood-opacity="0.55"/>
    </filter>
    <clipPath id="tile"><rect x="40" y="40" width="944" height="944" rx="224"/></clipPath>
  </defs>

  <g clip-path="url(#tile)">
    <rect x="40" y="40" width="944" height="944" fill="url(#bg)"/>
    <rect x="40" y="40" width="944" height="944" fill="url(#cool)"/>
    <rect x="40" y="40" width="944" height="944" fill="url(#warm)"/>
  </g>

  <!-- progress track + wavy progress arc -->
  <circle cx="${C}" cy="${C}" r="372" fill="none" stroke="#ffffff" stroke-opacity="0.09" stroke-width="30"/>
  <path d="${ring.d}" fill="none" stroke="url(#arc)" stroke-width="38" stroke-linecap="round" stroke-linejoin="round" filter="url(#glow)"/>
  <circle cx="${f(ring.end[0])}" cy="${f(ring.end[1])}" r="40" fill="#fff1e8" filter="url(#glow)"/>

  <!-- cookie cover -->
  <path d="${cookie(268, 0.075, 9, -Math.PI / 2)}" fill="url(#cookie)" filter="url(#drop)"/>
  <path d="${cookie(268, 0.075, 9, -Math.PI / 2)}" fill="url(#shine)"/>

  <!-- note -->
  <g fill="#2a1668" transform="translate(0 -6)">
    <ellipse cx="462" cy="612" rx="72" ry="54" transform="rotate(-24 462 612)"/>
    <rect x="520" y="388" width="32" height="236" rx="16"/>
    <path d="M536 392 C 548 466 640 470 660 568 C 664 592 654 612 640 624 C 650 548 586 524 536 512 Z"/>
  </g>
</svg>
`;

function writeIco(file, pngs) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0); header.writeUInt16LE(1, 2); header.writeUInt16LE(pngs.length, 4);
  const entries = [];
  let offset = 6 + pngs.length * 16;
  for (const { size, data } of pngs) {
    const e = Buffer.alloc(16);
    e.writeUInt8(size >= 256 ? 0 : size, 0); e.writeUInt8(size >= 256 ? 0 : size, 1);
    e.writeUInt8(0, 2); e.writeUInt8(0, 3);
    e.writeUInt16LE(1, 4); e.writeUInt16LE(32, 6);
    e.writeUInt32LE(data.length, 8); e.writeUInt32LE(offset, 12);
    entries.push(e); offset += data.length;
  }
  fs.writeFileSync(file, Buffer.concat([header, ...entries, ...pngs.map((p) => p.data)]));
}

app.whenReady().then(async () => {
  fs.mkdirSync(path.join(ROOT, 'build'), { recursive: true });
  fs.mkdirSync(path.join(ROOT, 'docs'), { recursive: true });
  fs.writeFileSync(path.join(ROOT, 'build', 'icon.svg'), svg);

  const win = new BrowserWindow({ show: false, width: 1024, height: 1024, useContentSize: true, transparent: true, frame: false, webPreferences: { offscreen: false } });
  const html = `<!doctype html><body style="margin:0;background:transparent;overflow:hidden">${svg}</body>`;
  await win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
  await new Promise((r) => setTimeout(r, 500));
  let big = await win.webContents.capturePage({ x: 0, y: 0, width: 1024, height: 1024 });
  if (big.getSize().width !== 1024) big = big.resize({ width: 1024, height: 1024, quality: 'best' });

  const at = (s) => big.resize({ width: s, height: s, quality: 'best' }).toPNG();
  fs.writeFileSync(path.join(ROOT, 'build', 'icon.png'), at(512));
  fs.writeFileSync(path.join(ROOT, 'docs', 'icon.png'), at(256));
  writeIco(path.join(ROOT, 'build', 'icon.ico'), [16, 24, 32, 48, 64, 128, 256].map((size) => ({ size, data: at(size) })));
  console.log('icons written:', fs.readdirSync(path.join(ROOT, 'build')).join(', '));
  app.quit();
});
