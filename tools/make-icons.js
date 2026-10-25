// Generates the app icon: build/icon.svg (source), build/icon.png, build/icon.ico (multi-size) and docs/icon.png.
// Run with:  npx electron tools/make-icons.js
//
// Concept: the two shapes the app is built around — the scalloped "cookie" cover and the wavy progress ring — as flat colour blocks.
// The ring is drawn ~3/4 round, the way playback progress looks in the app.

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

// Rounded scalloped disc: a centre circle plus `n` lobe circles — soft bumps, unlike the pointy cosine silhouette
function scallops(n, dist, r, core) {
  let out = `<circle cx="${C}" cy="${C}" r="${core}"/>`;
  for (let i = 0; i < n; i++) {
    const a = -Math.PI / 2 + (i / n) * Math.PI * 2;
    out += `<circle cx="${f(C + dist * Math.cos(a))}" cy="${f(C + dist * Math.sin(a))}" r="${r}"/>`;
  }
  return out;
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

// Flat on purpose: no gradients, glow or drop shadows. Four solid colours, bold geometry.
const P = {
  tile: '#e4572e',   // vermilion
  track: '#c4421d',  // tone-on-tone ring track
  wave: '#f7ecdc',   // warm cream
  cookie: '#1d1b1a', // ink
  note: '#f7ecdc',
};

const START = -Math.PI / 2 - 0.1;
const ring = waveArc(366, 24, 12, START, Math.PI * 2 * 0.74);

const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">
  <rect x="40" y="40" width="944" height="944" rx="224" fill="${P.tile}"/>

  <!-- progress track + wavy progress arc -->
  <circle cx="${C}" cy="${C}" r="366" fill="none" stroke="${P.track}" stroke-width="44"/>
  <path d="${ring.d}" fill="none" stroke="${P.wave}" stroke-width="44" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="${f(ring.end[0])}" cy="${f(ring.end[1])}" r="46" fill="${P.wave}"/>

  <!-- cookie cover with a cut-out note -->
  <path d="${cookie(274, 0.055, 9, -Math.PI / 2)}" fill="${P.cookie}"/>
  <g fill="${P.note}" transform="translate(0 -6)">
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
