'use strict';
// Synced lyrics (LRCLIB gives per-line timestamps). Words inside the active line are filled progressively:
// each word gets a time slice proportional to its length, so focus moves word by word instead of the whole line at once.

const Lyr = { lines: [], synced: false, idx: -1, token: 0, lastWheel: 0 };
const lyBox = $('#lyrics');

function parseLrc(text) {
  const out = [];
  for (const raw of text.split(/\r?\n/)) {
    const re = /\[(\d+):(\d+(?:\.\d+)?)\]/g;
    const stamps = [];
    let m, end = 0;
    while ((m = re.exec(raw))) { stamps.push(+m[1] * 60 + +m[2]); end = re.lastIndex; }
    if (!stamps.length) continue;
    const txt = raw.slice(end).replace(/<\d+:\d+(?:\.\d+)?>/g, '').trim();
    for (const t of stamps) out.push({ t, text: txt });
  }
  return out.sort((a, b) => a.t - b.t);
}

function lyState(icon, msg) {
  lyBox.innerHTML = `<div class="lystate">${svg(icon)}<p>${esc(msg)}</p></div>`;
  Lyr.lines = []; Lyr.idx = -1;
}

function buildLines(list, synced) {
  Lyr.synced = synced;
  Lyr.idx = -1;
  Lyr.lines = list.map((l) => {
    const el = document.createElement('div');
    el.className = 'ly' + (l.text ? '' : ' gap');
    let words = [];
    if (!l.text) {
      el.innerHTML = '<span class="dot"></span><span class="dot"></span><span class="dot"></span>';
    } else {
      // Spaces separate words; text without spaces (CJK) is split per character so focus still travels.
      const spaced = /\s/.test(l.text);
      const toks = spaced ? l.text.split(/\s+/) : [...l.text];
      const weights = toks.map((w) => w.length + (spaced ? 1 : 0));
      const total = weights.reduce((a, b) => a + b, 0);
      let acc = 0;
      words = toks.map((w, i) => {
        const a = acc / total; acc += weights[i];
        const s = document.createElement('span');
        s.className = 'w';
        s.textContent = w;
        el.append(s);
        if (spaced && i < toks.length - 1) el.append(' ');
        return { el: s, a, b: acc / total, f: -1 };
      });
    }
    if (synced) el.onclick = () => { audio.currentTime = l.t + 0.01; if (audio.paused) audio.play(); };
    return { t: l.t, text: l.text, el, words };
  });
  lyBox.classList.toggle('plain', !synced);
  lyBox.replaceChildren(...Lyr.lines.map((l) => l.el));
  if (!synced) lyBox.insertAdjacentHTML('afterbegin', '<div class="lynote">Текст без синхронизации</div>');
}

async function loadLyrics(t) {
  const token = ++Lyr.token;
  if (!t) return lyState('music', 'Ничего не играет');
  lyBox.scrollTop = 0;
  lyBox.innerHTML = '<div class="lystate"><div class="lyload"><i></i><i></i><i></i></div><p>Ищу текст…</p></div>';
  Lyr.lines = []; Lyr.idx = -1;
  const res = await window.api.lyrics({ artist: t.artist, title: t.title, album: t.album, duration: t.duration || audio.duration || 0 });
  if (token !== Lyr.token) return;
  if (res?.synced) {
    const list = parseLrc(res.synced);
    if (list.length) return buildLines(list, true);
  }
  if (res?.plain) return buildLines(res.plain.split(/\r?\n/).map((text) => ({ t: 0, text: text.trim() })).filter((l, i, a) => l.text || (a[i - 1]?.text)), false);
  lyState('lyrics', 'Текст не найден');
}

function lySetActive(idx) {
  Lyr.lines.forEach((l, i) => {
    l.el.classList.toggle('active', i === idx);
    l.el.classList.toggle('past', i < idx);
    l.el.style.setProperty('--d', Math.abs(i - idx));
  });
  const cur = Lyr.lines[idx];
  if (cur && performance.now() - Lyr.lastWheel > 2500) {
    lyBox.scrollTo({ top: cur.el.offsetTop - lyBox.clientHeight / 2 + cur.el.offsetHeight / 2, behavior: 'smooth' });
  }
}

// Called every animation frame while the lyrics tab is visible.
function tickLyrics(time) {
  if (!Lyr.synced || !Lyr.lines.length) return;
  const t = time + 0.12;
  let lo = 0, hi = Lyr.lines.length - 1, idx = -1;
  while (lo <= hi) { const mid = (lo + hi) >> 1; if (Lyr.lines[mid].t <= t) { idx = mid; lo = mid + 1; } else hi = mid - 1; }
  if (idx !== Lyr.idx) { Lyr.idx = idx; lySetActive(idx); }
  const L = Lyr.lines[idx];
  if (!L || !L.words.length) return;
  const next = Lyr.lines[idx + 1];
  const dur = clamp(Math.min(next ? next.t - L.t : 6, Math.max(1.2, L.text.length * 0.08)), 0.6, 9);
  const p = clamp((time - L.t) / dur, 0, 1);
  for (const w of L.words) {
    const f = Math.round(clamp((p - w.a) / (w.b - w.a), 0, 1) * 100);
    if (f !== w.f) { w.f = f; w.el.style.setProperty('--f', f); }
  }
}

lyBox.addEventListener('wheel', () => { Lyr.lastWheel = performance.now(); }, { passive: true });
lyState('music', 'Ничего не играет');
