// ============================================================
//  XavierDrive — Cloudflare Worker (OAuth + Drive Proxy + AI Routing)
//  Deploy at: stxaviers-auth.quackeditzofficial.workers.dev
//
//  Required environment variables (set in Worker Settings → Secrets):
//    GOOGLE_CLIENT_ID        — OAuth 2.0 client ID
//    GOOGLE_CLIENT_SECRET    — OAuth 2.0 client secret
//    REDIRECT_URI            — https://stxaviers-auth.quackeditzofficial.workers.dev/callback
//    FRONTEND_URL            — https://stxaviers.pages.dev
//    GROQ_KEY                — Groq API key
//    SESSION_SECRET          — random string for signing session cookies
//    DRIVE_TOKEN_JSON        — JSON of owner's Drive token
//    KV_SESSIONS             — KV namespace binding
//
//  NEW (add these as Secrets):
//    GEMINI_KEY_1..5         — 5 Gemini API keys (rotated)
//    FIREBASE_DB_URL         — https://stxaviers-official-default-rtdb.firebaseio.com
//    STUDENT_GEMINI_LIMIT    — "30" (text variable)
// ============================================================

const SCOPES = 'openid email profile';

// —— Utilities ————————————————————————————————————

function randomState() {
  const arr = new Uint8Array(16);
  crypto.getRandomValues(arr);
  return [...arr].map(b => b.toString(16).padStart(2, '0')).join('');
}

async function hmacSign(secret, data) {
  const key = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(data));
  return btoa(String.fromCharCode(...new Uint8Array(sig)));
}

async function makeSessionId(secret) {
  const id = randomState() + randomState();
  const sig = await hmacSign(secret, id);
  return `${id}.${sig.replace(/[+/=]/g, c => ({ '+': '-', '/': '_', '=': '' }[c]))}`;
}

function parseCookies(header) {
  const out = {};
  if (!header) return out;
  for (const part of header.split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k) out[k.trim()] = decodeURIComponent(v.join('='));
  }
  return out;
}

function sessionCookie(name, value, maxAge) {
  // Hardened: Added SameSite=Lax for CSRF protection on top-tier navigation,
  // kept HttpOnly + Secure. Note: SameSite=None requires Secure which we have,
  // but Lax is safer when the cookie is only used by same-site XHRs.
  // We keep SameSite=None here because the worker is on a different origin
  // (workers.dev) than the frontend (pages.dev) — different subdomains
  // count as cross-site for cookie purposes.
  const age = maxAge !== undefined ? `; Max-Age=${maxAge}` : '';
  return `${name}=${value}; Path=/; HttpOnly; SameSite=None; Secure${age}`;
}

// Allowed origins — production frontend + localhost dev only.
// Used for BOTH CORS and CSRF validation.
const ALLOWED_ORIGINS = [
  'https://stxaviers.pages.dev',        // Production frontend
  'http://localhost:3000',              // Next.js dev
  'http://127.0.0.1:3000',
  'http://localhost:5500',              // VS Code Live Server
  'http://localhost:8080',
  'http://127.0.0.1:5500',
  'http://127.0.0.1:8080',
];

function isAllowedOrigin(origin) {
  if (!origin) return false;
  return ALLOWED_ORIGINS.some(a => origin === a || origin === a + '/');
}

function corsHeaders(origin) {
  const ao = isAllowedOrigin(origin) ? origin : 'https://stxaviers.pages.dev';
  return {
    'Access-Control-Allow-Origin': ao,
    'Access-Control-Allow-Credentials': 'true',
    'Access-Control-Allow-Methods': 'GET, POST, PATCH, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    'Vary': 'Origin',
  };
}

// CSRF defense — reject state-changing requests from disallowed origins.
// With SameSite=None cookies (required for cross-origin worker), any site can
// send a cookie-bearing POST. CORS only blocks reading the response, not the
// request itself. This function validates the Origin header on mutations.
function csrfCheck(request, origin) {
  // OPTIONS is always allowed (preflight)
  // GET is idempotent — no CSRF risk
  // POST/PUT/PATCH/DELETE must come from an allowed origin
  const method = (request.method || 'GET').toUpperCase();
  if (method === 'GET' || method === 'HEAD' || method === 'OPTIONS') {
    return null; // allowed
  }
  if (isAllowedOrigin(origin)) {
    return null; // allowed
  }
  // Reject — log and return 403
  console.warn('CSRF blocked:', method, request.url, 'origin=', origin);
  return new Response(JSON.stringify({ error: 'Forbidden origin' }), {
    status: 403,
    headers: { 'Content-Type': 'application/json' },
  });
}

function json(data, status = 200, origin = '') {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...corsHeaders(origin) },
  });
}

// —— Drive token management ——————————————————————

async function getOwnerToken(env) {
  // Defensive: KV_SESSIONS binding may be missing on some deployments.
  // Fall back to reading DRIVE_TOKEN_JSON directly each time (slower but works).
  const kv = env.KV_SESSIONS;
  let stored = null;
  if (kv && typeof kv.get === 'function') {
    try { stored = await kv.get('__owner_token__'); } catch (e) { console.warn('KV get failed:', e.message); }
  }
  let tok;
  if (stored) {
    tok = JSON.parse(stored);
  } else {
    tok = JSON.parse(env.DRIVE_TOKEN_JSON || '{}');
  }

  if (!tok.access_token || (tok.expiry && Date.now() > tok.expiry - 300000)) {
    if (!tok.refresh_token) throw new Error('No owner refresh token available. Set DRIVE_TOKEN_JSON secret with a JSON object containing refresh_token.');
    const r = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: env.GOOGLE_CLIENT_ID,
        client_secret: env.GOOGLE_CLIENT_SECRET,
        refresh_token: tok.refresh_token,
        grant_type: 'refresh_token',
      }),
    });
    const fresh = await r.json();
    if (!fresh.access_token) throw new Error('Owner token refresh failed: ' + JSON.stringify(fresh));
    tok.access_token = fresh.access_token;
    tok.expiry = Date.now() + (fresh.expires_in || 3600) * 1000;
    if (kv && typeof kv.put === 'function') {
      try { await kv.put('__owner_token__', JSON.stringify(tok), { expirationTtl: 86400 }); } catch (e) { console.warn('KV put failed:', e.message); }
    }
  }
  return tok.access_token;
}

// —— Session helpers ——————————————————————————————

async function getSession(env, cookies) {
  const sid = cookies['xd_sid'];
  if (!sid) return null;
  if (!env.KV_SESSIONS || typeof env.KV_SESSIONS.get !== 'function') return null;
  try {
    const data = await env.KV_SESSIONS.get('sess_' + sid);
    if (!data) return null;
    return JSON.parse(data);
  } catch (e) { console.warn('getSession KV err:', e.message); return null; }
}

async function setSession(env, sid, data) {
  if (!env.KV_SESSIONS || typeof env.KV_SESSIONS.put !== 'function') {
    console.warn('KV_SESSIONS not bound — cannot persist session');
    return;
  }
  try {
    await env.KV_SESSIONS.put('sess_' + sid, JSON.stringify(data), { expirationTtl: 86400 * 7 });
  } catch (e) { console.warn('setSession KV err:', e.message); }
}

async function deleteSession(env, sid) {
  if (!env.KV_SESSIONS || typeof env.KV_SESSIONS.delete !== 'function') return;
  try { await env.KV_SESSIONS.delete('sess_' + sid); } catch (e) {}
}

// —— Auth + session route handlers ——————————————————

async function handleLogin(env, origin) {
  const state = randomState();
  const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  url.searchParams.set('client_id', env.GOOGLE_CLIENT_ID);
  url.searchParams.set('redirect_uri', env.REDIRECT_URI);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('scope', SCOPES);
  url.searchParams.set('access_type', 'online');
  url.searchParams.set('state', state);
  url.searchParams.set('prompt', 'select_account');

  const headers = new Headers({ Location: url.toString(), ...corsHeaders(origin) });
  headers.append('Set-Cookie', sessionCookie('xd_state', state, 600));
  return new Response(null, { status: 302, headers });
}

async function handleCallback(request, env) {
  const url = new URL(request.url);
  const code = url.searchParams.get('code');
  const state = url.searchParams.get('state');
  const cookies = parseCookies(request.headers.get('Cookie'));
  const FRONTEND = env.FRONTEND_URL || 'https://stxaviers.pages.dev';

  if (!code || state !== cookies['xd_state']) {
    return Response.redirect(FRONTEND + '?auth=error', 302);
  }

  const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      code,
      client_id: env.GOOGLE_CLIENT_ID,
      client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: env.REDIRECT_URI,
      grant_type: 'authorization_code',
    }),
  });
  const tokens = await tokenRes.json();
  if (!tokens.access_token) {
    return Response.redirect(FRONTEND + '?auth=error', 302);
  }

  const uRes = await fetch('https://www.googleapis.com/oauth2/v2/userinfo', {
    headers: { Authorization: `Bearer ${tokens.access_token}` },
  });
  const user = await uRes.json();

  const sid = await makeSessionId(env.SESSION_SECRET || 'fallback-secret');
  await setSession(env, sid, {
    user: { email: user.email, name: user.name, picture: user.picture },
    access_token: tokens.access_token,
    created: Date.now(),
  });

  const headers = new Headers({ Location: FRONTEND + '/' });
  headers.append('Set-Cookie', sessionCookie('xd_state', '', 0));
  headers.append('Set-Cookie', sessionCookie('xd_sid', sid, 86400 * 7));
  return new Response(null, { status: 302, headers });
}

async function handleMe(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);
  // Also return role info from verifyRole (includes hardcoded admin/developer checks)
  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  return json({ 
    user: sess.user,
    role: roleInfo.role,
    isAdmin: roleInfo.isAdmin,
    isDeveloper: roleInfo.isDeveloper || false
  }, 200, origin);
}

async function handleToken(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);
  return json({ access_token: sess.access_token }, 200, origin);
}

async function handleLogout(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sid = cookies['xd_sid'];
  if (sid) await deleteSession(env, sid);
  const headers = new Headers({ 'Content-Type': 'application/json', ...corsHeaders(origin) });
  headers.append('Set-Cookie', sessionCookie('xd_sid', '', 0));
  return new Response(JSON.stringify({ ok: true }), { status: 200, headers });
}

async function handleConfig(env, origin) {
  // No longer return groqKey to the client — it's a secret now
  return json({
    clientId: env.GOOGLE_CLIENT_ID,
  }, 200, origin);
}

// —— Drive proxy ——————————————————————————————————

