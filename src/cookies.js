// Cookie import for yt-dlp. Browser extensions export JSON (Cookie-Editor style); yt-dlp wants the Netscape cookies.txt format.
// Only YouTube/Google cookies are kept — nothing else is needed, and nothing else should sit in our profile.

const KEEP = /(^|\.)(youtube\.com|google\.com)$/i;

function jsonToNetscape(list) {
  const lines = ['# Netscape HTTP Cookie File', '# Converted by Resonance — contains login session secrets, keep private', ''];
  let count = 0;
  for (const c of list) {
    const domain = String(c.domain || '');
    if (!c.name || !KEEP.test(domain.replace(/^\./, ''))) continue;
    const sub = c.hostOnly ? 'FALSE' : 'TRUE';
    const dom = c.hostOnly ? domain.replace(/^\./, '') : (domain.startsWith('.') ? domain : '.' + domain);
    const expiry = c.session || !c.expirationDate ? 0 : Math.floor(c.expirationDate);
    const value = String(c.value ?? '').replace(/[\r\n\t]/g, '');
    lines.push(`${c.httpOnly ? '#HttpOnly_' : ''}${dom}\t${sub}\t${c.path || '/'}\t${c.secure ? 'TRUE' : 'FALSE'}\t${expiry}\t${c.name}\t${value}`);
    count++;
  }
  return { text: lines.join('\n') + '\n', count };
}

// Accepts either the JSON export or an already-Netscape file. Returns { text, count } or throws a readable error.
function normalize(raw) {
  const s = raw.replace(/^﻿/, '').trim();
  if (s.startsWith('[') || s.startsWith('{')) {
    let data;
    try { data = JSON.parse(s); } catch { throw new Error('Файл похож на JSON, но не читается'); }
    const list = Array.isArray(data) ? data : data.cookies;
    if (!Array.isArray(list)) throw new Error('В JSON нет списка cookies');
    const out = jsonToNetscape(list);
    if (!out.count) throw new Error('В файле нет cookies youtube.com / google.com');
    return out;
  }
  const rows = s.split(/\r?\n/).filter((l) => l && (!l.startsWith('#') || l.startsWith('#HttpOnly_')) && l.split('\t').length >= 7);
  const kept = rows.filter((l) => KEEP.test(l.replace(/^#HttpOnly_/, '').split('\t')[0].replace(/^\./, '')));
  if (!kept.length) throw new Error('В файле нет cookies youtube.com / google.com');
  return { text: '# Netscape HTTP Cookie File\n' + kept.join('\n') + '\n', count: kept.length };
}

module.exports = { jsonToNetscape, normalize };
