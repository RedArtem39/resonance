'use strict';
// UI sound effects, synthesized live with Web Audio (no audio files, nothing to license, ~0 bytes).
// Character: very short tonal clicks (about 1.4-2.1 kHz, 30-70 ms), a "thunk" on switches, a 5 ms hiss for slider ticks,
// a breathy swoosh for panels, a soft rising chime for "done". Played straight to the output, so they never touch the EQ chain
// or the analyser. Switch them off in Settings.

const sfx = (() => {
  const out = AC.createGain();
  out.connect(AC.destination);
  const noiseBuf = AC.createBuffer(1, AC.sampleRate, AC.sampleRate);
  { const d = noiseBuf.getChannelData(0); for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1; }
  const cfg = { enabled: true, volume: 0.6 };
  const apply = () => { out.gain.value = cfg.enabled ? cfg.volume ** 1.6 * 0.9 : 0; }; // perceptual taper: the slider feels linear
  apply();

  // one sine/triangle blip with an exponential decay
  function tone({ f, f2, dur, type = 'sine', peak = 0.3, attack = 0.002, delay = 0 }) {
    const t = AC.currentTime + delay;
    const o = AC.createOscillator(), g = AC.createGain();
    o.type = type;
    o.frequency.setValueAtTime(f, t);
    if (f2) o.frequency.exponentialRampToValueAtTime(f2, t + dur);
    g.gain.setValueAtTime(0.0001, t);
    g.gain.linearRampToValueAtTime(peak, t + attack);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    o.connect(g); g.connect(out);
    o.start(t); o.stop(t + dur + 0.03);
  }
  // filtered noise burst (optionally sweeping)
  function noise({ f, f2, dur, q = 1, type = 'bandpass', peak = 0.3, attack = 0.003, delay = 0 }) {
    const t = AC.currentTime + delay;
    const s = AC.createBufferSource(), flt = AC.createBiquadFilter(), g = AC.createGain();
    s.buffer = noiseBuf; s.loop = true;
    flt.type = type; flt.Q.value = q;
    flt.frequency.setValueAtTime(f, t);
    if (f2) flt.frequency.exponentialRampToValueAtTime(f2, t + dur);
    g.gain.setValueAtTime(0.0001, t);
    g.gain.linearRampToValueAtTime(peak, t + attack);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    s.connect(flt); flt.connect(g); g.connect(out);
    s.start(t, Math.random() * 0.4); s.stop(t + dur + 0.03);
  }

  const sounds = {
    click: () => tone({ f: 1800, dur: 0.045, peak: 0.28 }),
    click2: () => tone({ f: 2100, dur: 0.07, peak: 0.26 }),
    thunk: () => { tone({ f: 1400, dur: 0.07, peak: 0.24 }); tone({ f: 95, f2: 55, dur: 0.06, peak: 0.2 }); },
    on: () => { tone({ f: 1250, dur: 0.06, peak: 0.24 }); tone({ f: 1880, dur: 0.1, peak: 0.24, delay: 0.05 }); tone({ f: 70, dur: 0.05, peak: 0.14 }); },
    off: () => { tone({ f: 1880, dur: 0.06, peak: 0.2 }); tone({ f: 1100, dur: 0.1, peak: 0.22, delay: 0.05 }); },
    tick: () => noise({ f: 7000, type: 'highpass', dur: 0.012, peak: 0.22, attack: 0.0008 }),
    list: () => noise({ f: 6000, type: 'highpass', dur: 0.025, peak: 0.18 }),
    swoosh: () => { noise({ f: 500, f2: 4300, q: 0.9, dur: 0.55, peak: 0.22, attack: 0.04 }); tone({ f: 1040, dur: 0.3, peak: 0.05, attack: 0.03 }); },
    swooshShort: () => noise({ f: 700, f2: 4000, q: 0.9, dur: 0.26, peak: 0.18, attack: 0.025 }),
    pop: () => { tone({ f: 580, dur: 0.22, peak: 0.24, attack: 0.005 }); tone({ f: 870, dur: 0.16, peak: 0.09, delay: 0.04 }); },
    done: () => { tone({ f: 420, dur: 0.3, peak: 0.28, attack: 0.012 }); tone({ f: 630, dur: 0.32, peak: 0.16, attack: 0.012, delay: 0.09 }); tone({ f: 840, dur: 0.38, peak: 0.1, attack: 0.012, delay: 0.18 }); },
    error: () => { tone({ f: 210, f2: 140, dur: 0.24, type: 'triangle', peak: 0.3 }); tone({ f: 190, f2: 125, dur: 0.24, type: 'triangle', peak: 0.2, delay: 0.1 }); },
    // the equalizer's lightning: a falling zap plus a spray of crackles over the ~1 s the bolt takes to cross the bands
    zap: () => {
      tone({ f: 1500, f2: 90, dur: 0.34, type: 'sawtooth', peak: 0.06, attack: 0.004 });
      noise({ f: 2600, f2: 900, q: 0.6, dur: 0.9, peak: 0.1, attack: 0.02 });
      for (let i = 0; i < 16; i++) noise({ f: 4500 + Math.random() * 3500, type: 'highpass', dur: 0.012 + Math.random() * 0.02, peak: 0.1 + Math.random() * 0.14, attack: 0.0008, delay: Math.random() * 0.95 });
    },
  };

  let lastTick = 0;
  return {
    configure(o) { Object.assign(cfg, o); apply(); },
    get enabled() { return cfg.enabled; },
    play(name) {
      if (!cfg.enabled || !sounds[name]) return;
      if (AC.state !== 'running') AC.resume();
      if (name === 'tick') { const now = performance.now(); if (now - lastTick < 35) return; lastTick = now; } // sliders fire a lot of events
      sounds[name]();
    },
  };
})();

// ---- automatic wiring: every control gets a sound without each handler having to ask for one
document.addEventListener('click', (e) => {
  const el = e.target.closest('button, .row, .yrow, .qrow, .cand, .artist');
  if (!el || el.disabled) return;
  if (el.matches('.pchip')) return; // presets play the zap instead
  if (el.matches('.tab')) return sfx.play('swooshShort');
  if (el.matches('.play, .rail-item, .fab')) return sfx.play('click2');
  if (el.matches('.row, .yrow, .qrow, .cand, .artist, .card')) return sfx.play('list');
  sfx.play('click');
}, true);
document.addEventListener('change', (e) => { if (e.target.matches('input.switch')) sfx.play(e.target.checked ? 'on' : 'off'); }, true);
document.addEventListener('input', (e) => { if (e.target.matches('input[type=range]')) sfx.play('tick'); }, true);