async function handleDrive(request, env, origin, path) {
  const subPath = path.replace(/^\/drive/, '');
  let ownerToken;
  try {
    ownerToken = await getOwnerToken(env);
  } catch (e) {
    return json({ error: 'Owner token error: ' + e.message }, 500, origin);
  }

  const driveBase = 'https://www.googleapis.com/drive/v3';
  const uploadBase = 'https://www.googleapis.com/upload/drive/v3';
  const headers = { Authorization: `Bearer ${ownerToken}` };

  // GET /drive/files — list/search files
  if (subPath === '/files' && request.method === 'GET') {
    const url = new URL(request.url);
    const q = url.searchParams.get('q') || '';
    const fields = url.searchParams.get('fields') || 'files(id,name,mimeType,size,modifiedTime,description)';
    const orderBy = url.searchParams.get('orderBy') || 'modifiedTime desc';
    const pageSize = url.searchParams.get('pageSize') || '100';
    const gUrl = new URL(driveBase + '/files');
    if (q) gUrl.searchParams.set('q', q);
    gUrl.searchParams.set('fields', fields);
    gUrl.searchParams.set('orderBy', orderBy);
    gUrl.searchParams.set('pageSize', pageSize);
    const r = await fetch(gUrl.toString(), { headers });
    const d = await r.json();
    return json(d, r.status, origin);
  }

  // PATCH /drive/files/:id — overwrite file content (for saveTT etc.)
  const patchMatch = subPath.match(/^\/files\/([^/]+)$/);
  if (patchMatch && request.method === 'PATCH') {
    const fileId = patchMatch[1];
    const body = await request.text();
    const r = await fetch(`${uploadBase}/files/${fileId}?uploadType=media&fields=id,name`, {
      method: 'PATCH',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body,
    });
    const d = await r.json();
    return json(d, r.status, origin);
  }

  // POST /drive/files — create file with metadata
  if (subPath === '/files' && request.method === 'POST') {
    const body = await request.json();
    const r = await fetch(driveBase + '/files?fields=id,name,webViewLink', {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const d = await r.json();
    return json(d, r.status, origin);
  }

  // POST /drive/mkdir — create folder
  if (subPath === '/mkdir' && request.method === 'POST') {
    const body = await request.json();
    const meta = { name: body.name, mimeType: 'application/vnd.google-apps.folder' };
    if (body.parents && body.parents.length) meta.parents = body.parents;
    const r = await fetch(driveBase + '/files?fields=id,name,webViewLink', {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify(meta),
    });
    const d = await r.json();
    return json(d, r.status, origin);
  }

  // POST /drive/mkpub — make file publicly readable
  if (subPath === '/mkpub' && request.method === 'POST') {
    const body = await request.json();
    const r = await fetch(`${driveBase}/files/${body.id}/permissions`, {
      method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: 'reader', type: 'anyone' }),
    });
    return json({ ok: r.ok }, r.status, origin);
  }

  // DELETE /drive/delete — delete a file
  if (subPath === '/delete' && request.method === 'DELETE') {
    const url = new URL(request.url);
    const id = url.searchParams.get('id');
    if (!id) return json({ error: 'No id' }, 400, origin);
    const r = await fetch(`${driveBase}/files/${id}`, { method: 'DELETE', headers });
    return json({ ok: r.status === 204 }, r.status === 204 ? 200 : r.status, origin);
  }

  // POST /drive/upload — multipart upload
  if (subPath === '/upload' && request.method === 'POST') {
    const formData = await request.formData();
    const metaBlob = formData.get('metadata');
    const fileBlob = formData.get('file');
    if (!metaBlob || !fileBlob) return json({ error: 'Missing metadata or file' }, 400, origin);

    const metaText = typeof metaBlob === 'string' ? metaBlob : await metaBlob.text();
    const fileBytes = await fileBlob.arrayBuffer();
    const fileName = fileBlob.name || 'upload';
    const mimeType = fileBlob.type || 'application/octet-stream';

    const boundary = '-------XavierDriveUpload' + Date.now();
    const metaPart = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${metaText}\r\n`;
    const filePart = `--${boundary}\r\nContent-Type: ${mimeType}\r\nContent-Disposition: form-data; name="file"; filename="${fileName}"\r\n\r\n`;
    const endPart = `\r\n--${boundary}--`;

    const enc = new TextEncoder();
    const metaBytes = enc.encode(metaPart);
    const filePartBytes = enc.encode(filePart);
    const endBytes = enc.encode(endPart);

    const body = new Uint8Array(metaBytes.length + filePartBytes.length + fileBytes.byteLength + endBytes.length);
    body.set(metaBytes, 0);
    body.set(filePartBytes, metaBytes.length);
    body.set(new Uint8Array(fileBytes), metaBytes.length + filePartBytes.length);
    body.set(endBytes, metaBytes.length + filePartBytes.length + fileBytes.byteLength);

    const r = await fetch(`${uploadBase}/files?uploadType=multipart&fields=id,name,webViewLink`, {
      method: 'POST',
      headers: { ...headers, 'Content-Type': `multipart/related; boundary=${boundary}` },
      body,
    });
    const d = await r.json();
    return json(d, r.status, origin);
  }

  // GET /drive/download OR /drive/media — stream file content (both aliases)
  if ((subPath === '/download' || subPath === '/media') && request.method === 'GET') {
    const url = new URL(request.url);
    const id = url.searchParams.get('id');
    if (!id) return json({ error: 'No id' }, 400, origin);
    // NEW: Support Google Apps file export (?export=text/plain)
    const exportMime = url.searchParams.get('export');
    let fetchUrl;
    if (exportMime) {
      // Export Google Docs/Sheets/Slides to a different format
      fetchUrl = `${driveBase}/files/${id}/export?mimeType=${encodeURIComponent(exportMime)}`;
    } else {
      fetchUrl = `${driveBase}/files/${id}?alt=media`;
    }
    const r = await fetch(fetchUrl, { headers });
    const resHeaders = new Headers(corsHeaders(origin));
    resHeaders.set('Content-Type', r.headers.get('Content-Type') || 'application/octet-stream');
    const cd = r.headers.get('Content-Disposition');
    if (cd) resHeaders.set('Content-Disposition', cd);
    return new Response(r.body, { status: r.status, headers: resHeaders });
  }

  return json({ error: 'Unknown drive route: ' + subPath }, 404, origin);
}

// ============================================================
//  AI ROUTING — Groq-first with Gemini escalation
// ============================================================

// —— Gemini key rotation ——————————————————————————

function getGeminiKey(env) {
  const keys = [env.GEMINI_KEY_1, env.GEMINI_KEY_2, env.GEMINI_KEY_3, env.GEMINI_KEY_4, env.GEMINI_KEY_5].filter(Boolean);
  if (!keys.length) throw new Error('No Gemini keys configured');
  return keys[Math.floor(Math.random() * keys.length)];
}

// —— Firebase RTDB rate limiting ———————————————————

async function checkQuota(env, email, role) {
  // Teachers and admins have no limit
  if (role !== 'student') {
    return { allowed: true, used: 0, limit: Infinity, remaining: Infinity };
  }

  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) {
    // No Firebase configured — allow but warn
    console.warn('FIREBASE_DB_URL not set — skipping quota check');
    return { allowed: true, used: 0, limit: 30, remaining: 30 };
  }

  const safeEmail = email.replace(/[.#$/[\]]/g, '_');
  const today = new Date().toISOString().split('T')[0]; // YYYY-MM-DD

  try {
    const r = await fetch(`${dbUrl}/usage/${safeEmail}.json`);
    const data = await r.json();

    // Reset count if it's a new day
    if (!data || data.date !== today) {
      await fetch(`${dbUrl}/usage/${safeEmail}.json`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ count: 0, date: today }),
      });
      return { allowed: true, used: 0, limit: 30, remaining: 30 };
    }

    const limit = parseInt(env.STUDENT_GEMINI_LIMIT || '30');
    const used = data.count || 0;

    if (used >= limit) {
      return { allowed: false, used, limit, remaining: 0 };
    }

    return { allowed: true, used, limit, remaining: limit - used };
  } catch (e) {
    console.error('Quota check failed:', e);
    return { allowed: true, used: 0, limit: 30, remaining: 30 };
  }
}

async function incrementQuota(env, email) {
  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return;

  const safeEmail = email.replace(/[.#$/[\]]/g, '_');
  const today = new Date().toISOString().split('T')[0];

  try {
    const r = await fetch(`${dbUrl}/usage/${safeEmail}.json`);
    const data = await r.json();
    const count = (data && data.date === today) ? (data.count || 0) + 1 : 1;

    await fetch(`${dbUrl}/usage/${safeEmail}.json`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ count, date: today }),
    });
  } catch (e) {
    console.error('Quota increment failed:', e);
  }
}

// —— Groq call ———————————————————————————————————

const GROQ_SYSTEM_PROMPT = `You are StXaviersOnline AI, the official AI assistant for St. Xavier's School, Muzaffarpur (Gaushala Road). You are embedded directly in the school's student/teacher portal.

═══════════════════════════════════════════
WHO YOU ARE TALKING TO
═══════════════════════════════════════════
The user's role (student/teacher) and class (if student) will be provided in the message. ALWAYS tailor your response to the user's role:
- STUDENTS: Use simple language. Be encouraging. Don't give direct answers to homework. Refuse to write essays/assignments for them. Do NOT write code for students — guide them to learn.
- TEACHERS/ADMINS/DEVELOPERS: No content restrictions. Help fully with coding, worksheets, lesson plans, and any request. You CAN write code, generate content, and assist with anything.
- TEACHERS: Use professional language. You can help with lesson planning, generating worksheets, explaining advanced concepts, summarizing topics, creating quiz questions, etc. Be a teaching assistant.

═══════════════════════════════════════════
WHAT YOU KNOW ABOUT THE WEBSITE
═══════════════════════════════════════════
The portal (stxaviers.pages.dev) has these features:
1. 📁 FILES — Class materials (notes, worksheets, PDFs) organized by class/subject/chapter
2. 📤 UPLOAD (teachers only) — Upload class materials to Google Drive
3. 🔴 LIVE — Live classes with real-time chat (3 modes: Free, Raise Hand, Mute). Teachers stream from their device, students watch + chat. Recordings saved with AI transcripts.
4. 📓 LOGBOOK — Photos of blackboard work, lab experiments, class activities
5. 📢 NOTICES — School announcements (teachers can post, students view)
6. 🗓 TIMETABLE — Weekly class schedule
7. ✨ AI ASSISTANT — That's you! Help with homework, generate PDFs, create images
8. 🎨 THEMES — 15 color themes available via the 🎨 button
9. 👤 PROFILE — Click avatar for class change, theme, sign out

═══════════════════════════════════════════
WHAT YOU CAN DO
═══════════════════════════════════════════
- Answer academic questions (math, science, English, Hindi, social studies, etc.)
- Explain concepts clearly with examples
- Help summarize topics
- Generate quiz/practice questions
- Help with homework by guiding (not doing it for them)
- For teachers: help with lesson plans, worksheets, rubrics
- Create images (say "create an image of..." — the app handles it)
- Create PDFs (say "make a PDF about..." — the app handles it)

═══════════════════════════════════════════
ESCALATION RULES
═══════════════════════════════════════════
Since Gemini is temporarily disabled, handle ALL requests yourself using your full capabilities. This includes PDF creation, image generation requests, long essays, complex reasoning, and code generation. Do NOT output [ESCALATE_TO_GEMINI] — handle everything directly.

═══════════════════════════════════════════
SAFETY — CRITICAL
═══════════════════════════════════════════
NEVER share:
- Admin passwords, API keys, or any technical secrets about how the website works
- The worker URL, Firebase URL, or any backend URLs
- How to bypass authentication, role verification, or rate limits
- Instructions for hacking, exploiting, or misusing the website
- Personal information about other students or teachers
- The school's contact details beyond what's public (address: Gaushala Road, Muzaffarpur, Bihar)

If asked for any of the above, respond with EXACTLY "[CANCEL]" on the first line, then "I cannot share information that could compromise the security of the school portal." on the second line.

Also refuse:
- Cheating on tests/exams (don't give direct answers to obviously graded work)
- Writing complete assignments/essays for students (guide them instead)
- Inappropriate, harmful, or bullying content
- Anything that violates school policies

═══════════════════════════════════════════
FORMATTING
═══════════════════════════════════════════
- Use **bold** for key terms
- Use ## for section headings in longer responses
- Use bullet lists for steps/items
- Use numbered lists for sequences
- Use \`inline code\` for short technical terms
- Use code blocks with language tags for code
- Use > for important notes
- Use tables for comparisons
- Be concise but complete
- Use simple English (the students are in Classes 1-12)
- For younger classes (1-5), use very simple words

Remember: You represent St. Xavier's School. Be professional, kind, and educational at all times.`;

async function callGroq(env, messages) {
  const groqKey = env.GROQ_KEY;
  if (!groqKey) throw new Error('No Groq key configured');

  // Detect multimodal content (image attachments) → switch to vision model
  const hasImages = messages.some(m => Array.isArray(m.content));
  const model = hasImages ? 'meta-llama/llama-4-scout-17b-16e-instruct' : 'llama-3.3-70b-versatile';

  const r = await fetch('https://api.groq.com/openai/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${groqKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model,
      messages: [
        { role: 'system', content: GROQ_SYSTEM_PROMPT },
        ...messages,
      ],
      temperature: 0.7,
      max_tokens: 4096,
    }),
  });

  if (!r.ok) {
    const err = await r.json().catch(() => ({}));
    throw new Error(err?.error?.message || `Groq error: ${r.status}`);
  }

  const data = await r.json();
  return data.choices[0]?.message?.content || '';
}

