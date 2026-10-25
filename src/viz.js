'use strict';
// Visualizer behind the transport controls. One button next to the sleep timer cycles: off -> bars -> solid -> off.
//   bars  : rounded capsules with falling peak caps
//   solid : one continuous silhouette (a soft wave with an echo layer behind it and a bright outline)
//
// Smoothness: everything is smoothed in TIME (exponential, frame-rate independent, with separate attack/release), then in SPACE
// (neighbouring bands), and the auto-gain moves slowly, so a single loud frame can no longer make the shape jump.

const vizCv = $('#viz');
const vizBox = $('.dock-low');
const vizBtn = $('#vizBtn');
const VIZ_BARS = 26, VIZ_WAVE = 72;
const vizBins = new Uint8Array(analyser.frequencyBinCount);
const edgesFor = (n) => Array.from({ length: n + 1 }, (_, i) => 45 * (15000 / 45) ** (i / n));
const barEdges = edgesFor(VIZ_BARS), waveEdges = edgesFor(VIZ_WAVE);

const barVal = new Float32Array(VIZ_BARS), barPeak = new Float32Array(VIZ_BARS);
const waveVal = new Float32Array(VIZ_WAVE), waveEcho = new Float32Array(VIZ_WAVE);
const rawBar = new Float32Array(VIZ_BARS), rawWave = new Float32Array(VIZ_WAVE);
let vizMode = 'off', vizBass = 0, vizMax = 0.5, vizLast = 0;

const VIZ_TITLES = {
  off: 'Визуализатор: выключен (нажми — полосы)',
  bars: 'Визуализатор: полосы (нажми — сплошной)',
  wave: 'Визуализатор: сплошной (нажми — выключить)',
};
function setVizMode(mode) {
  vizMode = mode;
  store.set('vizMode', mode);
  vizBox.classList.toggle('viz-on', mode !== 'off');
  vizBtn.classList.toggle('on', mode !== 'off');
  vizBtn.innerHTML = svg(mode === 'wave' ? 'wave' : 'eq');
  vizBtn.title = VIZ_TITLES[mode];
}
vizBtn.onclick = () => setVizMode(vizMode === 'off' ? 'bars' : vizMode === 'bars' ? 'wave' : 'off');
setVizMode(store.get('vizMode', store.get('viz', false) ? 'bars' : 'off')); // older versions stored a plain on/off flag

// raw 0..1 level per band; narrow (bass) bands interpolate between FFT bins instead of stair-stepping
function bandLevels(edges, n, hz, out) {
  for (let i = 0; i < n; i++) {
    const f0 = edges[i], f1 = edges[i + 1], lo = f0 / hz, hi = f1 / hz;
    let v;
    if (hi - lo < 1.5) {
      const fb = (f0 + f1) / 2 / hz, i0 = Math.floor(fb), fr = fb - i0;
      v = ((vizBins[i0] ?? 0) * (1 - fr) + (vizBins[i0 + 1] ?? 0) * fr) / 255;
    } else {
      let m = 0, s = 0, c = 0;
      for (let k = Math.max(1, Math.floor(lo)); k < Math.ceil(hi); k++) { m = Math.max(m, vizBins[k]); s += vizBins[k]; c++; }
      v = (m * 0.6 + (s / (c || 1)) * 0.4) / 255;
    }
    out[i] = v * (0.8 + (i / n) * 0.6); // spectra fall off with frequency; lift the top end a little
  }
}

// frame-rate independent smoothing: separate time constants for rising and falling
function follow(arr, raw, dt, gain, up, down) {
  for (let i = 0; i < arr.length; i++) {
    const target = Math.min(1, (raw[i] / gain) ** 1.7);
    arr[i] += (target - arr[i]) * (1 - Math.exp(-dt / (target > arr[i] ? up : down)));
  }
}
// weighted neighbour blur so one bin can't spike alone
function blur(src, k) {
  const out = new Float32Array(src.length), r = (k.length - 1) / 2, sum = k.reduce((a, b) => a + b, 0);
  for (let i = 0; i < src.length; i++) {
    let s = 0;
    for (let j = -r; j <= r; j++) s += src[Math.min(src.length - 1, Math.max(0, i + j))] * k[j + r];
    out[i] = s / sum;
  }
  return out;
}

