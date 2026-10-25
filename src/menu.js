'use strict';
// Context menu + modal dialogs (confirm, text input, pick from a list). Pure UI: the caller decides what each item does.

let ctxEl = null;
const closeMenu = () => { if (ctxEl) { ctxEl.remove(); ctxEl = null; } };

function showMenu(items, x, y) {
  closeMenu();
  const el = document.createElement('div');
  el.className = 'ctxmenu';
  el.setAttribute('role', 'menu');
  for (const it of items) {
    if (it.sep) { el.append(document.createElement('hr')); continue; }
    const b = document.createElement('button');
    b.className = 'ctx-item' + (it.danger ? ' danger' : '');
    b.textContent = it.label;
    b.disabled = !!it.disabled;
    b.setAttribute('role', 'menuitem');
    b.onclick = () => { closeMenu(); it.run?.(); };
    el.append(b);
  }
  // keep focus where it was (a text field must still be focused when "Paste" runs)
  el.addEventListener('mousedown', (e) => e.preventDefault());
  document.body.append(el);
  ctxEl = el;
  const r = el.getBoundingClientRect();
  el.style.left = Math.max(8, Math.min(x, innerWidth - r.width - 8)) + 'px';
  el.style.top = Math.max(8, Math.min(y, innerHeight - r.height - 8)) + 'px';
}

document.addEventListener('mousedown', (e) => { if (ctxEl && !e.target.closest('.ctxmenu')) closeMenu(); }, true);
addEventListener('blur', closeMenu);
addEventListener('resize', closeMenu);
document.addEventListener('scroll', closeMenu, true);
document.addEventListener('keydown', (e) => {
  if (!ctxEl) return;
  if (e.key === 'Escape') { closeMenu(); return; }
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    const items = [...ctxEl.querySelectorAll('.ctx-item:not(:disabled)')];
    const i = items.indexOf(document.activeElement);
    items[(i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus();
  }
}, true);

// modal({ title, text, input: { value }, list: [{ label, sub }], ok, cancel, danger }) -> Promise<{ ok, value, index }>
function modal({ title, text = '', input = null, list = null, ok = 'OK', cancel = 'Отмена', danger = false }) {
  return new Promise((resolve) => {
    closeMenu();
    const wrap = document.createElement('div');
    wrap.className = 'modal';
    wrap.innerHTML = `<div class="dlg" role="dialog" aria-modal="true">
      <h3></h3><p class="dtext"></p>
      ${input ? '<input class="dinput" type="text" spellcheck="false" autocomplete="off">' : ''}
      ${list ? '<div class="dlist"></div>' : ''}
      <div class="dact"><button class="dbtn dcancel"></button><button class="dbtn ${danger ? 'ddanger' : 'dok'}"></button></div>
    </div>`;
    $('h3', wrap).textContent = title;
    const tx = $('.dtext', wrap);
    tx.textContent = text;
    tx.hidden = !text;
    const okBtn = $('.dact button:last-child', wrap), cancelBtn = $('.dcancel', wrap);
    okBtn.textContent = ok; cancelBtn.textContent = cancel;

    const inp = $('.dinput', wrap);
    if (inp) inp.value = input.value ?? '';
    let sel = -1;
    const valid = () => (inp ? inp.value.trim().length > 0 : true) && (list ? sel >= 0 : true);
    const sync = () => { okBtn.disabled = !valid(); };
    if (list) {
      const box = $('.dlist', wrap);
      list.forEach((it, i) => {
        const b = document.createElement('button');
        b.className = 'ditem';
        const l = document.createElement('b'); l.textContent = it.label;
        const s = document.createElement('span'); s.textContent = it.sub || '';
        b.append(l, s);
        b.onclick = () => { sel = i; $$('.ditem', box).forEach((x, k) => x.classList.toggle('on', k === i)); sync(); };
        b.ondblclick = () => { sel = i; finish(true); };
        box.append(b);
      });
    }
    inp?.addEventListener('input', sync);

    function finish(accepted) {
      if (accepted && !valid()) return;
      document.removeEventListener('keydown', onKey, true);
      wrap.classList.add('out');
      setTimeout(() => wrap.remove(), 180);
      resolve({ ok: accepted, value: inp ? inp.value.trim() : undefined, index: sel });
    }
    function onKey(e) {
      if (e.key === 'Escape') { e.stopPropagation(); finish(false); }
      else if (e.key === 'Enter' && !(document.activeElement && document.activeElement.matches('button:not(.dok):not(.ddanger)'))) { e.preventDefault(); finish(true); }
    }
    okBtn.onclick = () => finish(true);
    cancelBtn.onclick = () => finish(false);
    wrap.addEventListener('mousedown', (e) => { if (e.target === wrap) finish(false); });
    document.addEventListener('keydown', onKey, true);
    document.body.append(wrap);
    sync();
    requestAnimationFrame(() => { wrap.classList.add('in'); (inp || (danger ? cancelBtn : okBtn)).focus(); inp?.select(); });
  });
}