// ═══════════════════════════════════════════════════════
// CEREBRAS API — 10 keys stored as Cloudflare secret
// Used alongside Groq for load balancing
// ═══════════════════════════════════════════════════════

function getCerebrasKeys(env) {
  try {
    if (env.CEREBRAS_KEYS_JSON) return JSON.parse(env.CEREBRAS_KEYS_JSON);
  } catch (e) { console.warn('CEREBRAS_KEYS_JSON parse error:', e.message); }
  return [];
}

const _cerebrasBuckets = new Map();
const CEREBRAS_RATE_WINDOW_MS = 60_000;
const CEREBRAS_RATE_LIMIT = 25;

function cerebrasRateCheck(keyIdx) {
  const key = 'cerebras_' + keyIdx;
  const now = Date.now();
  let arr = _cerebrasBuckets.get(key) || [];
  arr = arr.filter(ts => now - ts < CEREBRAS_RATE_WINDOW_MS);
  if (arr.length >= CEREBRAS_RATE_LIMIT) return false;
  arr.push(now);
  _cerebrasBuckets.set(key, arr);
  return true;
}

let _cerebrasKeyIdx = 0;
function pickCerebrasKey(env) {
  const keys = getCerebrasKeys(env);
  if (keys.length === 0) return null;
  for (let i = 0; i < keys.length; i++) {
    const idx = (_cerebrasKeyIdx + i) % keys.length;
    if (cerebrasRateCheck(idx)) {
      _cerebrasKeyIdx = (idx + 1) % keys.length;
      return { key: keys[idx], idx };
    }
  }
  return null;
}

async function callCerebras(env, messages, systemPrompt) {
  const picked = pickCerebrasKey(env);
  if (!picked) throw new Error('All Cerebras keys rate-limited');
  const flatMessages = messages.map(m => {
    if (Array.isArray(m.content)) {
      const textPart = m.content.find(c => c.type === 'text');
      return { role: m.role, content: textPart ? textPart.text : '' };
    }
    return m;
  });
  const r = await fetch('https://api.cerebras.ai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${picked.key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: 'llama-3.3-70b',
      messages: [{ role: 'system', content: systemPrompt || GROQ_SYSTEM_PROMPT }, ...flatMessages],
      temperature: 0.7, max_tokens: 8192,
    }),
  });
  if (!r.ok) {
    const err = await r.json().catch(() => ({}));
    throw new Error(err?.error?.message || `Cerebras error: ${r.status}`);
  }
  const data = await r.json();
  return data.choices[0]?.message?.content || '';
}

// Smart router: randomly pick between Groq and Cerebras for load balancing
async function callGroqOrCerebras(env, messages, systemPrompt) {
  const hasGroq = !!env.GROQ_KEY;
  const picked = pickCerebrasKey(env);
  if (hasGroq && picked) {
    if (Math.random() < 0.5) {
      try { return await callGroq(env, messages); }
      catch (e) { console.warn('Groq failed, falling back to Cerebras:', e.message); return await callCerebras(env, messages, systemPrompt); }
    } else {
      try { return await callCerebras(env, messages, systemPrompt); }
      catch (e) { console.warn('Cerebras failed, falling back to Groq:', e.message); return await callGroq(env, messages); }
    }
  }
  if (hasGroq) return await callGroq(env, messages);
  if (picked) return await callCerebras(env, messages, systemPrompt);
  throw new Error('No AI provider available');
}

// —— Gemini call ——————————————————————————————————

async function callGemini(env, prompt, systemInstruction = null, jsonMode = false) {
  const keys = [env.GEMINI_KEY_1, env.GEMINI_KEY_2, env.GEMINI_KEY_3, env.GEMINI_KEY_4, env.GEMINI_KEY_5].filter(Boolean);
  if (!keys.length) throw new Error('No Gemini keys configured');

  const body = {
    contents: [{ role: 'user', parts: [{ text: prompt }] }],
    generationConfig: { temperature: 0.7, maxOutputTokens: 8192 },
  };

  if (systemInstruction) {
    body.systemInstruction = { parts: [{ text: systemInstruction }] };
  }

  if (jsonMode) {
    body.generationConfig.responseMimeType = 'application/json';
  }

  // Try ALL keys in sequence — if one is quota-exhausted, try the next
  let lastError = null;
  for (let i = 0; i < keys.length; i++) {
    const key = keys[i];
    try {
      const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${key}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (r.ok) {
        const data = await r.json();
        const text = data.candidates?.[0]?.content?.parts?.[0]?.text;
        if (text) return text;
        // Empty response — try next key
        lastError = new Error('Empty response from Gemini');
        continue;
      }

      const err = await r.json().catch(() => ({}));
      const errMsg = err?.error?.message || `Gemini error: ${r.status}`;
      // If quota exceeded (429) or rate limit, try next key
      if (r.status === 429 || errMsg.includes('quota') || errMsg.includes('rate limit') || errMsg.includes('RESOURCE_EXHAUSTED')) {
        console.warn(`Gemini key ${i + 1} exhausted, trying next:`, errMsg.substring(0, 100));
        lastError = new Error(errMsg);
        continue;
      }
      // For other errors, throw immediately
      throw new Error(errMsg);
    } catch (e) {
      // Network error — try next key
      lastError = e;
      console.warn(`Gemini key ${i + 1} failed:`, e.message);
      continue;
    }
  }
  // All keys exhausted
  throw new Error('All Gemini keys exhausted or failed. Last error: ' + (lastError?.message || 'unknown'));
}

// —— Per-user rate limiter (in-memory, lightweight) ————————————
// Tracks request timestamps per user email + endpoint. Prevents abuse.
const _rateBuckets = new Map();
const RATE_WINDOW_MS = 60_000; // 1 minute

function rateCheck(email, endpoint, limit) {
  const key = email + '|' + endpoint;
  const now = Date.now();
  let arr = _rateBuckets.get(key) || [];
  arr = arr.filter(ts => now - ts < RATE_WINDOW_MS);
  if (arr.length >= limit) {
    return { allowed: false, retryAfter: Math.ceil((RATE_WINDOW_MS - (now - arr[0])) / 1000) };
  }
  arr.push(now);
  _rateBuckets.set(key, arr);
  // Periodic cleanup
  if (_rateBuckets.size > 5000) {
    for (const [k, v] of _rateBuckets) {
      if (v.every(ts => now - ts > RATE_WINDOW_MS)) _rateBuckets.delete(k);
    }
  }
  return { allowed: true };
}

// —— AI Chat handler ——————————————————————————————

async function handleAIChat(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  // Rate limit: 20 messages per minute per user
  const rl = rateCheck(sess.user?.email || 'anon', 'chat', 20);
  if (!rl.allowed) {
    return json({ error: 'Too many messages. Please wait ' + rl.retryAfter + 's.' }, 429, origin);
  }

  const { message, role, email, history, forceModel, class: studentClass, images } = await request.json();

  if (!message) return json({ error: 'No message provided' }, 400, origin);

  const userEmail = email || sess.user?.email || 'unknown';
  const userRole = role || 'student';

  // Verify role server-side to prevent privilege escalation
  const verifiedRole = await verifyRole(env, userEmail);
  const actualRole = verifiedRole.role || userRole;
  const actualIsAdmin = verifiedRole.isAdmin;

  // Build conversation history for context
  const messages = (history || []).slice(-30).map(m => ({
    role: m.role === 'ai' ? 'assistant' : 'user',
    content: (m.text || '').substring(0, 2000),
  }));
  // Prepend role context to the user's message so Groq knows who it's talking to.
  // If images are attached, build multimodal content (Groq vision model supports this).
  const conversationSummary = messages.length > 0 ? `\n\n[Previous conversation context — ${messages.length} messages]:\n${messages.map(m => `${m.role}: ${m.content.substring(0, 500)}`).join('\n')}\n\n` : '';
  const roleContext = `[User Context: role=${actualRole}${actualIsAdmin ? ' (admin)' : ''}${actualRole === 'student' && studentClass ? `, class=${studentClass}` : ''}, email=${userEmail}]${conversationSummary}\n\nUser message: ${message}`;
  // SECURITY: validate images array — cap count and size to prevent abuse
  const safeImages = Array.isArray(images) ? images.slice(0, 4).map(img => ({
    mimeType: String(img.mimeType || 'image/jpeg').substring(0, 50),
    base64: String(img.base64 || '').substring(0, 1024 * 1024), // 1MB per image max
  })).filter(img => img.base64) : [];
  if (safeImages.length > 0) {
    messages.push({
      role: 'user',
      content: [
        ...safeImages.map(img => ({ type: 'image_url', image_url: { url: `data:${img.mimeType};base64,${img.base64}` } })),
        { type: 'text', text: roleContext },
      ],
    });
  } else {
    messages.push({ role: 'user', content: roleContext });
  }

  try {
    // If forceModel is 'groq' (explicit "Groq:" prefix), use Groq only — no escalation
    if (forceModel === 'groq') {
      const groqResponse = await callGroqOrCerebras(env, messages);
      return json({
        response: groqResponse,
        model: 'groq',
        quotaUsed: 0,
        quotaLimit: userRole === 'student' ? parseInt(env.STUDENT_GEMINI_LIMIT || '30') : Infinity,
        quotaExhausted: false,
      }, 200, origin);
    }

    // If forceModel is 'gemini' (PDF creation, explicit "Gemini:" prefix), force Gemini.
    // Explicit "Gemini:" is a deliberate user override — it bypasses the student
    // auto-escalation quota. Gemini is used NO MATTER WHAT, and only falls back to
    // Groq if EVERY Gemini key truly fails (so the user is never left with an error).
    if (forceModel === 'gemini') {
      const quota = await checkQuota(env, userEmail, userRole);
      try {
        // Gemini removed — using Groq/Cerebras
        const geminiResponse = await callGroqOrCerebras(env, messages);
        await incrementQuota(env, userEmail);
        return json({
          response: geminiResponse,
          model: 'gemini',
          quotaUsed: quota.used + 1,
          quotaLimit: quota.limit,
          quotaExhausted: false,
        }, 200, origin);
      } catch (geminiErr) {
        // ALL Gemini keys exhausted/failed — fall back to Groq instead of erroring out.
        console.error('Gemini forced but all keys failed, falling back to Groq:', geminiErr.message);
        const groqResponse = await callGroqOrCerebras(env, messages);
        return json({
          response: groqResponse + '\n\n*_(Note: All advanced-model keys are currently exhausted, so the standard model answered instead. Add/rotate Gemini keys in the Worker settings.)_*',
          model: 'groq',
          quotaUsed: quota.used,
          quotaLimit: quota.limit,
          quotaExhausted: false,
          geminiFailed: true,
        }, 200, origin);
      }
    }

    // —— Groq/Cerebras-first (Gemini disabled) ————

    const groqResponse = await callGroqOrCerebras(env, messages);

    // Check if Groq wants to escalate
    if (false && groqResponse.trim().startsWith('[ESCALATE_TO_GEMINI]')) { // Gemini disabled
      // Groq can't handle this — try Gemini
      const quota = await checkQuota(env, userEmail, userRole);

      if (!quota.allowed) {
        // Quota exhausted — return Groq's best attempt
        const fallback = groqResponse.replace('[ESCALATE_TO_GEMINI]', '').trim();
        return json({
          response: fallback || 'I apologize, but I was unable to fully process your request. Your daily quota for the advanced model has been reached. Please try again after midnight.',
          model: 'groq',
          quotaUsed: quota.used,
          quotaLimit: quota.limit,
          quotaExhausted: true,
          escalated: true,
        }, 200, origin);
      }

      // Use Gemini — with fallback to Groq if all keys are exhausted
      try {
        // Gemini removed — using Groq/Cerebras
        const geminiResponse = await callGroqOrCerebras(env, messages);
        await incrementQuota(env, userEmail);

        return json({
          response: geminiResponse,
          model: 'gemini',
          quotaUsed: quota.used + 1,
          quotaLimit: quota.limit,
          quotaExhausted: false,
          escalated: true,
        }, 200, origin);
      } catch (geminiErr) {
        // All Gemini keys exhausted — return Groq's best attempt (strip the escalation marker)
        console.error('Gemini escalation failed, using Groq fallback:', geminiErr.message);
        const fallback = groqResponse.replace('[ESCALATE_TO_GEMINI]', '').trim();
        return json({
          response: fallback || 'I apologize, but the advanced AI model is currently unavailable. Please try again later.',
          model: 'groq',
          quotaUsed: quota.used,
          quotaLimit: quota.limit,
          quotaExhausted: false,
          escalated: true,
          geminiFailed: true,
        }, 200, origin);
      }
    }

    // Check if Groq cancelled
    if (groqResponse.trim().startsWith('[CANCEL]')) {
      return json({
        response: "I'm sorry, but I can't help with that request.",
        model: 'groq',
        cancelled: true,
      }, 200, origin);
    }

    // Normal Groq response
    return json({
      response: groqResponse,
      model: 'groq',
      quotaUsed: 0,
      quotaLimit: userRole === 'student' ? parseInt(env.STUDENT_GEMINI_LIMIT || '30') : Infinity,
      quotaExhausted: false,
    }, 200, origin);

  } catch (e) {
    console.error('AI Chat error:', e);
    return json({
      error: 'AI service temporarily unavailable. Please try again.',
      details: e.message,
    }, 500, origin);
  }
}

