'use strict';
// The "Звук" view: 10-band EQ with a live response curve and a lightning effect, plus the audio panel
// (balance with readouts, mono / swap, dynamic range, channel test, reset).
//
// The lightning is our own take on the idea: when a preset is applied or a slider is released, a bolt runs through the slider
// handles from the lowest band to the highest — layered glowing strands that crackle, a few forks, and a pulse (halo, ring and a
// highlight rising up the track) on every band it touches. It can be switched off in Settings.

const eqFx = { enabled: true };
const BAND_RANGE = 12;
const posOf = (v) => ((BAND_RANGE - v) / (BAND_RANGE * 2)) * 100;
const fmtDb = (v) => (v > 0 ? '+' : '') + (Math.round(v * 10) / 10);

function balanceLabel(v) {
  const a = Math.abs(v);
  if (a >= 0.995) return v < 0 ? 'Только слева' : 'Только справа';
  if (a < 0.005) return 'По центру';
  if (a < 0.25) return v < 0 ? 'Чуть левее' : 'Чуть правее';
  return v < 0 ? 'Левее' : 'Правее';
}
const DYN_HINT = {
  off: 'Без сжатия динамики: звук как в записи.',
  balanced: 'Мягко выравнивает громкость, пики остаются живыми.',
  night: 'Для тихого вечера: сильно сжимает пики и подтягивает тихое.',
};

