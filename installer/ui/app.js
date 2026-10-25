'use strict';

const $ = (s) => document.querySelector(s);

// ---------------------------------------------------------------- strings (English / Russian by system locale)
const T = {
  en: {
    eyebrow: 'Setup', tagline: 'An offline music player with a real equalizer, synced lyrics and YouTube search.',
    pt1: '10-band equalizer with live spectrum', pt2: 'Lyrics that follow the song, word by word', pt3: 'Search and download straight from YouTube',
    install: 'Install', update: 'Update', options: 'Options', back: 'Back', license: 'GNU GPL v3 or later',
    existing: (v) => `Resonance${v ? ' ' + v : ''} is already installed. This will update it and keep your library and settings.`,
    optionsTitle: 'Options', location: 'Install location', browse: 'Browse…',
    desktop: 'Desktop shortcut', startmenu: 'Start menu shortcut', launchAfter: 'Launch Resonance when finished',
    readLicense: 'Read the license', licenseTitle: 'GNU General Public License v3', close: 'Close',
    installing: 'Installing', doneTitle: 'All set', doneText: (d) => `Resonance is installed in ${d}.`,
    launchNow: 'Launch Resonance', finish: 'Finish', errTitle: 'Something went wrong', retry: 'Try again',
    busy: (f) => `Could not write ${f}. Close Resonance if it is running and try again.`,
  },
  ru: {
    eyebrow: 'Установка', tagline: 'Офлайн-плеер с настоящим эквалайзером, синхронными текстами и поиском на YouTube.',
    pt1: '10-полосный эквалайзер с живым спектром', pt2: 'Тексты, которые идут за песней по словам', pt3: 'Поиск и загрузка прямо с YouTube',
    install: 'Установить', update: 'Обновить', options: 'Настройки', back: 'Назад', license: 'GNU GPL v3 или новее',
    existing: (v) => `Resonance${v ? ' ' + v : ''} уже установлен. Установщик обновит его, библиотека и настройки сохранятся.`,
    optionsTitle: 'Настройки', location: 'Папка установки', browse: 'Обзор…',
    desktop: 'Ярлык на рабочем столе', startmenu: 'Ярлык в меню «Пуск»', launchAfter: 'Запустить Resonance после установки',
    readLicense: 'Читать лицензию', licenseTitle: 'Стандартная общественная лицензия GNU v3', close: 'Закрыть',
    installing: 'Установка', doneTitle: 'Готово', doneText: (d) => `Resonance установлен в ${d}.`,
    launchNow: 'Запустить Resonance', finish: 'Завершить', errTitle: 'Что-то пошло не так', retry: 'Повторить',
    busy: (f) => `Не удалось записать ${f}. Закрой Resonance, если он запущен, и повтори.`,
  },
};
let L = T.en;

const state = { dir: '', existing: null, exe: '' };
const pages = $('#pages');
const go = (p) => { pages.dataset.page = p; };
const tt = (el, v) => { el.textContent = v; };

// ---------------------------------------------------------------- art: the app icon, alive
// Same geometry as the icon (1024 grid): wavy progress ring around a scalloped ink "cookie" with a note in it.
const cv = $('#art');
const g = cv.getContext('2d');
const art = { target: 0.74, p: 0.74, mode: 'idle', pop: 0, check: 0, amp: 1 };
const C = 512, LOBES = 12, COOKIE_N = 9;
const col = { track: '#c4421d', wave: '#f7ecdc', cookie: '#1d1b1a' };

function cookiePath(R, amp, rot) {
  const p = new Path2D();
  for (let i = 0; i <= 240; i++) {
    const a = (i / 240) * Math.PI * 2;
    const r = R * (1 - amp + amp * Math.cos(COOKIE_N * (a - rot)));
    const x = C + r * Math.cos(a), y = C + r * Math.sin(a);
    i ? p.lineTo(x, y) : p.moveTo(x, y);
  }
  p.closePath();
  return p;
}

const NOTE_FLAG = new Path2D('M536 392 C 548 466 640 470 660 568 C 664 592 654 612 640 624 C 650 548 586 524 536 512 Z');

