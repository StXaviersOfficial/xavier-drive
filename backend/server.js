// XavierDrive Heavy Backend v2.1 — 32GB THINKING server (Pterodactyl, port 25570)
// Pure Node.js, ZERO dependencies.
//
// Auth: X-Backend-Key header on everything except /health.
//
// Storage architecture (owner spec 2026-09-24):
//   - PRIMARY storage = this server's 250GB disk (/home/container/storage/XavierDrive/...)
//   - MIRROR = Google Drive (worker handles the mirroring, same structure)
//   - Worker merges listings server+Drive, dedupes by path (server wins)
//   - If this server is down, worker fails over to Drive-only automatically
//
// Endpoints:
//   GET  /health                     no-auth liveness probe
//   POST /pdf | /render              PDF generation {title?, content|blocks}
//   GET  /files?path=/...            list directory (server disk)
//   GET  /files/tree                 full recursive tree JSON (for worker dedupe merge)
//   GET  /files/download?path=...    stream a file
//   PUT  /files/upload?path=...      raw body = file bytes (query ?path= full path)
//   POST /files/mkdir                {path}
//   POST /files/delete               {path}  (removes from server disk only; Drive-side
//                                            BIN move is handled by the worker)
//   POST /files/move                 {from, to}
//   POST /exec                       {command, agent?, timeoutMs?} — AI terminal:
//                                        runs in /home/container/ai/<agent>/ workspace,
//                                        everything logged to ai/<agent>/terminal.log
//   GET  /ai/sessions                list agent workspaces + log sizes
//   GET  /ai/log?agent=X&n=200       last N terminal log lines
//   POST /ai/search                  {query, agent?, max?} — AI web search engine
//                                        (research-before-answer, like GPT/Claude/Gemini)
//   POST /ai/fetch                   {url, agent?, maxChars?} — read any web page,
//                                        HTML stripped to clean text for the AI
//   POST /ai/research                {question|queries[], agent?, depth?} — full
//                                        research pipeline: multi-query search +
//                                        reads top sources + returns a research pack
//                                        the AI answers FROM (search refined first)
'use strict';
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const PORT = parseInt(process.env.SERVER_PORT || '25570', 10);
const KEY = process.env.BACKEND_KEY || '';
const ROOT = path.join(__dirname, 'storage', 'XavierDrive');   // mirrors Drive structure
const AI_ROOT = path.join(__dirname, 'ai');                    // per-agent workspaces

const LOG = (msg) => { const t = new Date().toISOString(); console.log(`[${t}] ${msg}`); try { fs.appendFileSync(__dirname + '/server.log', `[${t}] ${msg}\n`); } catch (e) {} };

// —— safety: resolve inside ROOT/AI_ROOT only ————————————
function safeResolve(root, rel) {
  const p = path.resolve(root, '.' + path.sep + (rel || '').replace(/^[/\\]+/, ''));
  if (p !== root && !p.startsWith(root + path.sep)) throw new Error('path escapes root');
  return p;
}
function ensureDir(p) { fs.mkdirSync(p, { recursive: true }); }