function renderEq(c) {
  c.innerHTML = `<div class="eqwrap">
    <div class="panel eqcard">
      <div class="eqstage">
        <canvas class="eqcv"></canvas>
        <div class="bands">${EQ_FREQS.map((_, i) => `
          <div class="band" data-i="${i}" style="--i:${i}">
            <div class="bv"></div>
            <div class="btrack"><div class="bfill"></div><div class="bthumb"></div></div>
            <div class="bl">${EQ_LABELS[i]}</div>
          </div>`).join('')}</div>
        <canvas class="eqfx"></canvas>
      </div>
      <div class="presets">${Object.keys(EQ_PRESETS).map((p) => `<button class="pchip" data-p="${p}">${p}</button>`).join('')}<button class="pchip" data-p="Свой" disabled>Свой</button></div>
    </div>

    <div class="audiogrid">
      <div class="panel balcard wide" id="balCard">
        <div class="bhead"><h4>Баланс</h4><span class="bcenter" id="balLabel"></span></div>
        <div class="bslide">
          <div class="bticks">${Array.from({ length: 9 }, (_, i) => `<i${i === 4 ? ' class="mid"' : ''}></i>`).join('')}</div>
          <input type="range" class="bal" id="eqBal" min="-1" max="1" step="0.01">
        </div>
        <div class="bread"><span id="balL"></span><span id="balR"></span></div>
      </div>

      <div class="panel">
        <h4>Каналы</h4>
        <label class="srow"><span>Моно<small>Смешать оба канала в один</small></span><input class="switch" id="eqMono" type="checkbox"></label>
        <label class="srow"><span>Поменять каналы<small>Левый ↔ правый</small></span><input class="switch" id="eqSwap" type="checkbox"></label>
      </div>

      <div class="panel">
        <h4>Динамический диапазон</h4>
        <div class="seg" id="eqDyn">
          <button data-d="off">Выкл</button><button data-d="balanced">Баланс</button><button data-d="night">Ночь</button>
        </div>
        <p class="hint" id="dynHint"></p>
      </div>

      <div class="panel">
        <h4>Уровень</h4>
        <label class="rrow"><span>Предусиление</span><input id="eqPre" type="range" min="-12" max="12" step="0.5"><output id="eqPreV"></output></label>
        <button class="tonal" id="eqTest"><span class="ch" data-s="L">L</span><span class="lbl">Проверить каналы</span><span class="ch" data-s="R">R</span></button>
        <button class="tonal" id="eqReset"><span class="lbl">Сбросить звук</span></button>
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

    const bal = $('#eqBal', c);
    bal.value = eq.balance;
    const p = ((eq.balance + 1) / 2) * 100;
    bal.style.setProperty('--lo', Math.min(50, p) + '%'); bal.style.setProperty('--hi', Math.max(50, p) + '%');
    $('#balLabel', c).textContent = eq.mono ? 'Моно: баланс не действует' : balanceLabel(eq.balance);
    $('#balL', c).textContent = `Л  ${eq.balance <= 0 ? 100 : Math.round((1 - eq.balance) * 100)}%`;
    $('#balR', c).textContent = `${eq.balance >= 0 ? 100 : Math.round((1 + eq.balance) * 100)}%  П`;
    $('#balCard', c).classList.toggle('dim', eq.mono);

    $('#eqMono', c).checked = eq.mono; $('#eqSwap', c).checked = eq.swap;
    $$('#eqDyn button', c).forEach((b) => b.classList.toggle('on', b.dataset.d === eq.dyn));
    $('#dynHint', c).textContent = DYN_HINT[eq.dyn];
    $$('#eqPre', c).forEach((r) => r.style.setProperty('--p', ((r.value - r.min) / (r.max - r.min)) * 100 + '%'));
  };
  const commit = () => { applyEq(); saveEq(); paintAll(); };

  bandEls.forEach((el, i) => {
    const track = $('.btrack', el);
    let moved = false;
    const set = (e) => {
      const r = track.getBoundingClientRect();
      const v = clamp(Math.round((BAND_RANGE - ((e.clientY - r.top) / r.height) * BAND_RANGE * 2) * 2) / 2, -BAND_RANGE, BAND_RANGE);
      if (v !== eq.bands[i]) { eq.bands[i] = v; eq.preset = 'Свой'; moved = true; commit(); }
    };
    track.addEventListener('pointerdown', (e) => { track.setPointerCapture(e.pointerId); el.classList.add('drag'); moved = false; set(e); });
    track.addEventListener('pointermove', (e) => { if (el.classList.contains('drag')) set(e); });
    const end = () => { if (el.classList.contains('drag')) { el.classList.remove('drag'); if (moved) eqZap(); } };
    track.addEventListener('pointerup', end); track.addEventListener('pointercancel', end);
    track.addEventListener('dblclick', () => { eq.bands[i] = 0; eq.preset = 'Свой'; commit(); eqZap(); });
    el.addEventListener('wheel', (e) => { e.preventDefault(); eq.bands[i] = clamp(eq.bands[i] + (e.deltaY < 0 ? 1 : -1), -BAND_RANGE, BAND_RANGE); eq.preset = 'Свой'; commit(); sfx.play('tick'); }, { passive: false });
  });
  c.addEventListener('click', (e) => {
    const p = e.target.closest('.pchip');
    if (p && EQ_PRESETS[p.dataset.p]) { eq.bands = [...EQ_PRESETS[p.dataset.p]]; eq.preset = p.dataset.p; commit(); eqZap(); if (!eqFx.enabled) sfx.play('click'); }
    const d = e.target.closest('#eqDyn button');
    if (d) { eq.dyn = d.dataset.d; commit(); }
  });
  $('#eqPre', c).oninput = (e) => { eq.pre = +e.target.value; commit(); };
  $('#eqBal', c).oninput = (e) => { eq.balance = Math.abs(+e.target.value) < 0.03 ? 0 : +e.target.value; commit(); }; // snaps to centre
  $('#eqBal', c).ondblclick = () => { eq.balance = 0; commit(); };
  $('#eqMono', c).onchange = (e) => { eq.mono = e.target.checked; if (eq.mono) eq.swap = false; commit(); };
  $('#eqSwap', c).onchange = (e) => { eq.swap = e.target.checked; if (eq.swap) eq.mono = false; commit(); };
  $('#eqTest', c).onclick = () => eqTest((s) => $$('#eqTest .ch', c).forEach((n) => n.classList.toggle('on', n.dataset.s === s || s === 'C')));
  $('#eqReset', c).onclick = () => { resetAudio(); paintAll(); sfx.play('swooshShort'); };
  paintAll();
  setTimeout(eqZap, 520); // a first bolt as the bands finish sliding in
}

// ---------------------------------------------------------------- lightning
const fx = { active: false, start: 0, hitAt: Array(10).fill(0), hitDone: Array(10).fill(false), rng: [], rngT: 0, forks: [] };
const easeOut = (t) => 1 - (1 - clamp(t, 0, 1)) ** 3;

function eqZap() {
  if (!eqFx.enabled || !$('.eqfx')) return;
  fx.active = true;
  fx.start = performance.now();
  fx.hitDone.fill(false);
  fx.hitAt.fill(0);
  fx.forks = Array.from({ length: 9 }, () => Array.from({ length: 2 }, () => ({
    at: 0.2 + Math.random() * 0.6, ang: (Math.random() < 0.5 ? -1 : 1) * (0.7 + Math.random() * 0.8), len: 26 + Math.random() * 40, on: Math.random() < 0.65,
  })));
  sfx.play('zap');
}

function drawFx(now) {
  const cv = $('.eqfx');
  if (!cv) return;
  const dpr = devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
  if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
  const g = cv.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  if (!fx.active) return;

  const OY = 20, TRACK_H = 240, cw = w / 10; // the canvas has 20 px of headroom above the tracks, for the glow
  const age = (now - fx.start) / 1000;
  const p = Math.min(9, age / 0.1);                      // 0.1 s per segment
  const fade = clamp((age - 0.9 - 0.25) / 0.55, 0, 1);   // hold a moment after the last band, then fade out
  if (fade >= 1) { fx.active = false; return; }

  const pts = Array.from({ length: 10 }, (_, i) => ({ x: (i + 0.5) * cw, y: OY + (posOf(eq.bands[i]) / 100) * TRACK_H }));
  const bandEls = $$('.band');
  for (let i = 0; i < 10; i++) {
    if (!fx.hitDone[i] && p >= i) {
      fx.hitDone[i] = true; fx.hitAt[i] = now;
      const el = bandEls[i];
      if (el) { el.classList.remove('hit'); void el.offsetWidth; el.classList.add('hit'); setTimeout(() => el.classList.remove('hit'), 900); }
    }
  }

  if (now - fx.rngT > 45) { // re-roll the crackle ~22 times a second
    fx.rngT = now;
    fx.rng = Array.from({ length: 9 * 9 }, () => [Math.random() * 2 - 1, Math.random() * 2 - 1]);
  }

  // the bolt: polyline through the handles, each segment jittered sideways
  const line = [];
  const STEPS = 8;
  for (let i = 0; i < 9 && p > i; i++) {
    const frac = Math.min(1, p - i), a = pts[i], b = pts[i + 1];
    const dx = b.x - a.x, dy = b.y - a.y, len = Math.hypot(dx, dy) || 1, nx = -dy / len, ny = dx / len;
    for (let j = i === 0 ? 0 : 1; j <= STEPS; j++) {
      const t = (j / STEPS) * frac;
      const env = Math.sin(Math.min(1, j / STEPS) * Math.PI) * (1 - fade);
      const r = fx.rng[i * 9 + Math.min(j, 8)];
      line.push({ x: a.x + dx * t + nx * r[0] * 12 * env + (dx / len) * r[1] * 3 * env, y: a.y + dy * t + ny * r[0] * 12 * env + (dy / len) * r[1] * 3 * env });
      if (j / STEPS >= frac) break;
    }
  }

  const primary = cssColor('--primary'), tertiary = cssColor('--tertiary');
  const core = document.documentElement.dataset.theme === 'light' ? cssColor('--on-surface') : '#ffffff';
  const grad = g.createLinearGradient(0, 0, w, 0);
  grad.addColorStop(0, primary); grad.addColorStop(1, tertiary);

  // pulses on the bands the bolt has reached
  for (let i = 0; i < 10; i++) {
    if (!fx.hitAt[i]) continue;
    const t = (now - fx.hitAt[i]) / 1000;
    if (t > 1.2) continue;
    const { x, y } = pts[i];
    const halo = g.createRadialGradient(x, y, 2, x, y, 12 + 20 * easeOut(t / 0.5));
    halo.addColorStop(0, primary); halo.addColorStop(1, 'transparent');
    g.globalAlpha = 0.65 * (1 - clamp(t / 0.7, 0, 1)) * (1 - fade);
    g.fillStyle = halo; g.beginPath(); g.arc(x, y, 34, 0, Math.PI * 2); g.fill();
    g.globalAlpha = 0.75 * (1 - clamp(t / 0.8, 0, 1));
    g.strokeStyle = tertiary; g.lineWidth = 2;
    g.beginPath(); g.arc(x, y, 8 + 22 * easeOut(t / 0.8), 0, Math.PI * 2); g.stroke();
    const ty = y - (y - OY) * easeOut(t / 1.0); // a spark climbing the track
    const spark = g.createLinearGradient(0, ty - 26, 0, ty + 26);
    spark.addColorStop(0, 'transparent'); spark.addColorStop(0.5, core); spark.addColorStop(1, 'transparent');
    g.globalAlpha = 0.55 * (1 - clamp(t / 1.0, 0, 1));
    g.fillStyle = spark; g.fillRect(x - 3, ty - 26, 6, 52);
  }

  if (line.length > 1) {
    g.lineCap = 'round'; g.lineJoin = 'round';
    const path = () => { g.beginPath(); line.forEach((q, k) => (k ? g.lineTo(q.x, q.y) : g.moveTo(q.x, q.y))); };
    const layers = [[20, 0.16, grad, 18], [10, 0.34, grad, 12], [4.5, 0.7, grad, 0], [2.2, 1, core, 0]];
    for (const [lw, a, style, blur] of layers) {
      g.globalAlpha = a * (1 - fade); g.strokeStyle = style; g.lineWidth = lw;
      g.shadowColor = primary; g.shadowBlur = blur;
      path(); g.stroke();
    }
    g.shadowBlur = 0;

    // forks: short thin branches off the bolt
    g.strokeStyle = core; g.lineWidth = 1.4;
    for (let i = 0; i < 9 && p > i; i++) {
      for (const f of fx.forks[i]) {
        if (!f.on || p < i + f.at) continue;
        const a = pts[i], b = pts[i + 1], base = Math.atan2(b.y - a.y, b.x - a.x) + f.ang;
        let x = a.x + (b.x - a.x) * f.at, y = a.y + (b.y - a.y) * f.at;
        g.globalAlpha = 0.55 * (1 - fade);
        g.beginPath(); g.moveTo(x, y);
        for (let k = 1; k <= 3; k++) {
          const r = fx.rng[(i * 9 + k) % fx.rng.length];
          x += Math.cos(base + r[0] * 0.5) * (f.len / 3); y += Math.sin(base + r[0] * 0.5) * (f.len / 3);
          g.lineTo(x, y);
        }
        g.stroke();
      }
    }
  }
  g.globalAlpha = 1;
}

// ---------------------------------------------------------------- response curve + dB grid (called from the main frame loop)
const SPEC_N = 140;
const specFreq = Array.from({ length: SPEC_N }, (_, i) => 20 * 1000 ** (i / (SPEC_N - 1)));
const respFreq = Float32Array.from(specFreq);
const respMag = new Float32Array(SPEC_N), respPh = new Float32Array(SPEC_N), respDb = new Float32Array(SPEC_N);

function drawEqViz() {
  const cv = $('.eqcv');
  if (!cv) return;
  const now = performance.now();
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

  drawFx(now);
}
