'use strict';
// Visualizer that lives behind the transport controls (toggle: button next to the sleep timer).
// Capsule bars grow wider as they get louder, peak caps fall slowly, and a bass-driven glow pulses under the play button.

const vizCv = $('#viz');
const vizBox = $('.dock-low');
const VIZ_N = 26;
const vizBins = new Uint8Array(analyser.frequencyBinCount);
const vizVal = new Float32Array(VIZ_N);
const vizPeak = new Float32Array(VIZ_N);
const vizEdges = Array.from({ length: VIZ_N + 1 }, (_, i) => 45 * (15000 / 45) ** (i / VIZ_N));
let vizOn = false, vizBass = 0, vizMax = 0.5;

function setViz(on) {
  vizOn = on;
  store.set('viz', on);
  vizBox.classList.toggle('viz-on', on);
  $('#vizBtn').classList.toggle('on', on);
}
$('#vizBtn').onclick = () => setViz(!vizOn);
setViz(store.get('viz', false));

function drawViz(now) {
  if (!vizOn) return;
  const w = vizCv.clientWidth, h = vizCv.clientHeight;
  const g = fit(vizCv, w, h);
  analyser.getByteFrequencyData(vizBins);
  const hz = AC.sampleRate / analyser.fftSize;

  // raw band levels, then auto-gain: normalise by a slowly decaying running maximum so quiet and loud tracks both use the full height
  const raw = new Float32Array(VIZ_N);
  let frameMax = 0;
  for (let i = 0; i < VIZ_N; i++) {
    const lo = Math.max(1, Math.floor(vizEdges[i] / hz)), hi = Math.max(lo + 1, Math.ceil(vizEdges[i + 1] / hz));
    let m = 0;
    for (let k = lo; k < hi; k++) m = Math.max(m, vizBins[k]);
    raw[i] = (m / 255) * (0.8 + (i / VIZ_N) * 0.6); // spectra fall off with frequency; lift the top end
    frameMax = Math.max(frameMax, raw[i]);
  }
  vizMax += (frameMax - vizMax) * (frameMax > vizMax ? 0.3 : 0.01);
  const norm = Math.max(0.3, vizMax);
  for (let i = 0; i < VIZ_N; i++) {
    const v = Math.min(1, (raw[i] / norm) ** 2.4); // steep curve = real contrast between loud and quiet bands
    vizVal[i] += (v - vizVal[i]) * (v > vizVal[i] ? 0.7 : 0.12);
    vizPeak[i] = Math.max(vizVal[i], vizPeak[i] - 0.006);
  }
  let bass = 0;
  for (let k = 1; k <= 6; k++) bass += vizBins[k];
  bass = bass / 6 / 255;
  vizBass += (bass - vizBass) * (bass > vizBass ? 0.5 : 0.1);

  const primary = cssColor('--primary'), tertiary = cssColor('--tertiary');

  // bass glow
  const glow = g.createRadialGradient(w / 2, h, 0, w / 2, h, w * 0.75);
  glow.addColorStop(0, primary); glow.addColorStop(1, 'transparent');
  g.globalAlpha = 0.05 + vizBass ** 2 * 0.5;
  g.fillStyle = glow;
  g.fillRect(0, 0, w, h);

  // capsule bars
  const fill = g.createLinearGradient(0, 0, w, 0);
  fill.addColorStop(0, primary); fill.addColorStop(1, tertiary);
  const slot = w / VIZ_N;
  for (let i = 0; i < VIZ_N; i++) {
    const v = vizVal[i], bw = slot * (0.28 + v * 0.55), x = slot * (i + 0.5) - bw / 2;
    const bh = Math.max(bw, v * h * 0.97);
    g.globalAlpha = 0.2 + v * 0.42;
    g.fillStyle = fill;
    g.beginPath(); g.roundRect(x, h - bh, bw, bh, bw / 2); g.fill();
    if (vizPeak[i] > v + 0.04) {
      g.globalAlpha = 0.85;
      g.beginPath(); g.roundRect(x, h - vizPeak[i] * h * 0.97 - bw * 0.9, bw, bw * 0.55, bw * 0.28); g.fill();
    }
  }
  g.globalAlpha = 1;
}
