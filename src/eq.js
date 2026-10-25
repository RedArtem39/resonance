'use strict';
// Audio engine (Web Audio graph): 10-band EQ, balance, mono, channel swap, dynamics + limiter, channel test. The "Звук" view lives in eqview.js.

const audio = new Audio();
audio.crossOrigin = 'anonymous'; // rmedia:// sends CORS headers, so the analyser sees real samples
audio.preload = 'auto';

const EQ_FREQS = [31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
const EQ_LABELS = ['31', '63', '125', '250', '500', '1k', '2k', '4k', '8k', '16k'];
const EQ_PRESETS = {
  'Плоский': [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  'Бас': [5, 7, 5, 2, 1, 0, 0, 0, 1, 2],
  'Высокие': [-2, -1, 0, 1, 2, 3, 4, 5, 6, 6],
  'Вокал': [-2, -1, 1, 3, 5, 5, 4, 2, 1, 0],
  'Поп': [2, 4, 2, 0, 1, 2, 4, 2, 1, 2],
  'Рок': [5, 4, 2, -1, -2, -1, 2, 4, 5, 6],
  'Джаз': [3, 3, 1, 1, 1, 1, 2, 1, 2, 3],
  'Классика': [0, 1, 2, 2, 2, 2, 1, 2, 3, 4],
};
const DYN = {
  off: { threshold: 0, knee: 0, ratio: 1, attack: 0.003, release: 0.25, makeup: 1 },
  balanced: { threshold: -26, knee: 10, ratio: 2.8, attack: 0.014, release: 0.2, makeup: 1.26 },
  night: { threshold: -36, knee: 14, ratio: 6, attack: 0.006, release: 0.32, makeup: 1.6 },
};

const eq = { bands: Array(10).fill(0), preset: 'Плоский', pre: 0, balance: 0, mono: false, swap: false, dyn: 'off', ...store.get('eq', {}) };
if (!Array.isArray(eq.bands) || eq.bands.length !== 10) eq.bands = Array(10).fill(0);
const saveEq = () => store.set('eq', eq);

// ---------------------------------------------------------------- graph
const AC = new AudioContext();
const source = AC.createMediaElementSource(audio);
const preamp = AC.createGain();
const filters = EQ_FREQS.map((f, i) => {
  const b = AC.createBiquadFilter();
  b.type = i === 0 ? 'lowshelf' : i === 9 ? 'highshelf' : 'peaking';
  b.frequency.value = f;
  b.Q.value = 1.1;
  return b;
});
const shadow = filters.map((f) => { const b = AC.createBiquadFilter(); b.type = f.type; b.frequency.value = f.frequency.value; b.Q.value = f.Q.value; return b; });
const stereoize = AC.createGain(); // forces mono files up to 2 channels so the matrix below always has L and R
stereoize.channelCount = 2; stereoize.channelCountMode = 'explicit'; stereoize.channelInterpretation = 'speakers';
const splitter = AC.createChannelSplitter(2);
const merger = AC.createChannelMerger(2);
const mx = { ll: AC.createGain(), lr: AC.createGain(), rl: AC.createGain(), rr: AC.createGain() };
const panner = AC.createStereoPanner();
const comp = AC.createDynamicsCompressor();
const makeup = AC.createGain();
const limiter = AC.createDynamicsCompressor(); // brick-wall safety net after the EQ: boosted bands must not clip
limiter.threshold.value = -1; limiter.knee.value = 0; limiter.ratio.value = 20; limiter.attack.value = 0.005; limiter.release.value = 0.05;
const analyser = AC.createAnalyser();
analyser.fftSize = 2048;
analyser.smoothingTimeConstant = 0.8;
analyser.minDecibels = -92; // wide window so loud bass does not clip flat at the top
analyser.maxDecibels = -6;

source.connect(preamp);
[preamp, ...filters, stereoize].reduce((a, b) => (a.connect(b), b));
stereoize.connect(splitter);
splitter.connect(mx.ll, 0); splitter.connect(mx.lr, 0);
splitter.connect(mx.rl, 1); splitter.connect(mx.rr, 1);
mx.ll.connect(merger, 0, 0); mx.rl.connect(merger, 0, 0);
mx.lr.connect(merger, 0, 1); mx.rr.connect(merger, 0, 1);
[merger, panner, comp, makeup, limiter, analyser, AC.destination].reduce((a, b) => (a.connect(b), b));

let curveDirty = true;
function applyEq() {
  const t = AC.currentTime;
  filters.forEach((f, i) => f.gain.setTargetAtTime(eq.bands[i], t, 0.02));
  shadow.forEach((f, i) => { f.gain.value = eq.bands[i]; });
  preamp.gain.setTargetAtTime(10 ** (eq.pre / 20), t, 0.02);
  const [ll, lr, rl, rr] = eq.mono ? [0.5, 0.5, 0.5, 0.5] : eq.swap ? [0, 1, 1, 0] : [1, 0, 0, 1];
  mx.ll.gain.value = ll; mx.lr.gain.value = lr; mx.rl.gain.value = rl; mx.rr.gain.value = rr;
  panner.pan.setTargetAtTime(eq.balance, t, 0.02);
  const d = DYN[eq.dyn] ?? DYN.off;
  comp.threshold.value = d.threshold; comp.knee.value = d.knee; comp.ratio.value = d.ratio;
  comp.attack.value = d.attack; comp.release.value = d.release;
  makeup.gain.setTargetAtTime(d.makeup, t, 0.05);
  curveDirty = true;
}
applyEq();
audio.addEventListener('play', () => { if (AC.state !== 'running') AC.resume(); });

// Channel test: a tone in the left speaker, then the right, then both. It runs through the whole chain on purpose, so it also
// shows what Balance / Swap / Mono do. (Same idea and frequencies as the original: 660 Hz left, 880 Hz right, 770 Hz both.)
let testing = false;
function eqTest(onStep) {
  if (testing) return;
  testing = true;
  AC.resume();
  const t0 = AC.currentTime + 0.05;
  const beep = (freq, start, dur, pan) => {
    const o = AC.createOscillator(), g = AC.createGain(), p = AC.createStereoPanner();
    o.frequency.value = freq; p.pan.value = pan;
    o.connect(g); g.connect(p); p.connect(preamp);
    const a = t0 + start, z = a + dur;
    g.gain.setValueAtTime(0, a); g.gain.linearRampToValueAtTime(0.27, a + 0.015);
    g.gain.setValueAtTime(0.27, z - 0.015); g.gain.linearRampToValueAtTime(0, z);
    o.start(a); o.stop(z + 0.02);
  };
  beep(660, 0, 0.75, -1); beep(880, 0.93, 0.75, 1); beep(770, 1.86, 1.0, 0);
  [['L', 0], ['R', 930], ['C', 1860], [null, 2900]].forEach(([step, ms]) => setTimeout(() => onStep?.(step), ms));
  setTimeout(() => { testing = false; }, 3000);
}

function resetAudio() {
  eq.balance = 0; eq.mono = false; eq.swap = false; eq.dyn = 'off';
  applyEq(); saveEq();
}

// ---------------------------------------------------------------- colours for canvas drawing (resolved from CSS tokens)
const colorProbes = {};
function cssColor(v) {
  let el = colorProbes[v];
  if (!el) {
    el = colorProbes[v] = document.createElement('i');
    el.style.cssText = `position:absolute;visibility:hidden;color:var(${v})`;
    document.body.append(el);
  }
  return getComputedStyle(el).color;
}