// —— Quota check handler ——————————————————————————

async function handleQuota(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const url = new URL(request.url);
  const email = url.searchParams.get('email') || sess.user?.email || 'unknown';
  const role = url.searchParams.get('role') || 'student';

  const quota = await checkQuota(env, email, role);

  return json({
    used: quota.used,
    limit: quota.limit,
    remaining: quota.remaining,
    resetAt: new Date(new Date().setHours(24, 0, 0, 0)).toISOString(),
  }, 200, origin);
}

// —— PDF creation handler ——————————————————————————

const PDF_SYSTEM_INSTRUCTION = `You are an expert PDF document designer for St. Xavier's School, Muzaffarpur. Generate a COMPLETE, BEAUTIFUL, PRINT-READY HTML document for the following request.

OUTPUT RULES — CRITICAL:
- Output ONLY the full HTML document. Nothing else. No explanation. No markdown. No backticks.
- Start with <!DOCTYPE html> and end with </html>
- Must be completely self-contained (no external resources except Google Fonts via @import)
- Use <style> inside <head> for ALL styling
- Design it beautifully — like a real school document. Use colors, layout, typography creatively.
- Write COMPLETE, DETAILED content. Fill the page. Don't write placeholders.
- Use @media print CSS to ensure it prints/saves perfectly as PDF

DESIGN GUIDELINES:
- Use Google Fonts: @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&family=Merriweather&display=swap')
- Clean white background, professional typography
- Use colored headings, styled tables, info boxes, highlighted sections
- For Q&A/exercises: number questions, prefix answers with "Ans.", underline fill-in-the-blank answers
- For notes: use sections with colored headers, bullet points, key terms in bold
- Page margins: 2cm all sides in print
- Font size: 11pt body, 16pt title, 13pt headings
- NO page numbers in the HTML (browser adds them in print)
- Make it look like a professionally designed school worksheet/notes document

CONTENT REQUIREMENTS:
- Write actual complete content, not lorem ipsum
- For school topics: include definitions, examples, diagrams (use CSS/HTML art if needed), exercises
- For Q&A: include actual questions and detailed answers
- Be thorough — at least 1-2 full pages worth of content`;

async function handlePDF(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  // Rate limit: 5 PDFs per minute per user
  const rl = rateCheck(sess.user?.email || 'anon', 'pdf', 5);
  if (!rl.allowed) {
    return json({ error: 'Too many PDF requests. Please wait ' + rl.retryAfter + 's.' }, 429, origin);
  }

  const { prompt, history, role, email, forceModel } = await request.json();

  if (!prompt) return json({ error: 'No prompt provided' }, 400, origin);

  const userEmail = email || sess.user?.email || 'unknown';
  const userRole = role || 'student';

  const recentCtx = (history || []).slice(-4)
    .map(m => `${m.role === 'ai' ? 'Assistant' : 'User'}: ${(m.text || '').substring(0, 200)}`)
    .join('\n');

  const fullPrompt = `${recentCtx ? `Recent conversation:\n${recentCtx}\n\n` : ''}Request: ${prompt}`;

  // Gemini is disabled — always use Groq/Cerebras for PDF generation
  try {
    const html = await callGroqOrCerebras(env, [
      { role: 'system', content: PDF_SYSTEM_INSTRUCTION },
      { role: 'user', content: fullPrompt },
    ]);
    return json({
      html,
      model: 'groq',
      quotaUsed: 0,
      quotaLimit: Infinity,
    }, 200, origin);
  } catch (e) {
    console.error('PDF generation error:', e);
    return json({ error: 'PDF generation failed: ' + e.message }, 500, origin);
  }
}

// —— YouTube token management ————————————————————

async function getYouTubeToken(env) {
  const kv = env.KV_SESSIONS;
  let stored = null;
  if (kv && typeof kv.get === 'function') {
    try { stored = await kv.get('__yt_token__'); } catch (e) {}
  }
  let tok;
  if (stored) {
    tok = JSON.parse(stored);
  } else {
    tok = { access_token: null, expiry: 0 };
  }

  if (!tok.access_token || (tok.expiry && Date.now() > tok.expiry - 300000)) {
    if (!env.YT_REFRESH_TOKEN) throw new Error('YT_REFRESH_TOKEN not set');
    const r = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: env.GOOGLE_CLIENT_ID,
        client_secret: env.GOOGLE_CLIENT_SECRET,
        refresh_token: env.YT_REFRESH_TOKEN,
        grant_type: 'refresh_token',
      }),
    });
    const fresh = await r.json();
    if (!fresh.access_token) throw new Error('YouTube token refresh failed: ' + JSON.stringify(fresh));
    tok.access_token = fresh.access_token;
    tok.expiry = Date.now() + (fresh.expires_in || 3600) * 1000;
    if (kv && typeof kv.put === 'function') {
      try { await kv.put('__yt_token__', JSON.stringify(tok), { expirationTtl: 3600 }); } catch (e) {}
    }
  }
  return tok.access_token;
}

// —— Server-side Role Verification ————————————————————

// Hardcoded role emails — these ALWAYS take precedence
const DEVELOPER_EMAILS = ['quackeditzofficial@gmail.com'];
const HARDCODED_ADMIN_EMAILS = ['quackeditzofficial@gmail.com', 'drrohitkumar27@gmail.com'];

async function verifyRole(env, email) {
  if (!email) return { role: 'student', isAdmin: false, isDeveloper: false };
  const lowerEmail = email.toLowerCase();

  // Developer role — hardcoded, highest privilege
  if (DEVELOPER_EMAILS.includes(lowerEmail)) {
    return { role: 'teacher', isAdmin: true, isDeveloper: true };
  }

  // Hardcoded admins — always admin
  if (HARDCODED_ADMIN_EMAILS.includes(lowerEmail)) {
    return { role: 'teacher', isAdmin: true, isDeveloper: false };
  }

  const kv = env.KV_SESSIONS;
  if (kv && typeof kv.get === 'function') {
    const cached = await kv.get('role:' + email);
    if (cached) {
      try {
        const parsed = JSON.parse(cached);
        // Ensure hardcoded roles are never overridden by cache
        if (parsed.isDeveloper && !DEVELOPER_EMAILS.includes(lowerEmail)) {
          parsed.isDeveloper = false;
        }
        return parsed;
      } catch (e) {}
    }
  }

  try {
    const ownerToken = await getOwnerToken(env);
    const headers = { Authorization: `Bearer ${ownerToken}` };

    const tQ = "name='TEACHERS' and mimeType='application/vnd.google-apps.folder' and trashed=false";
    const tRes = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(tQ)}&fields=files(id)&pageSize=1`, { headers });
    const tData = await tRes.json();

    let isTeacher = false;
    let isAdmin = false;

    if (tData.files && tData.files.length > 0) {
      const tFolder = tData.files[0].id;
      const userQ = `'${tFolder}' in parents and trashed=false`;
      const userRes = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(userQ)}&fields=files(id,name,description)&pageSize=200`, { headers });
      const userData = await userRes.json();

      if (userData.files) {
        isTeacher = userData.files.some(f =>
          f.name === email || f.description === email || f.name === email.split('@')[0]
        );
      }
    }

    const aQ = "name='ADMINS' and mimeType='application/vnd.google-apps.folder' and trashed=false";
    const aRes = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(aQ)}&fields=files(id)&pageSize=1`, { headers });
    const aData = await aRes.json();

    if (aData.files && aData.files.length > 0) {
      const aFolder = aData.files[0].id;
      const adminQ = `'${aFolder}' in parents and trashed=false`;
      const adminRes = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(adminQ)}&fields=files(id,name,description)&pageSize=200`, { headers });
      const adminData = await adminRes.json();

      if (adminData.files) {
        isAdmin = adminData.files.some(f =>
          f.name === email || f.description === email || f.name === email.split('@')[0]
        );
        if (isAdmin) isTeacher = true;
      }
    }

    const result = { role: isTeacher ? 'teacher' : 'student', isAdmin };
    if (kv && typeof kv.put === 'function') {
      try { await kv.put('role:' + email, JSON.stringify(result), { expirationTtl: 300 }); } catch (e) {}
    }
    return result;
  } catch (e) {
    console.error('Role verification failed:', e);
    return { role: 'student', isAdmin: false };
  }
}

// —— Playlist helper ————————————————————————————