// —— PDF engine ————————————————————————————————————————
const W_HELV = [278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,334,584];
const W_HELVB = [278,333,474,556,556,889,722,238,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,333,333,584,584,584,611,975,722,722,722,722,667,611,778,722,278,556,722,611,833,722,778,667,778,722,667,611,722,667,944,667,667,611,333,278,333,584,556,333,556,611,556,611,556,333,611,611,278,278,556,278,889,611,611,611,611,389,556,333,611,556,778,556,556,500,389,280,389,584];
function charWidth(ch, font) {
  const c = ch.charCodeAt(0);
  if (c >= 32 && c <= 126) return (font === 'HB' ? W_HELVB : W_HELV)[c - 32];
  return 556;
}
function textWidth(s, font, size) { let w = 0; for (const ch of s) w += charWidth(ch, font); return w * size / 1000; }
function sanitizePdf(s) {
  const map = { '\u2014': '--', '\u2013': '-', '\u2018': "'", '\u2019': "'", '\u201C': '"', '\u201D': '"', '\u2022': '-', '\u2026': '...', '\u00A0': ' ', '\u2192': '->', '\u00D7': 'x', '\u00F7': '/', '\t': '    ' };
  let out = '';
  for (const ch of String(s)) out += (map[ch] !== undefined ? map[ch] : (ch.charCodeAt(0) > 126 ? '?' : ch));
  return out;
}
function esc(s) { return s.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)'); }
function wrapText(s, font, size, maxWidth) {
  const words = sanitizePdf(s).split(' ');
  const lines = []; let cur = '';
  for (let w of words) {
    while (textWidth(w, font, size) > maxWidth && w.length > 1) {
      let cut = w.length;
      while (cut > 1 && textWidth(w.slice(0, cut), font, size) > maxWidth) cut--;
      if (cur) { lines.push(cur); cur = ''; }
      lines.push(w.slice(0, cut)); w = w.slice(cut);
    }
    const cand = cur ? cur + ' ' + w : w;
    if (textWidth(cand, font, size) > maxWidth && cur) { lines.push(cur); cur = w; }
    else cur = cand;
  }
  if (cur) lines.push(cur);
  return lines.length ? lines : [''];
}
function buildPdf({ title = '', blocks = [] }) {
  const PW = 595.28, PH = 841.89, M = 64, CW = PW - M * 2;
  const pages = [];
  let pageLines = [];
  let y = PH - M;
  const newPage = () => { pages.push(pageLines); pageLines = []; y = PH - M; };
  const need = (h) => { if (y - h < M + 30) newPage(); };
  const put = (x, yy, font, size, text) => pageLines.push(`BT ${x.toFixed(2)} ${yy.toFixed(2)} Td /${font} ${size} Tf (${esc(text)}) Tj ET`);
  for (const b of blocks) {
    const type = (b.type || 'p').toLowerCase();
    const text = String(b.text || '');
    if (type === 'pagebreak') { newPage(); continue; }
    if (type === 'rule') { need(24); y -= 8; pageLines.push(`0.7 w ${M} ${y.toFixed(2)} m ${(PW - M)} ${y.toFixed(2)} l S`); y -= 16; continue; }
    if (type === 'spacer') { y -= 18; continue; }
    if (type === 'h1' || type === 'h2' || type === 'h3') {
      const size = type === 'h1' ? 19 : type === 'h2' ? 15 : 12.5;
      const lead = size * 1.35, before = type === 'h1' ? 14 : 12, after = 7;
      const lines = wrapText(text, 'HB', size, CW);
      need(before + lead * lines.length + after);
      y -= before;
      for (const ln of lines) { put(M, y - size, 'F2', size, ln); y -= lead; }
      y -= after;
      continue;
    }
    if (type === 'bullet' || type === 'number') {
      const size = 10.5, lead = 15.5, indent = 20;
      const lines = wrapText(text, 'F1', size, CW - indent);
      need(lead);
      put(M + 4, y - size, 'F2', size, '-');
      lines.forEach((ln) => { put(M + indent, y - size, 'F1', size, ln); y -= lead; });
      y += 3.5;
      continue;
    }
    const size = 10.5, lead = 15.5;
    const lines = wrapText(text, 'F1', size, CW);
    need(lead);
    for (const ln of lines) { put(M, y - size, 'F1', size, ln); y -= lead; }
    y -= 7;
  }
  pages.push(pageLines);
  if (title) {
    const tl = wrapText(title, 'HB', 21, CW);
    let ty = PH - M - 4;
    const head = [];
    for (const ln of tl) { head.push(`BT ${M.toFixed(2)} ${(ty - 21).toFixed(2)} Td /F2 21 Tf (${esc(ln)}) Tj ET`); ty -= 27; }
    ty -= 8;
    head.push(`0.7 w ${M.toFixed(2)} ${ty.toFixed(2)} m ${(PW - M).toFixed(2)} ${ty.toFixed(2)} l S`);
    const shift = (PH - M - 4) - ty;
    pages[0] = head.concat(pages[0].map((op) => op.replace(/^BT ([\d.]+) ([\d.]+) Td/, (m, x, yy) => `BT ${x} ${(parseFloat(yy) - shift).toFixed(2)} Td`)));
  }
  pages.forEach((pl, i) => { if (!(i === 0 && title)) pl.push(`BT ${(PW / 2 - 8).toFixed(2)} ${(M - 30).toFixed(2)} Td /F1 8.5 Tf (${i + 1}) Tj ET`); });

  const nPages = pages.length;
  const objs = [];
  const pageObjId = (i) => 7 + i * 2;
  const kids = [];
  for (let i = 0; i < nPages; i++) kids.push(`${pageObjId(i)} 0 R`);
  objs.push('<< /Type /Catalog /Pages 2 0 R >>');
  objs.push(`<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${nPages} >>`);
  objs.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>');
  objs.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>');
  objs.push('<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>');
  pages.forEach((pl, i) => {
    const stream = pl.join('\n');
    objs.push(`<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}\nendstream`);
    objs.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${PW} ${PH}] /Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents ${6 + i * 2} 0 R >>`);
  });
  let out = '%PDF-1.4\n%\xE2\xE3\xCF\xD3\n';
  const offsets = [0];
  objs.forEach((body, i) => { offsets.push(Buffer.byteLength(out, 'latin1')); out += `${i + 1} 0 obj\n${body}\nendobj\n`; });
  const xrefPos = Buffer.byteLength(out, 'latin1');
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
  for (let i = 1; i <= objs.length; i++) out += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
  out += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xrefPos}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}
function parseContent(content) {
  const blocks = [];
  for (const raw of String(content).split(/\r?\n/)) {
    const l = raw.trimEnd();
    if (!l.trim()) continue;
    if (/^(---+|\*\*\*+)$/.test(l.trim())) { blocks.push({ type: 'rule' }); continue; }
    let m;
    if ((m = l.match(/^####?\s+(.+)/))) { blocks.push({ type: 'h3', text: m[1] }); continue; }
    if ((m = l.match(/^##\s+(.+)/))) { blocks.push({ type: 'h2', text: m[1] }); continue; }
    if ((m = l.match(/^#\s+(.+)/))) { blocks.push({ type: 'h1', text: m[1] }); continue; }
    if ((m = l.match(/^\s*[-*]\s+(.+)/))) { blocks.push({ type: 'bullet', text: m[1] }); continue; }
    if ((m = l.match(/^\s*\d+[.)]\s+(.+)/))) { blocks.push({ type: 'number', text: m[1] }); continue; }
    blocks.push({ type: 'p', text: l });
  }
  return blocks;
}

// —— file tree ————————————————————————————————————————
function listDir(abs) {
  const out = [];
  let entries;
  try { entries = fs.readdirSync(abs, { withFileTypes: true }); } catch (e) { return out; }
  for (const e of entries) {
    if (e.name === '.gitkeep' || e.name.startsWith('.index')) continue;
    const full = path.join(abs, e.name);
    let st;
    try { st = fs.statSync(full); } catch (err) { continue; }
    out.push({ name: e.name, isDir: e.isDirectory(), size: st.size, mtime: st.mtime.toISOString() });
  }
  out.sort((a, b) => (b.isDir - a.isDir) || a.name.localeCompare(b.name));
  return out;
}
function buildTree(abs, rel, depth) {
  const node = { path: rel || '/', dirs: {}, files: [] };
  if (depth > 12) return node;
  let entries;
  try { entries = fs.readdirSync(abs, { withFileTypes: true }); } catch (e) { return node; }
  for (const e of entries) {
    if (e.name === '.gitkeep') continue;
    const childRel = (rel === '/' ? '' : rel) + '/' + e.name;
    const full = path.join(abs, e.name);
    if (e.isDirectory()) node.dirs[e.name] = buildTree(full, childRel, depth + 1);
    else {
      let st; try { st = fs.statSync(full); } catch (err) { continue; }
      node.files.push({ name: e.name, path: childRel, size: st.size, mtime: st.mtime.toISOString() });
    }
  }
  return node;
}
function dirSize(abs) {
  let total = 0;
  let entries;
  try { entries = fs.readdirSync(abs, { withFileTypes: true }); } catch (e) { return 0; }
  for (const e of entries) {
    const full = path.join(abs, e.name);
    if (e.isDirectory()) total += dirSize(full);
    else { try { total += fs.statSync(full).size; } catch (err) {} }
  }
  return total;
}

// —— AI terminal ————————————————————————————————————————
function agentLog(agent, line) {
  const safeAgent = String(agent || 'default').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 32) || 'default';
  const ws = path.join(AI_ROOT, safeAgent);
  ensureDir(ws);
  try { fs.appendFileSync(path.join(ws, 'terminal.log'), `[${new Date().toISOString()}] ${line}\n`); } catch (e) {}
  return safeAgent;
}
function runCommand(agent, command, timeoutMs) {
  return new Promise((resolve) => {
    const safeAgent = String(agent || 'default').replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 32) || 'default';
    const ws = path.join(AI_ROOT, safeAgent);
    ensureDir(ws);
    const logFile = path.join(ws, 'terminal.log');
    const stamp = new Date().toISOString();
    try { fs.appendFileSync(logFile, `\n[${stamp}] $ ${command}\n`); } catch (e) {}
    const child = spawn('/bin/bash', ['-c', command], { cwd: ws, env: { ...process.env, HOME: ws, TERM: 'dumb' }, timeout: timeoutMs || 20000 });
    let out = '', err = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    child.on('error', (e) => {
      try { fs.appendFileSync(logFile, `[spawn-error] ${e.message}\n`); } catch (x) {}
      resolve({ ok: false, code: -1, stdout: '', stderr: e.message });
    });
    child.on('close', (code) => {
      try { fs.appendFileSync(logFile, `${out}${err}[exit ${code}]\n`); } catch (e) {}
      resolve({ ok: code === 0, code, stdout: out.slice(0, 60000), stderr: err.slice(0, 20000) });
    });
  });
}

// —— AI web search / research engine (zero-dep) ————————————
const BROWSER_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'en-US,en;q=0.9',
};
function httpGet(url, opts, depth) {
  opts = opts || {};
  depth = depth || 0;
  return new Promise((resolve, reject) => {
    let u;
    try { u = new URL(url); } catch (e) { return reject(new Error('bad url')); }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return reject(new Error('only http(s)'));
    const mod = u.protocol === 'http:' ? http : https;
    const req = mod.get(url, { headers: { ...BROWSER_HEADERS, ...(opts.headers || {}) }, timeout: opts.timeoutMs || 15000 }, (res) => {
      const loc = res.headers.location;
      if (res.statusCode >= 300 && res.statusCode < 400 && loc && depth < 4) {
        res.resume();
        return resolve(httpGet(new URL(loc, url).toString(), opts, depth + 1));
      }
      const chunks = []; let size = 0;
      res.on('data', (c) => { size += c.length; if (size > (opts.maxBytes || 3 * 1024 * 1024)) { req.destroy(new Error('response too large')); } else chunks.push(c); });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
  });
}
function httpPostForm(url, form, opts) {
  opts = opts || {};
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const mod = u.protocol === 'http:' ? http : https;
    const body = Object.entries(form).map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&');
    const req = mod.request(url, {
      method: 'POST',
      headers: { ...BROWSER_HEADERS, 'Content-Type': 'application/x-www-form-urlencoded', ...(opts.headers || {}) },
      timeout: opts.timeoutMs || 15000,
    }, (res) => {
      const loc = res.headers.location;
      if (res.statusCode >= 300 && res.statusCode < 400 && loc) { res.resume(); return resolve(httpGet(new URL(loc, url).toString(), opts)); }
      const chunks = []; let size = 0;
      res.on('data', (c) => { size += c.length; if (size > (opts.maxBytes || 3 * 1024 * 1024)) { req.destroy(new Error('response too large')); } else chunks.push(c); });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    req.end(body);
  });
}
const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '-', mdash: '--', hellip: '...', rsquo: "'", lsquo: "'", ldquo: '"', rdquo: '"', bull: '-', middot: '-', copy: '(c)', reg: '(r)', trade: '(tm)' };
function decodeEntities(s) {
  return String(s).replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (m, e) => {
    if (e[0] === '#') {
      const code = (e[1] === 'x' || e[1] === 'X') ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(code) && code > 0 && code < 0x110000 ? String.fromCodePoint(code) : ' ';
    }
    return ENTITIES[e] !== undefined ? ENTITIES[e] : ' ';
  });
}
function stripTags(html) {
  return decodeEntities(String(html)
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(br|hr)\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|li|h[1-6]|tr|table|section|article|blockquote|pre)>/gi, '\n')
    .replace(/<[^>]+>/g, ' '))
    .replace(/[ \t]+/g, ' ')
    .replace(/ ?\n ?/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}
function pageTitle(html) {
  const m = String(html).match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  return m ? decodeEntities(m[1]).replace(/\s+/g, ' ').trim().slice(0, 200) : '';
}
function unwrapDdgHref(href) {
  const m = String(href).match(/[?&]uddg=([^&]+)/);
  if (m) { try { return decodeURIComponent(m[1]); } catch (e) { return href; } }
  return href.replace(/^\/\//, 'https://');
}
function parseDdg(html) {
  const results = [];
  const seen = new Set();
  const re = /<a[^>]*class="result-link"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
  let m;
  while ((m = re.exec(html)) !== null) {
    const url = unwrapDdgHref(decodeEntities(m[1]));
    const title = stripTags(m[2]).slice(0, 200);
    if (!url || !title || seen.has(url) || /^https?:\/\/duckduckgo\.com/.test(url)) continue;
    seen.add(url);
    results.push({ title, url, snippet: '' });
  }
  const sre = /<td[^>]*class="result-snippet"[^>]*>([\s\S]*?)<\/td>/g;
  let i = 0;
  while ((m = sre.exec(html)) !== null) {
    if (i < results.length) results[i].snippet = stripTags(m[1]).slice(0, 500);
    i++;
  }
  return results;
}
async function ddgSearch(query, max) {
  max = Math.min(Math.max(parseInt(max, 10) || 8, 1), 15);
  const q = String(query).slice(0, 400);
  let html = '';
  try { const r = await httpPostForm('https://lite.duckduckgo.com/lite/', { q: q, kl: 'wt-wt' }); if (r.status === 200 && /result-link/.test(r.body.toString('utf8'))) html = r.body.toString('utf8'); } catch (e) {}
  if (!html) { try { const r = await httpGet('https://html.duckduckgo.com/html/?q=' + encodeURIComponent(q)); if (r.status === 200) html = r.body.toString('utf8'); } catch (e) {} }
  if (!html) throw new Error('ddg unreachable');
  const results = parseDdg(html).slice(0, max);
  if (!results.length) throw new Error('ddg no results');
  return results;
}
function b64urlDecode(s) {
  s = String(s).replace(/-/g, '+').replace(/_/g, '/');
  s += '='.repeat(-s.length % 4);
  return Buffer.from(s, 'base64').toString('utf8');
}
function unwrapBingHref(href) {
  const m = String(href).match(/[?&]u=(a\d)?([A-Za-z0-9_-]+)/);
  if (m) {
    try {
      const url = b64urlDecode(m[2]);
      if (/^https?:\/\//.test(url)) return url;
    } catch (e) {}
  }
  return href;
}
function parseBing(html) {
  const results = [];
  const seen = new Set();
  const items = String(html).split(/<li class="b_algo"/).slice(1);
  for (const it of items) {
    const m = it.match(/<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/);
    if (!m) continue;
    let url = unwrapBingHref(decodeEntities(m[1].replace(/&amp;/g, '&')));
    if (/^https?:\/\/www\.bing\.com\/ck\/a/.test(m[1]) && url === m[1].replace(/&amp;/g, '&')) continue; // unwrap failed
    const title = stripTags(m[2]).slice(0, 200);
    const sm = it.match(/<p[^>]*>([\s\S]*?)<\/p>/);
    const snippet = sm ? stripTags(sm[1]).slice(0, 500) : '';
    if (!url || !title || seen.has(url) || !/^https?:\/\//.test(url) || /bing\.com\/(?:search|ck)/.test(url)) continue;
    seen.add(url);
    results.push({ title, url, snippet });
  }
  return results;
}
async function bingSearch(query, max) {
  const q = String(query).slice(0, 400);
  const r = await httpGet('https://www.bing.com/search?q=' + encodeURIComponent(q) + '&count=15&mkt=en-US&setlang=en', { timeoutMs: 15000, maxBytes: 3 * 1024 * 1024 });
  if (r.status !== 200) throw new Error('bing http ' + r.status);
  const results = parseBing(r.body.toString('utf8')).slice(0, max);
  if (!results.length) throw new Error('bing no results');
  return results;
}
// engine chain: DDG lite -> DDG html -> Bing (first that returns results wins)
async function webSearch(query, max) {
  const errors = [];
  for (const engine of [ddgSearch, bingSearch]) {
    try {
      const results = await engine(query, max);
      if (results.length) return results;
    } catch (e) { errors.push(e.message); }
  }
  throw new Error('all engines failed: ' + errors.join(' | '));
}
async function fetchPageText(url, maxChars) {
  maxChars = Math.min(parseInt(maxChars, 10) || 6000, 40000);
  const r = await httpGet(url, { timeoutMs: 15000, maxBytes: 3 * 1024 * 1024 });
  const ctype = (r.headers['content-type'] || '').toString();
  const body = r.body.toString('utf8');
  if (/json/.test(ctype)) return { status: r.status, title: '', text: body.slice(0, maxChars) };
  const text = stripTags(body);
  return { status: r.status, title: pageTitle(body), text: text.slice(0, maxChars) };
}
async function researchPack(queries, depth, agent) {
  depth = Math.min(Math.max(parseInt(depth, 10) || 2, 0), 4);   // pages read per query
  const perQuery = [];
  const allResults = [];
  for (const q of queries) {
    try {
      const results = await webSearch(q, 8);
      agentLog(agent, `search: ${q} -> ${results.length} results`);
      perQuery.push({ query: q, results });
      allResults.push(...results.map((x) => ({ ...x, query: q })));
    } catch (e) {
      perQuery.push({ query: q, error: e.message, results: [] });
      agentLog(agent, `search FAILED: ${q} (${e.message})`);
    }
  }
  // pick top sources to actually READ (dedupe by url, respect depth per query)
  const seenUrl = new Set();
  const toRead = [];
  for (const q of perQuery) {
    let n = 0;
    for (const r of q.results) {
      if (n >= depth || seenUrl.has(r.url)) continue;
      if (!/^https?:\/\//.test(r.url)) continue;
      seenUrl.add(r.url); toRead.push(r); n++;
    }
  }
  const sources = [];
  await Promise.all(toRead.slice(0, 8).map(async (r) => {
    try {
      const p = await fetchPageText(r.url, 6000);
      sources.push({ url: r.url, title: p.title || r.title, status: p.status, extract: p.text });
      agentLog(agent, `read: ${r.url} (${p.text.length} chars)`);
    } catch (e) {
      sources.push({ url: r.url, title: r.title, error: e.message });
      agentLog(agent, `read FAILED: ${r.url} (${e.message})`);
    }
  }));
  sources.sort((a, b) => (toRead.findIndex((x) => x.url === a.url) - toRead.findIndex((x) => x.url === b.url)));
  return { queries: perQuery, sources };
}

// —— HTTP ————————————————————————————————————————
function readBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', (c) => { size += c.length; if (size > maxBytes) { reject(new Error('body too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://localhost');
  const p = u.pathname;
  const send = (code, body, headers) => { res.writeHead(code, headers || { 'Content-Type': 'application/json' }); res.end(body); LOG(`${req.method} ${p}${u.search || ''} -> ${code}`); };
  try {
    if (p === '/health') {
      let diskFree = null;
      try { const s = fs.statfsSync ? fs.statfsSync(ROOT) : null; if (s) diskFree = s.bsize * s.bavail; } catch (e) {}
      return send(200, JSON.stringify({
        ok: true, uptime: Math.round(process.uptime()), rssMB: Math.round(process.memoryUsage().rss / 1048576),
        node: process.version, server: 'xavierdrive-backend', version: '2.1.0',
        storage: { root: '/storage/XavierDrive', usedBytes: dirSize(ROOT), files: buildTree(ROOT, '/', 0) ? countFiles(buildTree(ROOT, '/', 0)) : 0 },
      }));
    }
    if (req.headers['x-backend-key'] !== KEY) return send(401, JSON.stringify({ error: 'bad or missing X-Backend-Key' }));

    // PDF
    if (req.method === 'POST' && (p === '/pdf' || p === '/render')) {
      const raw = await readBody(req, 20 * 1024 * 1024);
      let body; try { body = JSON.parse(raw.toString('utf8')); } catch (e) { return send(400, JSON.stringify({ error: 'invalid JSON: ' + e.message })); }
      const t0 = Date.now();
      const blocks = Array.isArray(body.blocks) && body.blocks.length ? body.blocks : parseContent(body.content || body.text || '');
      if (!blocks.length) return send(400, JSON.stringify({ error: 'no content (send {content:"..."} or {blocks:[...]})' }));
      const pdf = buildPdf({ title: body.title || 'XavierDrive Document', blocks });
      LOG(`PDF built: ${blocks.length} blocks -> ${pdf.length}B in ${Date.now() - t0}ms`);
      return send(200, pdf, { 'Content-Type': 'application/pdf', 'Content-Disposition': 'inline; filename="xavierdrive.pdf"', 'X-Backend-Gen-Ms': String(Date.now() - t0) });
    }

    // FILES
    if (p === '/files' && req.method === 'GET') {
      const dir = safeResolve(ROOT, u.searchParams.get('path') || '/');
      if (!fs.existsSync(dir)) return send(404, JSON.stringify({ error: 'not found' }));
      return send(200, JSON.stringify({ path: u.searchParams.get('path') || '/', entries: listDir(dir) }));
    }
    if (p === '/files/tree' && req.method === 'GET') {
      return send(200, JSON.stringify({ tree: buildTree(ROOT, '/', 0), usedBytes: dirSize(ROOT) }));
    }
    if (p === '/files/download' && req.method === 'GET') {
      const f = safeResolve(ROOT, u.searchParams.get('path') || '');
      let st; try { st = fs.statSync(f); } catch (e) { return send(404, JSON.stringify({ error: 'not found' })); }
      if (st.isDirectory()) return send(400, JSON.stringify({ error: 'is a directory' }));
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': st.size, 'Content-Disposition': `attachment; filename="${encodeURIComponent(path.basename(f))}"` });
      fs.createReadStream(f).pipe(res);
      LOG(`GET /files/download ${u.searchParams.get('path')} -> 200 (${st.size}B)`);
      return;
    }
    if (p === '/files/upload' && req.method === 'PUT') {
      const raw = await readBody(req, 250 * 1024 * 1024);
      const rel = u.searchParams.get('path') || '';
      if (!rel || rel === '/') return send(400, JSON.stringify({ error: 'path query param required (full path incl. filename)' }));
      const f = safeResolve(ROOT, rel);
      ensureDir(path.dirname(f));
      fs.writeFileSync(f, raw);
      return send(200, JSON.stringify({ ok: true, path: rel, size: raw.length }));
    }
    if (p === '/files/mkdir' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req, 10240)).toString('utf8'));
      const d = safeResolve(ROOT, body.path || '');
      ensureDir(d);
      return send(200, JSON.stringify({ ok: true, path: body.path }));
    }
    if (p === '/files/delete' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req, 10240)).toString('utf8'));
      const f = safeResolve(ROOT, body.path || '');
      if (!fs.existsSync(f)) return send(404, JSON.stringify({ error: 'not found' }));
      const st = fs.statSync(f);
      if (st.isDirectory()) fs.rmSync(f, { recursive: true });
      else fs.unlinkSync(f);
      // prune empty parent dirs up to ROOT
      let dir = path.dirname(f);
      while (dir.startsWith(ROOT + path.sep)) {
        try { const left = fs.readdirSync(dir); if (left.length) break; fs.rmdirSync(dir); } catch (e) { break; }
        dir = path.dirname(dir);
      }
      return send(200, JSON.stringify({ ok: true, deleted: body.path, note: 'server copy removed; worker moves the Drive copy to BIN' }));
    }
    if (p === '/files/move' && req.method === 'POST') {
      const body = JSON.parse((await readBody(req, 10240)).toString('utf8'));
      const from = safeResolve(ROOT, body.from || '');
      const to = safeResolve(ROOT, body.to || '');
      if (!fs.existsSync(from)) return send(404, JSON.stringify({ error: 'not found' }));
      ensureDir(path.dirname(to));
      fs.renameSync(from, to);
      return send(200, JSON.stringify({ ok: true, from: body.from, to: body.to }));
    }

    // AI TERMINAL
    if (p === '/exec' && req.method === 'POST') {
      const raw = await readBody(req, 1024 * 1024);
      let body; try { body = JSON.parse(raw.toString('utf8')); } catch (e) { return send(400, JSON.stringify({ error: 'invalid JSON' })); }
      if (!body.command) return send(400, JSON.stringify({ error: 'command required' }));
      const r = await runCommand(body.agent, body.command, body.timeoutMs);
      return send(r.ok ? 200 : 500, JSON.stringify(r));
    }
    if (p === '/ai/sessions' && req.method === 'GET') {
      ensureDir(AI_ROOT);
      const agents = fs.readdirSync(AI_ROOT, { withFileTypes: true }).filter((e) => e.isDirectory()).map((e) => {
        const logF = path.join(AI_ROOT, e.name, 'terminal.log');
        let lines = 0, size = 0;
        try { size = fs.statSync(logF).size; lines = fs.readFileSync(logF, 'utf8').split('\n').length; } catch (x) {}
        return { agent: e.name, logSize: size, logLines: lines };
      });
      return send(200, JSON.stringify({ agents }));
    }
    if (p === '/ai/log' && req.method === 'GET') {
      const agent = String(u.searchParams.get('agent') || 'default').replace(/[^a-zA-Z0-9_-]/g, '');
      const n = Math.min(parseInt(u.searchParams.get('n') || '200', 10) || 200, 2000);
      const logF = path.join(AI_ROOT, agent, 'terminal.log');
      if (!fs.existsSync(logF)) return send(404, JSON.stringify({ error: 'no log for agent' }));
      const lines = fs.readFileSync(logF, 'utf8').split('\n');
      return send(200, JSON.stringify({ agent, lines: lines.slice(-n) }));
    }

    // AI SEARCH ENGINE (research before answer)
    if (p === '/ai/search' && req.method === 'POST') {
      const raw = await readBody(req, 1024 * 1024);
      let body; try { body = JSON.parse(raw.toString('utf8')); } catch (e) { return send(400, JSON.stringify({ error: 'invalid JSON' })); }
      if (!body.query) return send(400, JSON.stringify({ error: 'query required' }));
      const agent = agentLog(body.agent, `search: ${String(body.query).slice(0, 200)}`);
      const t0 = Date.now();
      try {
        const results = await webSearch(body.query, body.max);
        LOG(`AI search "${String(body.query).slice(0, 80)}" -> ${results.length} results in ${Date.now() - t0}ms`);
        return send(200, JSON.stringify({ ok: true, query: body.query, tookMs: Date.now() - t0, results }));
      } catch (e) {
        LOG(`AI search FAILED: ${e.message}`);
        return send(502, JSON.stringify({ ok: false, error: e.message }));
      }
    }
    if (p === '/ai/fetch' && req.method === 'POST') {
      const raw = await readBody(req, 1024 * 1024);
      let body; try { body = JSON.parse(raw.toString('utf8')); } catch (e) { return send(400, JSON.stringify({ error: 'invalid JSON' })); }
      if (!body.url) return send(400, JSON.stringify({ error: 'url required' }));
      const agent = agentLog(body.agent, `fetch: ${String(body.url).slice(0, 300)}`);
      const t0 = Date.now();
      try {
        const page = await fetchPageText(body.url, body.maxChars);
        LOG(`AI fetch ${String(body.url).slice(0, 120)} -> ${page.status} (${page.text.length} chars) in ${Date.now() - t0}ms`);
        return send(200, JSON.stringify({ ok: true, url: body.url, ...page, tookMs: Date.now() - t0 }));
      } catch (e) {
        LOG(`AI fetch FAILED: ${e.message}`);
        return send(502, JSON.stringify({ ok: false, error: e.message }));
      }
    }
    if (p === '/ai/research' && req.method === 'POST') {
      const raw = await readBody(req, 4 * 1024 * 1024);
      let body; try { body = JSON.parse(raw.toString('utf8')); } catch (e) { return send(400, JSON.stringify({ error: 'invalid JSON' })); }
      const queries = Array.isArray(body.queries) ? body.queries.map(String).filter(Boolean).slice(0, 5)
                    : body.question ? [String(body.question)] : [];
      if (!queries.length) return send(400, JSON.stringify({ error: 'question or queries[] required' }));
      const agent = agentLog(body.agent, `research: ${queries.map((q) => q.slice(0, 100)).join(' | ')}`);
      const t0 = Date.now();
      try {
        const pack = await researchPack(queries, body.depth, agent);
        LOG(`AI research (${queries.length} queries, ${pack.sources.length} sources read) in ${Date.now() - t0}ms`);
        return send(200, JSON.stringify({ ok: true, question: body.question || queries[0], tookMs: Date.now() - t0, ...pack }));
      } catch (e) {
        LOG(`AI research FAILED: ${e.message}`);
        return send(500, JSON.stringify({ ok: false, error: e.message }));
      }
    }

    return send(404, JSON.stringify({ error: 'not found', endpoints: ['GET /health', 'POST /pdf', 'GET /files', 'GET /files/tree', 'GET /files/download', 'PUT /files/upload', 'POST /files/mkdir', 'POST /files/delete', 'POST /files/move', 'POST /exec', 'GET /ai/sessions', 'GET /ai/log', 'POST /ai/search', 'POST /ai/fetch', 'POST /ai/research'] }));
  } catch (e) {
    LOG(`ERROR ${req.method} ${p}: ${e.stack}`);
    return send(500, JSON.stringify({ error: e.message }));
  }
});

function countFiles(node) {
  let n = node.files.length;
  for (const k of Object.keys(node.dirs || {})) n += countFiles(node.dirs[k]);
  return n;
}

ensureDir(ROOT);
ensureDir(AI_ROOT);
server.listen(PORT, '0.0.0.0', () => LOG(`XavierDrive backend v2.1.0 on 0.0.0.0:${PORT} (node ${process.version}, pid ${process.pid}) storage=${ROOT} [AI: terminal + search + research]`));
process.on('uncaughtException', (e) => LOG(`uncaught: ${e.stack}`));
process.on('unhandledRejection', (e) => LOG(`unhandled: ${e}`));