function frame(now) {
  requestAnimationFrame(frame);
  const dpr = devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
  if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  const k = (w - 34) / 850;
  g.translate(w / 2, h / 2); g.scale(k, k); g.translate(-C, -C);

  const t = now / 1000;
  art.p += (art.target - art.p) * 0.07;
  art.amp += ((art.mode === 'done' ? 0 : 1) - art.amp) * 0.06;
  art.pop *= 0.9;
  art.check += ((art.mode === 'done' ? 1 : 0) - art.check) * 0.09;
  if (art.mode === 'install') tt($('#pct'), Math.round(art.p * 100));

  // ring: track + wavy progress arc (+ dot at its end)
  g.lineCap = 'round'; g.lineJoin = 'round'; g.lineWidth = 44;
  g.strokeStyle = col.track;
  g.beginPath(); g.arc(C, C, 366, 0, Math.PI * 2); g.stroke();
  const a0 = -Math.PI / 2 - 0.1, span = Math.PI * 2 * Math.min(1, art.p);
  if (span > 0.02) {
    const A = 24 * art.amp, phase = t * 1.7, steps = Math.max(24, Math.ceil(span * 70));
    g.strokeStyle = col.wave; g.fillStyle = col.wave;
    g.beginPath();
    let ex = 0, ey = 0;
    for (let i = 0; i <= steps; i++) {
      const u = i / steps, a = a0 + span * u;
      const env = Math.min(1, u * 14, (1 - u) * 14 + 0.35);
      const r = 366 + Math.sin((a - a0) * LOBES - phase) * A * env;
      ex = C + Math.cos(a) * r; ey = C + Math.sin(a) * r;
      i ? g.lineTo(ex, ey) : g.moveTo(ex, ey);
    }
    g.stroke();
    g.beginPath(); g.arc(ex, ey, 46, 0, Math.PI * 2); g.fill();
  }

  // cookie: slowly turning and breathing while busy, pops when done
  const busy = art.mode === 'install';
  const scale = 1 + (busy ? Math.sin(t * 2.2) * 0.012 : 0) + art.pop;
  g.save();
  g.translate(C, C); g.scale(scale, scale); g.translate(-C, -C);
  g.fillStyle = col.cookie;
  g.fill(cookiePath(274, 0.055, -Math.PI / 2 + (busy ? t * 0.6 : 0)));

  // note fades out, check mark draws in
  g.fillStyle = col.wave;
  g.globalAlpha = 1 - art.check;
  g.save(); g.translate(0, -6);
  g.beginPath(); g.ellipse(462, 612, 72, 54, (-24 * Math.PI) / 180, 0, Math.PI * 2); g.fill();
  g.beginPath(); g.roundRect(520, 388, 32, 236, 16); g.fill();
  g.fill(NOTE_FLAG);
  g.restore();
  if (art.check > 0.02) {
    g.globalAlpha = 1; g.strokeStyle = col.wave; g.lineWidth = 46;
    const len = 520;
    g.setLineDash([len * art.check, len]);
    g.beginPath(); g.moveTo(376, 520); g.lineTo(470, 614); g.lineTo(654, 410); g.stroke();
    g.setLineDash([]);
  }
  g.restore();
}
requestAnimationFrame(frame);

// ---------------------------------------------------------------- flow
function applyText() {
  document.querySelectorAll('[data-t]').forEach((el) => { const v = L[el.dataset.t]; if (typeof v === 'string') el.textContent = v; });
  tt($('#installLbl'), state.existing ? L.update : L.install);
  tt($('#installLbl2'), state.existing ? L.update : L.install);
}

function options() {
  return { dir: state.dir, desktop: $('#optDesktop').checked, startmenu: $('#optStart').checked };
}

async function runInstall() {
  go('install');
  art.mode = 'install'; art.target = 0; art.p = Math.min(art.p, 0.02);
  tt($('#file'), '');
  const t0 = Date.now();
  const res = await setup.install(options());
  if (!res.ok) {
    art.mode = 'idle'; art.target = 0.74;
    const m = /^BUSY:(.*)$/.exec(res.error);
    tt($('#errText'), m ? L.busy(m[1]) : res.error);
    return go('error');
  }
  state.exe = res.exe;
  art.target = 1;
  while (art.p < 0.995 || Date.now() - t0 < 1200) await new Promise((r) => setTimeout(r, 60)); // let the ring visibly close
  tt($('#doneText'), L.doneText(res.dir));
  $('#launchNow').checked = $('#optLaunch').checked;
  art.mode = 'done'; art.pop = 0.14;
  go('done');
}

setup.onProgress(({ pct, label }) => { art.target = pct / 100; if (label && label.length > 1 && label !== 'done') tt($('#file'), label); });

$('#install').onclick = runInstall;
$('#install2').onclick = runInstall;
$('#retry').onclick = runInstall;
$('#customize').onclick = () => go('options');
$('#back').onclick = () => go('welcome');
$('#browse').onclick = async () => { const p = await setup.browse(state.dir); if (p) { state.dir = p; tt($('#dir'), p); } };
$('#readLicense').onclick = () => $('#license').classList.add('open');
$('#licClose').onclick = () => $('#license').classList.remove('open');
$('#finish').onclick = async () => { if ($('#launchNow').checked) await setup.launch(state.exe); setup.close(); };
$('#errClose').onclick = () => setup.close();
$('#wClose').onclick = () => setup.close();
$('#wMin').onclick = () => setup.minimize();
addEventListener('keydown', (e) => { if (e.key === 'Escape') $('#license').classList.remove('open'); });

(async () => {
  const info = await setup.info();
  L = /^(ru|uk|be)/i.test(info.locale) ? T.ru : T.en;
  state.dir = info.defaultDir;
  state.existing = info.existing;
  tt($('#dir'), state.dir);
  tt($('#ver'), `v${info.version}`);
  tt($('#licText'), info.license);
  applyText();
  if (info.existing) { const n = $('#existing'); n.hidden = false; tt(n, L.existing(info.existing.version)); }
})();