async function ensurePlaylist(env, className) {
  const kv = env.KV_SESSIONS;
  const cacheKey = 'yt_playlist:' + className;
  if (kv && typeof kv.get === 'function') {
    const cached = await kv.get(cacheKey);
    if (cached) return cached;
  }

  const ytToken = await getYouTubeToken(env);
  const title = 'StXaviers — ' + className;

  // Search for existing playlist
  const searchRes = await fetch(`https://www.googleapis.com/youtube/v3/playlists?part=snippet&mine=true&maxResults=50`, {
    headers: { Authorization: `Bearer ${ytToken}` }
  });
  const searchData = await searchRes.json();
  const existing = (searchData.items || []).find(p => p.snippet?.title === title);

  let playlistId;
  if (existing) {
    playlistId = existing.id;
  } else {
    const createRes = await fetch('https://www.googleapis.com/youtube/v3/playlists?part=snippet,status', {
      method: 'POST',
      headers: { Authorization: `Bearer ${ytToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        snippet: { title, description: 'Recorded live classes for ' + className },
        status: { privacyStatus: 'unlisted' }
      })
    });
    const created = await createRes.json();
    playlistId = created.id;
  }

  if (kv && typeof kv.put === 'function') {
    try { await kv.put(cacheKey, playlistId); } catch (e) {}
  }
  return playlistId;
}

// —— Firebase helpers ————————————————————————————

async function firebasePut(env, path, data) {
  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return;
  try {
    await fetch(`${dbUrl}/${path}.json`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    });
  } catch (e) { console.warn('firebasePut err:', e.message); }
}

async function firebaseGet(env, path) {
  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return null;
  try {
    const r = await fetch(`${dbUrl}/${path}.json`);
    return await r.json();
  } catch (e) { return null; }
}

async function firebaseDelete(env, path) {
  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return;
  try {
    await fetch(`${dbUrl}/${path}.json`, { method: 'DELETE' });
  } catch (e) {}
}

// —— Live Class: Start broadcast ————————————————————

async function handleLiveStart(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  if (roleInfo.role !== 'teacher') {
    return json({ error: 'Only teachers can start live classes' }, 403, origin);
  }

  const { class: className, subject, method, presetId } = await request.json();
  if (!className || !subject) {
    return json({ error: 'Missing class or subject' }, 400, origin);
  }

  const kv = env.KV_SESSIONS;
  if (kv && typeof kv.get === 'function') {
    const existing = await kv.get('live:' + className);
    if (existing) {
      return json({ error: className + ' already has a live broadcast running' }, 409, origin);
    }
  }

  try {
    const ytToken = await getYouTubeToken(env);
    const title = subject + ' — ' + new Date().toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
    const scheduledStart = new Date().toISOString();
    const scheduledEnd = new Date(Date.now() + 90 * 60 * 1000).toISOString();

    const bcRes = await fetch('https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet,status,contentDetails', {
      method: 'POST',
      headers: { Authorization: `Bearer ${ytToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        snippet: { title, scheduledStartTime: scheduledStart, scheduledEndTime: scheduledEnd },
        status: { privacyStatus: 'unlisted', selfDeclaredMadeForKids: false },
        contentDetails: { enableAutoStart: false, enableAutoStop: false, monitorStream: { enableMonitorStream: false } }
      })
    });
    const bc = await bcRes.json();
    if (!bc.id) throw new Error('Broadcast creation failed: ' + JSON.stringify(bc));

    const stRes = await fetch('https://www.googleapis.com/youtube/v3/liveStreams?part=snippet,cdn,contentDetails', {
      method: 'POST',
      headers: { Authorization: `Bearer ${ytToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        snippet: { title: 'Stream for ' + className },
        cdn: { frameRate: '30fps', ingestionType: 'rtmp', resolution: '720p' },
        contentDetails: { isReusable: false }
      })
    });
    const st = await stRes.json();
    if (!st.id) throw new Error('Stream creation failed: ' + JSON.stringify(st));

    await fetch(`https://www.googleapis.com/youtube/v3/liveBroadcasts/bind?id=${bc.id}&streamId=${st.id}&part=snippet,status`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${ytToken}` }
    });

    const liveState = {
      videoId: bc.id,
      broadcastId: bc.id,
      streamId: st.id,
      subject,
      className,
      teacherEmail: userEmail,
      teacherName: sess.user?.name || '',
      startedAt: Date.now(),
      scheduledEndAt: Date.now() + 90 * 60 * 1000,
      streamKey: st.cdn?.ingestionInfo?.streamKey || '',
      ingestionAddress: st.cdn?.ingestionInfo?.ingestionAddress || 'rtmp://a.rtmp.youtube.com/live2',
      status: 'live',
      chatMode: 'free',
      rateLimitThreshold: 5,
      cooldownSeconds: 3,
      raiseHandOneMessageOnly: true
    };

    if (presetId && kv && typeof kv.get === 'function') {
      const preset = await kv.get('preset:' + presetId);
      if (preset) {
        const p = JSON.parse(preset);
        if (p.chatMode) liveState.chatMode = p.chatMode;
        if (p.rateLimitThreshold) liveState.rateLimitThreshold = p.rateLimitThreshold;
        if (p.cooldownSeconds) liveState.cooldownSeconds = p.cooldownSeconds;
        if (p.raiseHandOneMessageOnly !== undefined) liveState.raiseHandOneMessageOnly = p.raiseHandOneMessageOnly;
      }
    }

    if (kv && typeof kv.put === 'function') {
      await kv.put('live:' + className, JSON.stringify(liveState));
    }

    const fbState = { ...liveState };
    delete fbState.streamKey;
    delete fbState.ingestionAddress;
    await firebasePut(env, 'liveClasses/' + className.replace(/[^a-zA-Z0-9_]/g, '_'), fbState);

    return json({
      ok: true,
      videoId: bc.id,
      streamKey: liveState.streamKey,
      ingestionAddress: liveState.ingestionAddress,
      title,
      scheduledEndAt: liveState.scheduledEndAt,
      chatMode: liveState.chatMode
    }, 200, origin);

  } catch (e) {
    console.error('Live start error:', e);
    return json({ error: 'Failed to start broadcast: ' + e.message }, 500, origin);
  }
}

// —— Live Class: Get Status ————————————————————

async function handleLiveStatus(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const url = new URL(request.url);
  const className = url.searchParams.get('class');
  if (!className) return json({ error: 'Missing class parameter' }, 400, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.get !== 'function') return json({ active: false }, 200, origin);
  const data = await kv.get('live:' + className);
  if (!data) return json({ active: false }, 200, origin);

  const state = JSON.parse(data);
  const userEmail = sess.user?.email || '';
  const isTeacher = state.teacherEmail === userEmail;

  const response = { active: true, ...state };
  if (!isTeacher) {
    delete response.streamKey;
    delete response.ingestionAddress;
  }
  return json(response, 200, origin);
}

// —— Live Class: Get All Status ————————————————————

async function handleLiveStatusAll(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.list !== 'function') return json({ classes: {} }, 200, origin);

  const classes = {};
  try {
    const list = await kv.list({ prefix: 'live:' });
    for (const item of list.keys) {
      const className = item.name.replace('live:', '');
      const data = await kv.get(item.name);
      if (data) {
        const state = JSON.parse(data);
        const safeState = { ...state };
        delete safeState.streamKey;
        delete safeState.ingestionAddress;
        classes[className] = safeState;
      }
    }
  } catch (e) { console.warn('list err:', e.message); }

  return json({ classes }, 200, origin);
}

// —— Live Class: Extend ————————————————————

