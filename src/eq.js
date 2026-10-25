'use strict';
// Audio engine (Web Audio graph) + the "Звук" view: 10-band EQ with live spectrum, balance, mono, channel swap, dynamics, test tone.

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
  balanced: { threshold: -26, knee: 24, ratio: 3, attack: 0.01, release: 0.25, makeup: 1.35 },
  night: { threshold: -42, knee: 18, ratio: 9, attack: 0.005, release: 0.3, makeup: 2.4 },
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
[merger, panner, comp, makeup, analyser, AC.destination].reduce((a, b) => (a.connect(b), b));

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

// Test tone: left, right, then a centred sweep (3.2 s). Bypasses the EQ chain on purpose — it tests the output, not the filters.
let testing = false;
function eqTest(onStep) {
  if (testing) return;
  testing = true;
  AC.resume();
  const osc = AC.createOscillator(), g = AC.createGain(), p = AC.createStereoPanner();
  osc.connect(g); g.connect(p); p.connect(AC.destination);
  const t0 = AC.currentTime + 0.05;
  p.pan.setValueAtTime(-1, t0); p.pan.setValueAtTime(1, t0 + 1); p.pan.setValueAtTime(0, t0 + 2);
  osc.frequency.setValueAtTime(440, t0);
  osc.frequency.setValueAtTime(440, t0 + 2);
  osc.frequency.exponentialRampToValueAtTime(1760, t0 + 3.2);
  g.gain.setValueAtTime(0, t0);
  g.gain.linearRampToValueAtTime(0.18, t0 + 0.05);
  g.gain.setValueAtTime(0.18, t0 + 3.1);
  g.gain.linearRampToValueAtTime(0, t0 + 3.2);
  osc.start(t0); osc.stop(t0 + 3.25);
  [['L', 0], ['R', 1000], ['C', 2000], [null, 3200]].forEach(([s, ms]) => setTimeout(() => onStep?.(s), ms));
  setTimeout(() => { testing = false; }, 3300);
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

// ---------------------------------------------------------------- view
const BAND_RANGE = 12;
const posOf = (v) => ((BAND_RANGE - v) / (BAND_RANGE * 2)) * 100;
const fmtDb = (v) => (v > 0 ? '+' : '') + (Math.round(v * 10) / 10);

function renderEq(c) {
  c.innerHTML = `<div class="eqwrap">
    <div class="panel eqcard">
      <div class="eqstage">
        <canvas class="eqcv"></canvas>
        <div class="bands">${EQ_FREQS.map((_, i) => `
          <div class="band" data-i="${i}">
            <div class="bv"></div>
            <div class="btrack"><div class="bfill"></div><div class="bthumb"></div></div>
            <div class="bl">${EQ_LABELS[i]}</div>
          </div>`).join('')}</div>
      </div>
      <div class="presets">${Object.keys(EQ_PRESETS).map((p) => `<button class="pchip" data-p="${p}">${p}</button>`).join('')}<button class="pchip" data-p="Свой" disabled>Свой</button></div>
    </div>
    <div class="eqgrid">
      <div class="panel">
        <h4>Уровни</h4>
        <label class="rrow"><span>Предусиление</span><input id="eqPre" type="range" min="-12" max="12" step="0.5"><output id="eqPreV"></output></label>
        <label class="rrow"><span>Баланс</span><input id="eqBal" type="range" min="-1" max="1" step="0.05"><output id="eqBalV"></output></label>
      </div>
      <div class="panel">
        <h4>Каналы</h4>
        <label class="srow"><span>Моно<small>Смешать каналы в один</small></span><input class="switch" id="eqMono" type="checkbox"></label>
        <label class="srow"><span>Поменять каналы<small>Левый ↔ правый</small></span><input class="switch" id="eqSwap" type="checkbox"></label>
      </div>
      <div class="panel">
        <h4>Динамика</h4>
        <div class="seg" id="eqDyn">
          <button data-d="off">Выкл</button><button data-d="balanced">Баланс</button><button data-d="night">Ночь</button>
        </div>
        <button class="tonal" id="eqTest"><span class="ch" data-s="L">L</span><span class="lbl">Тест звука</span><span class="ch" data-s="R">R</span></button>
      </div>
    </div>
  </div>`;

  const bandEls = $$('.band', c);
  const paintBand = (i) => {
    const v = eq.bands[i], el = bandEls[i];
    el.style.setProperty('--pos', posOf(v) + '%');
    $('.bv', el).textContent = fmtDb(v);
    el.classList.toggle('zero', v === 0);
  };
  const paintAll = () => {
    bandEls.forEach((_, i) => paintBand(i));
    $$('.pchip', c).forEach((b) => b.classList.toggle('on', b.dataset.p === eq.preset));
    $('[data-p="Свой"]', c).disabled = eq.preset !== 'Свой';
    $('#eqPre', c).value = eq.pre; $('#eqPreV', c).textContent = fmtDb(eq.pre) + ' дБ';
    $('#eqBal', c).value = eq.balance;
    $('#eqBalV', c).textContent = eq.balance === 0 ? 'Центр' : `${eq.balance < 0 ? 'Л' : 'П'} ${Math.round(Math.abs(eq.balance) * 100)}%`;
    $('#eqMono', c).checked = eq.mono; $('#eqSwap', c).checked = eq.swap;
    $$('#eqDyn button', c).forEach((b) => b.classList.toggle('on', b.dataset.d === eq.dyn));
    $$('input[type=range]', c).forEach((r) => r.style.setProperty('--p', ((r.value - r.min) / (r.max - r.min)) * 100 + '%'));
  };
  const commit = () => { applyEq(); saveEq(); paintAll(); };

  bandEls.forEach((el, i) => {
    const track = $('.btrack', el);
    const set = (e) => {
      const r = track.getBoundingClientRect();
      const v = clamp(Math.round((BAND_RANGE - ((e.clientY - r.top) / r.height) * BAND_RANGE * 2) * 2) / 2, -BAND_RANGE, BAND_RANGE);
      if (v !== eq.bands[i]) { eq.bands[i] = v; eq.preset = 'Свой'; commit(); }
    };
    track.addEventListener('pointerdown', (e) => { track.setPointerCapture(e.pointerId); el.classList.add('drag'); set(e); });
    track.addEventListener('pointermove', (e) => { if (el.classList.contains('drag')) set(e); });
    const end = () => el.classList.remove('drag');
    track.addEventListener('pointerup', end); track.addEventListener('pointercancel', end);
    track.addEventListener('dblclick', () => { eq.bands[i] = 0; eq.preset = 'Свой'; commit(); });
    el.addEventListener('wheel', (e) => { e.preventDefault(); eq.bands[i] = clamp(eq.bands[i] + (e.deltaY < 0 ? 1 : -1), -BAND_RANGE, BAND_RANGE); eq.preset = 'Свой'; commit(); }, { passive: false });
  });
  c.addEventListener('click', (e) => {
    const p = e.target.closest('.pchip');
    if (p && EQ_PRESETS[p.dataset.p]) { eq.bands = [...EQ_PRESETS[p.dataset.p]]; eq.preset = p.dataset.p; commit(); }
    const d = e.target.closest('#eqDyn button');
    if (d) { eq.dyn = d.dataset.d; commit(); }
  });
  $('#eqPre', c).oninput = (e) => { eq.pre = +e.target.value; commit(); };
  $('#eqBal', c).oninput = (e) => { eq.balance = +e.target.value; commit(); };
  $('#eqBal', c).ondblclick = () => { eq.balance = 0; commit(); };
  $('#eqMono', c).onchange = (e) => { eq.mono = e.target.checked; if (eq.mono) eq.swap = false; commit(); };
  $('#eqSwap', c).onchange = (e) => { eq.swap = e.target.checked; if (eq.swap) eq.mono = false; commit(); };
  $('#eqTest', c).onclick = () => eqTest((s) => $$('#eqTest .ch', c).forEach((n) => n.classList.toggle('on', n.dataset.s === s || s === 'C')));
  paintAll();
}

// ---------------------------------------------------------------- response curve + dB grid (called from the main frame loop)
const SPEC_N = 140;
const specFreq = Array.from({ length: SPEC_N }, (_, i) => 20 * 1000 ** (i / (SPEC_N - 1)));
const respFreq = Float32Array.from(specFreq);
const respMag = new Float32Array(SPEC_N), respPh = new Float32Array(SPEC_N), respDb = new Float32Array(SPEC_N);

function drawEqViz() {
  const cv = $('.eqcv');
  if (!cv) return;
  const dpr = devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
  if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
  const g = cv.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);

  const colW = w / 10;
  const xOf = (f) => colW / 2 + (Math.log2(f / 31) / Math.log2(16000 / 31)) * (w - colW);
  const primary = cssColor('--primary');

  if (curveDirty) {
    respDb.fill(0);
    shadow.forEach((f) => { f.getFrequencyResponse(respFreq, respMag, respPh); for (let i = 0; i < SPEC_N; i++) respDb[i] += 20 * Math.log10(respMag[i] || 1e-6); });
    curveDirty = false;
  }
  const yOfDb = (db) => h / 2 - (clamp(db, -BAND_RANGE * 1.3, BAND_RANGE * 1.3) / (BAND_RANGE * 2)) * h;

  // dB grid: 0 dB is the reference line, ±6 / ±12 are faint
  g.lineWidth = 1;
  for (const db of [-12, -6, 0, 6, 12]) {
    g.globalAlpha = db === 0 ? 0.5 : 0.18;
    g.strokeStyle = cssColor('--on-surface-var');
    g.setLineDash(db === 0 ? [] : [3, 6]);
    g.beginPath(); g.moveTo(0, Math.round(yOfDb(db)) + 0.5); g.lineTo(w, Math.round(yOfDb(db)) + 0.5); g.stroke();
  }
  g.setLineDash([]);

  // target response curve, drawn on top of the sliders
  g.globalAlpha = 1; g.strokeStyle = primary; g.lineWidth = 3.5; g.lineJoin = 'round'; g.lineCap = 'round';
  g.shadowColor = primary; g.shadowBlur = 12;
  g.beginPath();
  for (let i = 0; i < SPEC_N; i++) { const y = yOfDb(respDb[i]); i ? g.lineTo(xOf(specFreq[i]), y) : g.moveTo(xOf(specFreq[i]), y); }
  g.stroke();
  g.shadowBlur = 0;
}