function drawViz(now) {
  if (vizMode === 'off') { vizLast = 0; return; }
  const dt = vizLast ? Math.min(0.1, (now - vizLast) / 1000) : 0.016;
  vizLast = now;
  const w = vizCv.clientWidth, h = vizCv.clientHeight;
  const g = fit(vizCv, w, h);
  analyser.getByteFrequencyData(vizBins);
  const hz = AC.sampleRate / analyser.fftSize;

  const barMode = vizMode === 'bars';
  const edges = barMode ? barEdges : waveEdges, n = barMode ? VIZ_BARS : VIZ_WAVE, raw = barMode ? rawBar : rawWave;
  bandLevels(edges, n, hz, raw);
  let frameMax = 0;
  for (let i = 0; i < n; i++) frameMax = Math.max(frameMax, raw[i]);
  // auto-gain: quick to follow a louder passage (150 ms), very slow to relax (6 s) — no pumping
  vizMax += (frameMax - vizMax) * (1 - Math.exp(-dt / (frameMax > vizMax ? 0.15 : 6)));
  const gain = Math.max(0.3, vizMax);

  let bass = 0;
  for (let k = 1; k <= 6; k++) bass += vizBins[k];
  bass = bass / 6 / 255;
  vizBass += (bass - vizBass) * (1 - Math.exp(-dt / (bass > vizBass ? 0.06 : 0.25)));

  const primary = cssColor('--primary'), tertiary = cssColor('--tertiary');
  const grad = g.createLinearGradient(0, 0, w, 0);
  grad.addColorStop(0, primary); grad.addColorStop(1, tertiary);

  // bass glow under the controls
  const glow = g.createRadialGradient(w / 2, h, 0, w / 2, h, w * 0.75);
  glow.addColorStop(0, primary); glow.addColorStop(1, 'transparent');
  g.globalAlpha = 0.05 + vizBass ** 2 * 0.45;
  g.fillStyle = glow; g.fillRect(0, 0, w, h);

  if (barMode) {
    follow(barVal, raw, dt, gain, 0.05, 0.2);
    const vals = blur(barVal, [1, 2, 1]);
    const slot = w / VIZ_BARS;
    for (let i = 0; i < VIZ_BARS; i++) {
      barPeak[i] = Math.max(vals[i], barPeak[i] - dt * 0.33); // caps fall at a steady speed
      const v = vals[i], bw = slot * (0.3 + v * 0.5), x = slot * (i + 0.5) - bw / 2, bh = Math.max(bw, v * h * 0.96);
      g.fillStyle = grad;
      g.globalAlpha = 0.2 + v * 0.42;
      g.beginPath(); g.roundRect(x, h - bh, bw, bh, bw / 2); g.fill();
      if (barPeak[i] > v + 0.04) {
        g.globalAlpha = 0.8;
        g.beginPath(); g.roundRect(x, h - barPeak[i] * h * 0.96 - bw * 0.9, bw, bw * 0.55, bw * 0.28); g.fill();
      }
    }
  } else {
    follow(waveVal, raw, dt, gain, 0.07, 0.28);
    follow(waveEcho, raw, dt, gain, 0.16, 0.6); // a slower copy trails behind and gives depth
    const front = blur(waveVal, [1, 2, 3, 2, 1]), back = blur(waveEcho, [1, 2, 3, 4, 3, 2, 1]);
    const sway = (i) => Math.sin(now / 1100 + i * 0.35) * 1.6; // a very gentle drift so a held note still breathes
    const pathFor = (vals, scale, close) => {
      const X = (i) => (i / (VIZ_WAVE - 1)) * w;
      const Y = (i) => h - 2 - Math.min(1, vals[i] * scale) * h * 0.92 + sway(i);
      g.beginPath();
      if (close) { g.moveTo(0, h); g.lineTo(0, Y(0)); } else g.moveTo(0, Y(0));
      for (let i = 0; i < VIZ_WAVE - 1; i++) g.quadraticCurveTo(X(i), Y(i), (X(i) + X(i + 1)) / 2, (Y(i) + Y(i + 1)) / 2);
      g.lineTo(w, Y(VIZ_WAVE - 1));
      if (close) { g.lineTo(w, h); g.closePath(); }
    };
    g.fillStyle = grad;
    g.globalAlpha = 0.2; pathFor(back, 1.12, true); g.fill();
    g.globalAlpha = 0.52; pathFor(front, 1, true); g.fill();
    g.globalAlpha = 0.9; g.strokeStyle = grad; g.lineWidth = 2.5; g.lineJoin = 'round'; g.lineCap = 'round';
    pathFor(front, 1, false); g.stroke();
  }
  g.globalAlpha = 1;
}