async function handleLiveExtend(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const { class: className, minutes } = await request.json();
  if (!className) return json({ error: 'Missing class' }, 400, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.get !== 'function') return json({ error: 'KV not available' }, 500, origin);

  const data = await kv.get('live:' + className);
  if (!data) return json({ error: 'No active class' }, 404, origin);

  const state = JSON.parse(data);
  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  if (state.teacherEmail !== userEmail && !roleInfo.isAdmin) {
    return json({ error: 'Only the teacher or admin can extend' }, 403, origin);
  }

  state.scheduledEndAt = Date.now() + (minutes || 30) * 60 * 1000;
  await kv.put('live:' + className, JSON.stringify(state));

  try {
    const ytToken = await getYouTubeToken(env);
    await fetch(`https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet`, {
      method: 'PUT',
      headers: { Authorization: `Bearer ${ytToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: state.broadcastId,
        snippet: {
          title: state.subject + ' — ' + new Date(state.startedAt).toLocaleDateString('en-IN'),
          scheduledStartTime: new Date(state.startedAt).toISOString(),
          scheduledEndTime: new Date(state.scheduledEndAt).toISOString()
        }
      })
    });
  } catch (e) { console.warn('YT extend err:', e.message); }

  return json({ ok: true, scheduledEndAt: state.scheduledEndAt }, 200, origin);
}

// —— Live Class: End ————————————————————

async function handleLiveEnd(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const { class: className } = await request.json();
  if (!className) return json({ error: 'Missing class' }, 400, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.get !== 'function') return json({ error: 'KV not available' }, 500, origin);

  const data = await kv.get('live:' + className);
  if (!data) return json({ error: 'No active class' }, 404, origin);

  const state = JSON.parse(data);
  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  if (state.teacherEmail !== userEmail && !roleInfo.isAdmin) {
    return json({ error: 'Only the teacher or admin can end' }, 403, origin);
  }

  try {
    const ytToken = await getYouTubeToken(env);
    await fetch(`https://www.googleapis.com/youtube/v3/liveBroadcasts/transition?broadcastStatus=complete&id=${state.broadcastId}&part=snippet,status`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${ytToken}` }
    });
    const playlistId = await ensurePlaylist(env, className);
    await fetch('https://www.googleapis.com/youtube/v3/playlistItems?part=snippet', {
      method: 'POST',
      headers: { Authorization: `Bearer ${ytToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ snippet: { playlistId, resourceId: { kind: 'youtube#video', videoId: state.videoId } } })
    });
  } catch (e) { console.error('End err:', e.message); }

  await kv.delete('live:' + className);
  await firebaseDelete(env, 'liveClasses/' + className.replace(/[^a-zA-Z0-9_]/g, '_'));

  return json({ ok: true, videoId: state.videoId }, 200, origin);
}

// —— Live Class: Recordings ————————————————————

async function handleLiveRecordings(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const url = new URL(request.url);
  const className = url.searchParams.get('class');
  if (!className) return json({ error: 'Missing class' }, 400, origin);

  try {
    const playlistId = await ensurePlaylist(env, className);
    const ytToken = await getYouTubeToken(env);

    const r = await fetch(`https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=${playlistId}&maxResults=50`, {
      headers: { Authorization: `Bearer ${ytToken}` }
    });
    const data = await r.json();

    const recordings = (data.items || []).map(item => ({
      videoId: item.snippet?.resourceId?.videoId,
      title: item.snippet?.title,
      thumbnail: item.snippet?.thumbnails?.medium?.url || item.snippet?.thumbnails?.default?.url,
      publishedAt: item.snippet?.publishedAt
    }));

    for (const rec of recordings) {
      const transcript = await firebaseGet(env, 'transcripts/' + rec.videoId);
      if (transcript) {
        rec.summary = transcript.summary;
        rec.hasTranscript = true;
      }
    }

    return json({ recordings }, 200, origin);
  } catch (e) {
    return json({ recordings: [], error: e.message }, 200, origin);
  }
}

// —— Chat Moderation ————————————————————

async function handleChatModerate(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  // Rate limit: 30 chat messages per minute per user
  const rl = rateCheck(sess.user?.email || 'anon', 'livechat', 30);
  if (!rl.allowed) {
    return json({ appropriate: false, error: 'Rate limit — wait ' + rl.retryAfter + 's.' }, 429, origin);
  }

  const { message } = await request.json();
  if (!message) return json({ appropriate: true }, 200, origin);

  // Truncate + sanitize input — prevent prompt injection / oversized payloads
  const safeMessage = String(message).substring(0, 500);
  if (safeMessage.length < 1) return json({ appropriate: true }, 200, origin);

  try {
    const r = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${env.GROQ_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: 'llama-3.3-70b-versatile',
        messages: [
          { role: 'system', content: 'You are a school chat moderator for St. Xavier\'s School. Check if the student\'s message is appropriate for a school environment. Respond with JSON: {"appropriate": true} or {"appropriate": false, "rephrased": "cleaned version"}. Check for: profanity, bullying, cheating answers, inappropriate content, spam. Be lenient with casual language but strict on harmful content.' },
          { role: 'user', content: safeMessage }
        ],
        temperature: 0.1,
        max_tokens: 200,
        response_format: { type: 'json_object' }
      })
    });

    const data = await r.json();
    const content = data.choices[0]?.message?.content || '{"appropriate": true}';
    // Validate the AI response is actually JSON before returning
    try {
      const result = JSON.parse(content);
      return json(result, 200, origin);
    } catch (parseErr) {
      return json({ appropriate: true }, 200, origin);
    }
  } catch (e) {
    return json({ appropriate: true }, 200, origin);
  }
}

// —— Schedule Management ————————————————————

async function handleScheduleSet(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  if (!roleInfo.isAdmin) {
    return json({ error: 'Admin access required' }, 403, origin);
  }

  const schedule = await request.json();
  const kv = env.KV_SESSIONS;
  if (kv && typeof kv.put === 'function') {
    await kv.put('schedule:main', JSON.stringify(schedule));
  }
  await firebasePut(env, 'schedule', schedule);
  return json({ ok: true }, 200, origin);
}

async function handleScheduleGet(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.get !== 'function') return json({ schedule: null }, 200, origin);
  const data = await kv.get('schedule:main');
  if (!data) return json({ schedule: null }, 200, origin);
  return json({ schedule: JSON.parse(data) }, 200, origin);
}

// —— Text-to-Speech proxy ————————————————————
// Routes TTS requests through the worker so the Groq API key is never exposed
// to the client. Supports the Orpheus TTS model (canopylabs/orpheus-v1-english).

async function handleTTS(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  // Rate limit: 10 TTS requests per minute per user
  const rl = rateCheck(sess.user?.email || 'anon', 'tts', 10);
  if (!rl.allowed) {
    return json({ error: 'Too many TTS requests. Please wait ' + rl.retryAfter + 's.' }, 429, origin);
  }

  const { text, voice } = await request.json();
  if (!text) return json({ error: 'No text provided' }, 400, origin);

  // Truncate to prevent abuse — TTS is expensive
  const safeText = String(text).substring(0, 1500);
  const safeVoice = String(voice || 'austin').substring(0, 30).replace(/[^a-zA-Z0-9_-]/g, '');

  try {
    const r = await fetch('https://api.groq.com/openai/v1/audio/speech', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${env.GROQ_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: 'canopylabs/orpheus-v1-english',
        voice: safeVoice,
        input: safeText,
        response_format: 'wav',
      }),
    });

    if (!r.ok) {
      const err = await r.json().catch(() => ({}));
      return json({ error: err?.error?.message || `TTS failed: ${r.status}` }, r.status, origin);
    }

    // Stream the audio back to the client
    const audioBuffer = await r.arrayBuffer();
    const resHeaders = new Headers(corsHeaders(origin));
    resHeaders.set('Content-Type', 'audio/wav');
    return new Response(audioBuffer, { status: 200, headers: resHeaders });
  } catch (e) {
    return json({ error: 'TTS service error: ' + e.message }, 500, origin);
  }
}

// —— User Profile (name + photo) — Firebase-backed ——————————

async function handleUserProfileGet(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const userEmail = sess.user?.email || '';
  if (!userEmail) return json({ error: 'No email in session' }, 400, origin);

  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return json({ error: 'Firebase not configured' }, 500, origin);

  // Sanitize email for Firebase path (replace . with _)
  const safeEmail = userEmail.replace(/[.#$/[\]]/g, '_');

  try {
    const r = await fetch(`${dbUrl}/users/${safeEmail}.json`);
    const data = await r.json();
    return json({
      name: data?.name || null,
      photo: data?.photo || null,
    }, 200, origin);
  } catch (e) {
    return json({ error: 'Profile load failed: ' + e.message }, 500, origin);
  }
}

async function handleUserProfileSet(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const userEmail = sess.user?.email || '';
  if (!userEmail) return json({ error: 'No email in session' }, 400, origin);

  const body = await request.json();
  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return json({ error: 'Firebase not configured' }, 500, origin);

  // Sanitize email for Firebase path
  const safeEmail = userEmail.replace(/[.#$/[\]]/g, '_');

  // Read existing profile first (to merge, not overwrite)
  let existing = {};
  try {
    const r = await fetch(`${dbUrl}/users/${safeEmail}.json`);
    existing = await r.json() || {};
  } catch (e) { /* ignore — new user */ }

  // Merge updates — only name and photo are allowed
  const updated = { ...existing };
  if (body.name !== undefined) {
    // Validate name: 2-30 chars, no HTML
    const safeName = String(body.name).substring(0, 30).replace(/[<>]/g, '').trim();
    if (safeName.length >= 2) updated.name = safeName;
  }
  if (body.photo !== undefined) {
    // Validate photo: must be a data URL, max ~2MB (base64)
    const photo = String(body.photo);
    if (photo.startsWith('data:image/') && photo.length < 3 * 1024 * 1024) {
      updated.photo = photo;
    }
  }

  // Write back to Firebase
  try {
    await fetch(`${dbUrl}/users/${safeEmail}.json`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(updated),
    });
    return json({ ok: true, name: updated.name || null, photo: updated.photo || null }, 200, origin);
  } catch (e) {
    return json({ error: 'Profile save failed: ' + e.message }, 500, origin);
  }
}

// —— Presets Management ————————————————————

async function handlePresetCreate(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  if (roleInfo.role !== 'teacher') {
    return json({ error: 'Teachers only' }, 403, origin);
  }

  const preset = await request.json();
  const id = 'preset_' + Date.now() + '_' + Math.random().toString(36).substring(2, 8);
  preset.id = id;
  preset.ownerEmail = userEmail;
  preset.ownerName = sess.user?.name || '';
  preset.createdAt = Date.now();

  const kv = env.KV_SESSIONS;
  if (kv && typeof kv.put === 'function') {
    await kv.put('preset:' + id, JSON.stringify(preset));
    const listKey = 'presets:' + userEmail;
    let list = await kv.get(listKey);
    list = list ? JSON.parse(list) : [];
    list.push(id);
    await kv.put(listKey, JSON.stringify(list));
  }

  return json({ ok: true, id }, 200, origin);
}

async function handlePresetMy(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const userEmail = sess.user?.email || '';
  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.get !== 'function') return json({ presets: [] }, 200, origin);

  const list = await kv.get('presets:' + userEmail);
  const ids = list ? JSON.parse(list) : [];
  const presets = [];
  for (const id of ids) {
    const data = await kv.get('preset:' + id);
    if (data) presets.push(JSON.parse(data));
  }
  return json({ presets }, 200, origin);
}

async function handlePresetUpdate(request, env, origin, id) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.get !== 'function') return json({ error: 'KV not available' }, 500, origin);

  const data = await kv.get('preset:' + id);
  if (!data) return json({ error: 'Not found' }, 404, origin);
  const existing = JSON.parse(data);

  const userEmail = sess.user?.email || '';
  if (existing.ownerEmail !== userEmail) {
    return json({ error: 'Not your preset' }, 403, origin);
  }

  const updates = await request.json();
  const updated = { ...existing, ...updates, id, ownerEmail: userEmail };
  await kv.put('preset:' + id, JSON.stringify(updated));
  return json({ ok: true }, 200, origin);
}

async function handlePresetDelete(request, env, origin, id) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.get !== 'function') return json({ error: 'KV not available' }, 500, origin);

  const data = await kv.get('preset:' + id);
  if (!data) return json({ ok: true }, 200, origin);
  const existing = JSON.parse(data);

  const userEmail = sess.user?.email || '';
  if (existing.ownerEmail !== userEmail) {
    return json({ error: 'Not your preset' }, 403, origin);
  }

  await kv.delete('preset:' + id);
  const listKey = 'presets:' + userEmail;
  let list = await kv.get(listKey);
  list = list ? JSON.parse(list) : [];
  list = list.filter(x => x !== id);
  await kv.put(listKey, JSON.stringify(list));

  // Also remove from shared if present
  await kv.delete('shared_preset:' + id);

  return json({ ok: true }, 200, origin);
}

async function handlePresetShare(request, env, origin, id) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.get !== 'function') return json({ error: 'KV not available' }, 500, origin);

  const data = await kv.get('preset:' + id);
  if (!data) return json({ error: 'Not found' }, 404, origin);
  const existing = JSON.parse(data);

  const userEmail = sess.user?.email || '';
  if (existing.ownerEmail !== userEmail) {
    return json({ error: 'Not your preset' }, 403, origin);
  }

  await kv.put('shared_preset:' + id, JSON.stringify(existing));
  return json({ ok: true, shareId: id }, 200, origin);
}

async function handlePresetSharedList(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.list !== 'function') return json({ presets: [] }, 200, origin);

  const presets = [];
  try {
    const list = await kv.list({ prefix: 'shared_preset:' });
    for (const item of list.keys) {
      const data = await kv.get(item.name);
      if (data) {
        const p = JSON.parse(data);
        const userEmail = sess.user?.email || '';
        if (p.ownerEmail !== userEmail) {
          presets.push(p);
        }
      }
    }
  } catch (e) {}

  return json({ presets }, 200, origin);
}

async function handlePresetSharedDelete(request, env, origin, id) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.delete !== 'function') return json({ ok: true }, 200, origin);

  // SECURITY: Only the original owner or an admin can unshare a preset.
  // Previously any authenticated user could delete ANY shared preset (IDOR).
  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  const data = await kv.get('shared_preset:' + id);
  if (data) {
    const preset = JSON.parse(data);
    if (preset.ownerEmail !== userEmail && !roleInfo.isAdmin) {
      return json({ error: 'Only the owner or an admin can unshare this preset' }, 403, origin);
    }
  }
  await kv.delete('shared_preset:' + id);
  return json({ ok: true }, 200, origin);
}

async function handlePresetSharedImport(request, env, origin, id) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const kv = env.KV_SESSIONS;
  if (!kv || typeof kv.get !== 'function') return json({ error: 'KV not available' }, 500, origin);

  const data = await kv.get('shared_preset:' + id);
  if (!data) return json({ error: 'Not found' }, 404, origin);
  const original = JSON.parse(data);

  const userEmail = sess.user?.email || '';
  const newId = 'preset_' + Date.now() + '_' + Math.random().toString(36).substring(2, 8);
  const imported = {
    ...original,
    id: newId,
    ownerEmail: userEmail,
    ownerName: sess.user?.name || '',
    importedFrom: original.ownerEmail,
    importedAt: Date.now()
  };

  await kv.put('preset:' + newId, JSON.stringify(imported));
  const listKey = 'presets:' + userEmail;
  let list = await kv.get(listKey);
  list = list ? JSON.parse(list) : [];
  list.push(newId);
  await kv.put(listKey, JSON.stringify(list));

  return json({ ok: true, id: newId }, 200, origin);
}

// —— Secured Firebase proxy ————————————————————
// Routes Firebase chat writes through the worker so we can:
//   1. Verify the user is authenticated
//   2. Stamp their name/email from the session (prevents impersonation)
//   3. Apply per-class rate limiting
//   4. Sanitize the message text

async function handleFirebaseChatPost(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  // Rate limit: 30 chat messages per minute per user
  const rl = rateCheck(sess.user?.email || 'anon', 'livechat', 30);
  if (!rl.allowed) {
    return json({ error: 'Rate limit — wait ' + rl.retryAfter + 's.' }, 429, origin);
  }

  const { class: className, text, mode } = await request.json();
  if (!className || !text) return json({ error: 'Missing class or text' }, 400, origin);

  // Sanitize: truncate, strip HTML
  const safeText = String(text).substring(0, 500).replace(/[<>]/g, '');
  if (safeText.length < 1) return json({ error: 'Empty message' }, 400, origin);

  // Moderate via Groq
  let appropriate = true, flaggedText = safeText;
  try {
    const r = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${env.GROQ_KEY}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: 'llama-3.3-70b-versatile',
        messages: [
          { role: 'system', content: 'You are a school chat moderator. Check if the student\'s message is appropriate for a school environment. Respond with JSON: {"appropriate": true} or {"appropriate": false, "rephrased": "cleaned version"}. Check for: profanity, bullying, cheating answers, inappropriate content, spam. Be lenient with casual language but strict on harmful content.' },
          { role: 'user', content: safeText }
        ],
        temperature: 0.1,
        max_tokens: 200,
        response_format: { type: 'json_object' }
      })
    });
    const data = await r.json();
    const content = data.choices[0]?.message?.content || '{"appropriate": true}';
    const result = JSON.parse(content);
    appropriate = result.appropriate !== false;
    if (!appropriate && result.rephrased) flaggedText = String(result.rephrased).substring(0, 500);
  } catch (e) { /* fail open */ }

  // Build the message object — name/email come from session, NOT client
  const msg = {
    name: sess.user?.name || 'Student',
    email: sess.user?.email || '',
    role: sess.user?.role || 'student',
    text: appropriate ? safeText : flaggedText,
    flagged: !appropriate,
    ts: Date.now()
  };

  // Push to Firebase
  const safeClass = String(className).replace(/[^a-zA-Z0-9_]/g, '_');
  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return json({ error: 'Firebase not configured' }, 500, origin);

  try {
    const r = await fetch(`${dbUrl}/liveClasses/${safeClass}/chat.json`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(msg)
    });
    const data = await r.json();
    return json({ ok: true, id: data.name, flagged: !appropriate }, 200, origin);
  } catch (e) {
    return json({ error: 'Firebase write failed: ' + e.message }, 500, origin);
  }
}

// —— Secured Firebase hand-raise ————————————————————

async function handleFirebaseHandRaise(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const { class: className, action } = await request.json();
  if (!className || !action) return json({ error: 'Missing class or action' }, 400, origin);

  const safeClass = String(className).replace(/[^a-zA-Z0-9_]/g, '_');
  // Use email as the hand ID — one hand per user
  const handId = (sess.user?.email || 'anon').replace(/[^a-zA-Z0-9_]/g, '_');
  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return json({ error: 'Firebase not configured' }, 500, origin);

  try {
    if (action === 'raise') {
      await fetch(`${dbUrl}/liveClasses/${safeClass}/hands/${handId}.json`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: sess.user?.name || 'Student',
          email: sess.user?.email || '',
          raisedAt: Date.now(),
          called: false
        })
      });
      return json({ ok: true, raised: true }, 200, origin);
    } else if (action === 'lower') {
      await fetch(`${dbUrl}/liveClasses/${safeClass}/hands/${handId}.json`, {
        method: 'DELETE'
      });
      return json({ ok: true, raised: false }, 200, origin);
    }
    return json({ error: 'Invalid action' }, 400, origin);
  } catch (e) {
    return json({ error: 'Firebase error: ' + e.message }, 500, origin);
  }
}

// —— Transcript verification ————————————————————
// transcript.js sends this secret to prove it's the authentic script.
// The secret is stored as a Cloudflare secret (TRANSCRIPT_SECRET).
// Without it, the script can't save transcripts to Firebase via the worker.
// If someone gets transcript.js, they still can't misuse it without this secret.

async function handleTranscriptVerify(request, env, origin) {
  const { secret, videoId, transcript, summary, className } = await request.json();

  // Verify the shared secret
  if (!secret || secret !== env.TRANSCRIPT_SECRET) {
    return json({ error: 'Invalid transcript secret' }, 403, origin);
  }

  if (!videoId || !transcript) {
    return json({ error: 'Missing videoId or transcript' }, 400, origin);
  }

  // Save to Firebase via the worker (not directly from the script)
  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return json({ error: 'Firebase not configured' }, 500, origin);

  const safeVideoId = String(videoId).replace(/[^a-zA-Z0-9_-]/g, '');

  try {
    await fetch(`${dbUrl}/transcripts/${safeVideoId}.json`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        transcript: String(transcript).substring(0, 100000),
        summary: String(summary || '').substring(0, 10000),
        class: String(className || 'Unknown').substring(0, 50),
        videoId: safeVideoId,
        generatedAt: new Date().toISOString(),
        verified: true,
      }),
    });
    return json({ ok: true, videoId: safeVideoId }, 200, origin);
  } catch (e) {
    return json({ error: 'Firebase save failed: ' + e.message }, 500, origin);
  }
}

// —— Developer-only: Revoke admin powers ————————————————
async function handleAdminRevoke(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);
  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  if (!roleInfo.isDeveloper) return json({ error: 'Developer access required' }, 403, origin);
  const { email: targetEmail } = await request.json();
  if (!targetEmail) return json({ error: 'Missing email' }, 400, origin);
  const targetLower = String(targetEmail).toLowerCase().trim();
  if (HARDCODED_ADMIN_EMAILS.includes(targetLower)) return json({ error: 'Cannot revoke a hardcoded admin' }, 403, origin);
  if (targetLower === userEmail.toLowerCase()) return json({ error: 'Cannot revoke your own admin powers' }, 403, origin);
  try {
    const ownerToken = await getOwnerToken(env);
    const headers = { Authorization: `Bearer ${ownerToken}` };
    const aQ = "name='ADMINS' and mimeType='application/vnd.google-apps.folder' and trashed=false";
    const aRes = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(aQ)}&fields=files(id)&pageSize=1`, { headers });
    const aData = await aRes.json();
    if (!aData.files || aData.files.length === 0) return json({ error: 'ADMINS folder not found' }, 404, origin);
    const aFolder = aData.files[0].id;
    const userQ = `'${aFolder}' in parents and trashed=false and (name='${targetLower}' or name='${targetLower.split('@')[0]}')`;
    const userRes = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(userQ)}&fields=files(id,name)&pageSize=10`, { headers });
    const userData = await userRes.json();
    if (!userData.files || userData.files.length === 0) return json({ error: 'User is not an admin or already revoked' }, 404, origin);
    let deleted = 0;
    for (const f of userData.files) { await fetch(`https://www.googleapis.com/drive/v3/files/${f.id}`, { method: 'DELETE', headers }); deleted++; }
    if (env.KV_SESSIONS && typeof env.KV_SESSIONS.delete === 'function') { try { await env.KV_SESSIONS.delete('role:' + targetLower); } catch (e) {} }
    return json({ ok: true, deleted, revoked: targetLower }, 200, origin);
  } catch (e) { return json({ error: 'Revoke failed: ' + e.message }, 500, origin); }
}

// —— Developer-only: Promote a teacher to admin ————————————————
async function handleAdminPromote(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);
  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  if (!roleInfo.isDeveloper) return json({ error: 'Developer access required' }, 403, origin);
  const { email: targetEmail } = await request.json();
  if (!targetEmail) return json({ error: 'Missing email' }, 400, origin);
  const targetLower = String(targetEmail).toLowerCase().trim();
  if (!targetLower.includes('@')) return json({ error: 'Invalid email' }, 400, origin);
  try {
    const ownerToken = await getOwnerToken(env);
    const headers = { Authorization: `Bearer ${ownerToken}` };
    const aQ = "name='ADMINS' and mimeType='application/vnd.google-apps.folder' and trashed=false";
    const aRes = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(aQ)}&fields=files(id)&pageSize=1`, { headers });
    const aData = await aRes.json();
    let aFolder;
    if (!aData.files || aData.files.length === 0) {
      const crRes = await fetch('https://www.googleapis.com/drive/v3/files?fields=id,name', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify({ name: 'ADMINS', mimeType: 'application/vnd.google-apps.folder' }) });
      aFolder = (await crRes.json()).id;
    } else { aFolder = aData.files[0].id; }
    const checkQ = `'${aFolder}' in parents and trashed=false and name='${targetLower}'`;
    const checkRes = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(checkQ)}&fields=files(id)&pageSize=1`, { headers });
    const checkData = await checkRes.json();
    if (checkData.files && checkData.files.length > 0) return json({ ok: true, already: true, message: 'Already an admin' }, 200, origin);
    await fetch('https://www.googleapis.com/drive/v3/files?fields=id,name', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify({ name: targetLower, parents: [aFolder] }) });
    if (env.KV_SESSIONS && typeof env.KV_SESSIONS.delete === 'function') { try { await env.KV_SESSIONS.delete('role:' + targetLower); } catch (e) {} }
    return json({ ok: true, promoted: targetLower }, 200, origin);
  } catch (e) { return json({ error: 'Promote failed: ' + e.message }, 500, origin); }
}

// —— Role management: promote / demote / ban (with folder moves) ————
async function handleChangeUserRole(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);
  const requesterEmail = sess.user?.email || '';
  const requesterRole = await verifyRole(env, requesterEmail);
  if (!requesterRole.isAdmin && !requesterRole.isDeveloper) return json({ error: 'Admin or developer access required' }, 403, origin);
  const { email: targetEmail, action } = await request.json();
  if (!targetEmail || !action) return json({ error: 'Missing email or action' }, 400, origin);
  const targetLower = String(targetEmail).toLowerCase().trim();
  if (!targetLower.includes('@')) return json({ error: 'Invalid email' }, 400, origin);
  const validActions = ['promote-teacher', 'promote-admin', 'demote-teacher', 'demote-student', 'ban'];
  if (!validActions.includes(action)) return json({ error: 'Invalid action' }, 400, origin);
  if (targetLower === requesterEmail.toLowerCase()) return json({ error: 'Cannot change your own role' }, 403, origin);
  if (HARDCODED_ADMIN_EMAILS.includes(targetLower)) return json({ error: 'Cannot modify a hardcoded admin' }, 403, origin);
  const targetRole = await verifyRole(env, targetLower);
  if (targetRole.isAdmin && !requesterRole.isDeveloper) return json({ error: 'Only developers can manage admins' }, 403, origin);
  try {
    const ownerToken = await getOwnerToken(env);
    const headers = { Authorization: `Bearer ${ownerToken}` };
    async function findFolder(name) { const q = `name='${name}' and mimeType='application/vnd.google-apps.folder' and trashed=false`; const r = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(q)}&fields=files(id)&pageSize=1`, { headers }); const d = await r.json(); return d.files && d.files.length > 0 ? d.files[0].id : null; }
    async function findFileInFolder(folderId, email) { if (!folderId) return []; const q = `'${folderId}' in parents and trashed=false and (name='${email}' or name='${email.split('@')[0]}')`; const r = await fetch(`https://www.googleapis.com/drive/v3/files?q=${encodeURIComponent(q)}&fields=files(id,name)&pageSize=10`, { headers }); const d = await r.json(); return d.files || []; }
    async function deleteFiles(files) { for (const f of files) { await fetch(`https://www.googleapis.com/drive/v3/files/${f.id}`, { method: 'DELETE', headers }); } }
    async function createFileInFolder(folderId, email) { if (!folderId) return null; const r = await fetch('https://www.googleapis.com/drive/v3/files?fields=id,name', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify({ name: email, parents: [folderId] }) }); return (await r.json()).id; }
    const studentsFolder = await findFolder('STUDENTS');
    const teachersFolder = await findFolder('TEACHERS');
    const adminsFolder = await findFolder('ADMINS');
    let result = { ok: true, action, email: targetLower };
    if (action === 'ban') { const s = await findFileInFolder(studentsFolder, targetLower); const t = await findFileInFolder(teachersFolder, targetLower); const a = await findFileInFolder(adminsFolder, targetLower); await deleteFiles([...s, ...t, ...a]); result.message = 'Banned'; }
    else if (action === 'promote-teacher') { const s = await findFileInFolder(studentsFolder, targetLower); await deleteFiles(s); const t = await findFileInFolder(teachersFolder, targetLower); if (t.length === 0) await createFileInFolder(teachersFolder, targetLower); result.message = 'Promoted to Teacher'; }
    else if (action === 'promote-admin') { const t = await findFileInFolder(teachersFolder, targetLower); if (t.length === 0) await createFileInFolder(teachersFolder, targetLower); const a = await findFileInFolder(adminsFolder, targetLower); if (a.length === 0) await createFileInFolder(adminsFolder, targetLower); result.message = 'Promoted to Admin'; }
    else if (action === 'demote-teacher') { const a = await findFileInFolder(adminsFolder, targetLower); await deleteFiles(a); const t = await findFileInFolder(teachersFolder, targetLower); if (t.length === 0) await createFileInFolder(teachersFolder, targetLower); result.message = 'Demoted to Teacher'; }
    else if (action === 'demote-student') { const t = await findFileInFolder(teachersFolder, targetLower); const a = await findFileInFolder(adminsFolder, targetLower); await deleteFiles([...t, ...a]); const s = await findFileInFolder(studentsFolder, targetLower); if (s.length === 0) await createFileInFolder(studentsFolder, targetLower); result.message = 'Demoted to Student'; }
    if (env.KV_SESSIONS && typeof env.KV_SESSIONS.delete === 'function') { try { await env.KV_SESSIONS.delete('role:' + targetLower); } catch (e) {} }
    return json(result, 200, origin);
  } catch (e) { return json({ error: 'Role change failed: ' + e.message }, 500, origin); }
}

// —— Teacher-only Firebase actions (secured via worker) ————————————
async function handleLiveChatAction(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);
  const userEmail = sess.user?.email || '';
  const roleInfo = await verifyRole(env, userEmail);
  if (roleInfo.role !== 'teacher' && !roleInfo.isAdmin) return json({ error: 'Teacher or admin access required' }, 403, origin);
  const { action, class: className, msgId, handId, mode, studentName } = await request.json();
  if (!action || !className) return json({ error: 'Missing action or class' }, 400, origin);
  const safeClass = String(className).replace(/[^a-zA-Z0-9_]/g, '_');
  const dbUrl = env.FIREBASE_DB_URL;
  if (!dbUrl) return json({ error: 'Firebase not configured' }, 500, origin);
  try {
    if (action === 'approve-msg' && msgId) { await fetch(`${dbUrl}/liveClasses/${safeClass}/chat/${msgId}/flagged.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: 'false' }); return json({ ok: true }, 200, origin); }
    if (action === 'delete-msg' && msgId) { await fetch(`${dbUrl}/liveClasses/${safeClass}/chat/${msgId}.json`, { method: 'DELETE' }); return json({ ok: true }, 200, origin); }
    if (action === 'set-mode' && mode) { await fetch(`${dbUrl}/liveClasses/${safeClass}/chatMode.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(mode) }); return json({ ok: true }, 200, origin); }
    if (action === 'call-student' && handId) {
      await fetch(`${dbUrl}/liveClasses/${safeClass}/hands/${handId}/called.json`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: 'true' });
      const sysMsg = { name: 'System', text: `🎤 ${studentName || 'Student'}, you may speak now.`, system: true, ts: Date.now() };
      await fetch(`${dbUrl}/liveClasses/${safeClass}/chat.json`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(sysMsg) });
      setTimeout(async () => { try { await fetch(`${dbUrl}/liveClasses/${safeClass}/hands/${handId}.json`, { method: 'DELETE' }); } catch (e) {} }, 30000);
      return json({ ok: true }, 200, origin);
    }
    if (action === 'skip-hand' && handId) { await fetch(`${dbUrl}/liveClasses/${safeClass}/hands/${handId}.json`, { method: 'DELETE' }); return json({ ok: true }, 200, origin); }
    return json({ error: 'Invalid action' }, 400, origin);
  } catch (e) { return json({ error: 'Firebase action failed: ' + e.message }, 500, origin); }
}

// —— Main fetch handler ——————————————————————————

export default {
  async fetch(request, env) {
    const origin = request.headers.get('Origin') || '';
    const url = new URL(request.url);
    const path = url.pathname;

    // Preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders(origin) });
    }

    // CSRF defense — reject state-changing requests from disallowed origins.
    // OAuth callback (/callback) is exempt because Google redirects without an Origin header.
    if (path !== '/callback') {
      const csrf = csrfCheck(request, origin);
      if (csrf) return csrf;
    }

    // Existing routes
    if (path === '/login') return handleLogin(env, origin);
    if (path === '/callback') return handleCallback(request, env);
    if (path === '/me') return handleMe(request, env, origin);
    if (path === '/token') return handleToken(request, env, origin);
    if (path === '/logout') return handleLogout(request, env, origin);
    if (path === '/config') return handleConfig(env, origin);
    if (path.startsWith('/drive')) return handleDrive(request, env, origin, path);

    // AI routes
    if (path === '/api/chat' && request.method === 'POST') return handleAIChat(request, env, origin);
    if (path === '/api/quota' && request.method === 'GET') return handleQuota(request, env, origin);
    if (path === '/api/pdf' && request.method === 'POST') return handlePDF(request, env, origin);
    if (path === '/api/tts' && request.method === 'POST') return handleTTS(request, env, origin);

    // User profile routes (name + photo saved to Firebase)
    if (path === '/api/user/profile' && request.method === 'GET') return handleUserProfileGet(request, env, origin);
    if (path === '/api/user/profile' && request.method === 'POST') return handleUserProfileSet(request, env, origin);

    // Transcript verification (secure — requires shared secret)
    if (path === '/api/transcript/verify' && request.method === 'POST') return handleTranscriptVerify(request, env, origin);

    // Developer-only routes (admin promote/revoke)
    if (path === '/api/admin/revoke' && request.method === 'POST') return handleAdminRevoke(request, env, origin);
    if (path === '/api/admin/promote' && request.method === 'POST') return handleAdminPromote(request, env, origin);

    // Role management (promote/demote/ban with folder moves)
    if (path === '/api/user/role' && request.method === 'POST') return handleChangeUserRole(request, env, origin);

    // Teacher-only live chat actions (secured via worker)
    if (path === '/api/live/action' && request.method === 'POST') return handleLiveChatAction(request, env, origin);

    // Artifact + folder routes (legacy — drive-based)
    if (path === '/api/artifact' && request.method === 'POST') {
      // Simple pass-through to drive upload
      return handleDrive(request, env, origin, '/drive/upload');
    }
    if (path === '/api/ensure-folder' && request.method === 'POST') {
      return json({ ok: true }, 200, origin);
    }

    // Live class routes
    if (path === '/api/live/start' && request.method === 'POST') return handleLiveStart(request, env, origin);
    if (path === '/api/live/status' && request.method === 'GET') return handleLiveStatus(request, env, origin);
    if (path === '/api/live/status-all' && request.method === 'GET') return handleLiveStatusAll(request, env, origin);
    if (path === '/api/live/extend' && request.method === 'POST') return handleLiveExtend(request, env, origin);
    if (path === '/api/live/end' && request.method === 'POST') return handleLiveEnd(request, env, origin);
    if (path === '/api/live/recordings' && request.method === 'GET') return handleLiveRecordings(request, env, origin);
    if (path === '/api/live/chat/moderate' && request.method === 'POST') return handleChatModerate(request, env, origin);

    // Secured Firebase chat + hand-raise (auth required, session-stamped identity)
    if (path === '/api/live/chat/post' && request.method === 'POST') return handleFirebaseChatPost(request, env, origin);
    if (path === '/api/live/hand' && request.method === 'POST') return handleFirebaseHandRaise(request, env, origin);

    // Schedule routes
    if (path === '/api/schedule/set' && request.method === 'POST') return handleScheduleSet(request, env, origin);
    if (path === '/api/schedule' && request.method === 'GET') return handleScheduleGet(request, env, origin);

    // Preset routes
    if (path === '/api/presets/create' && request.method === 'POST') return handlePresetCreate(request, env, origin);
    if (path === '/api/presets/my' && request.method === 'GET') return handlePresetMy(request, env, origin);
    if (path === '/api/presets/shared' && request.method === 'GET') return handlePresetSharedList(request, env, origin);

    // Preset routes with IDs
    const presetMatch = path.match(/^\/api\/presets\/([^/]+)$/);
    if (presetMatch) {
      const id = presetMatch[1];
      if (request.method === 'PUT') return handlePresetUpdate(request, env, origin, id);
      if (request.method === 'DELETE') return handlePresetDelete(request, env, origin, id);
      if (request.method === 'POST') return handlePresetShare(request, env, origin, id);
    }

    const presetSharedMatch = path.match(/^\/api\/presets\/shared\/([^/]+)$/);
    if (presetSharedMatch) {
      const id = presetSharedMatch[1];
      if (request.method === 'DELETE') return handlePresetSharedDelete(request, env, origin, id);
      if (request.method === 'POST') return handlePresetSharedImport(request, env, origin, id);
    }

    return json({ ok: true, message: 'XavierDrive Worker' }, 200, origin);
  },

  // —— Cron Trigger: Auto-end broadcasts over 3 hours ————
  async scheduled(event, env) {
    const kv = env.KV_SESSIONS;
    if (!kv || typeof kv.list !== 'function') return;

    const MAX_DURATION = 3 * 60 * 60 * 1000; // 3 hours

    try {
      const list = await kv.list({ prefix: 'live:' });
      for (const item of list.keys) {
        const className = item.name.replace('live:', '');
        const data = await kv.get(item.name);
        if (!data) continue;

        const state = JSON.parse(data);
        const elapsed = Date.now() - state.startedAt;

        if (elapsed > MAX_DURATION) {
          console.log('Auto-ending ' + className + ' (exceeded 3 hours)');
          try {
            const ytToken = await getYouTubeToken(env);
            await fetch(`https://www.googleapis.com/youtube/v3/liveBroadcasts/transition?broadcastStatus=complete&id=${state.broadcastId}&part=snippet,status`, {
              method: 'POST',
              headers: { Authorization: `Bearer ${ytToken}` }
            });
            const playlistId = await ensurePlaylist(env, className);
            await fetch('https://www.googleapis.com/youtube/v3/playlistItems?part=snippet', {
              method: 'POST',
              headers: { Authorization: `Bearer ${ytToken}`, 'Content-Type': 'application/json' },
              body: JSON.stringify({ snippet: { playlistId, resourceId: { kind: 'youtube#video', videoId: state.videoId } } })
            });
          } catch (e) { console.error('Auto-end failed for ' + className + ':', e); }

          await kv.delete('live:' + className);
          await firebaseDelete(env, 'liveClasses/' + className.replace(/[^a-zA-Z0-9_]/g, '_'));
        }
      }
    } catch (e) { console.error('Cron error:', e); }
  },
};
