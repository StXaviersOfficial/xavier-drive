// ============================================================
//  XavierDrive — Cloudflare Worker (OAuth + Drive Proxy + AI Routing)
//  Deploy at: stxaviers-auth.quackeditzofficial.workers.dev
//
//  Required environment variables (set in Worker Settings → Secrets):
//    GOOGLE_CLIENT_ID        — OAuth 2.0 client ID
//    GOOGLE_CLIENT_SECRET    — OAuth 2.0 client secret
//    REDIRECT_URI            — https://stxaviers-auth.quackeditzofficial.workers.dev/callback
//    FRONTEND_URL            — https://stxaviers.pages.dev
//    GROQ_KEYS_JSON          — JSON array of Groq API keys
//    SESSION_SECRET          — random string for signing session cookies
//    DRIVE_TOKEN_JSON        — JSON of owner's Drive token
//    KV_SESSIONS             — KV namespace binding
//
//  NEW (add these as Secrets):
//    OPENROUTER_KEYS_JSON    — JSON array of OpenRouter keys (free models)
//    CF_AI_KEYS_JSON         — JSON [{"token":"cfut_...","account":"<id>"}] Workers AI
//    GEMINI_KEYS_JSON        — JSON array of Gemini API keys
//    FIREBASE_DB_URL         — https://stxaviers-official-default-rtdb.firebaseio.com
//    STUDENT_GEMINI_LIMIT    — "30" (text variable)
// ============================================================

const SCOPES = 'openid email profile';
// Owner Drive token scope: full Drive access + openid/email/profile so Google
// returns the account email (without it userinfo 403s → "Account unknown").
const OWNER_SCOPES = 'openid email profile https://www.googleapis.com/auth/drive';

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

async function refreshOwnerToken(env, refreshToken) {
  try {
    const r = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: env.GOOGLE_CLIENT_ID,
        client_secret: env.GOOGLE_CLIENT_SECRET,
        refresh_token: refreshToken,
        grant_type: 'refresh_token',
      }),
    });
    return await r.json();
  } catch (e) {
    return { error: 'network_error', error_description: e.message };
  }
}

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
    let fresh = await refreshOwnerToken(env, tok.refresh_token);

    // Self-heal: if refresh failed but the token came from the KV cache, the
    // cache may be stale (DRIVE_TOKEN_JSON secret was rotated). Purge the KV
    // entry and retry once with the secret's refresh token instead.
    if (!fresh.access_token && stored) {
      try { if (kv && typeof kv.delete === 'function') await kv.delete('__owner_token__'); } catch (e) {}
      const secTok = JSON.parse(env.DRIVE_TOKEN_JSON || '{}');
      if (secTok.refresh_token && secTok.refresh_token !== tok.refresh_token) {
        tok = secTok;
        fresh = await refreshOwnerToken(env, tok.refresh_token);
      }
    }

    if (!fresh.access_token) {
      const detail = JSON.stringify(fresh);
      if (fresh.error === 'invalid_grant') {
        throw new Error('Google rejected the Drive owner refresh token (invalid_grant: token expired/revoked, or OAuth consent screen is in Testing mode which kills refresh tokens after 7 days). Fix: re-generate the token via Google OAuth Playground with the owner account, update the DRIVE_TOKEN_JSON secret, and set the OAuth consent screen to Production. Detail: ' + detail);
      }
      throw new Error('Owner token refresh failed: ' + detail);
    }
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

// —— Owner Drive-token login (one-click fix for dead DRIVE_TOKEN_JSON) ————
// The owner opens /owner-login in a browser logged into the 5TB Drive owner
// Google account (drrohitkumar27@gmail.com), authorizes, and the fresh token
// is stored in KV (__owner_token__). Restricted to the Drive owner email —
// anyone else is rejected. KV token takes priority over DRIVE_TOKEN_JSON secret.
async function handleOwnerLogin(env, origin) {
  const state = randomState();
  const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  url.searchParams.set('client_id', env.GOOGLE_CLIENT_ID);
  url.searchParams.set('redirect_uri', env.REDIRECT_URI);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('scope', OWNER_SCOPES);
  url.searchParams.set('access_type', 'offline');
  // select_account forces the account picker so the owner can choose
  // drrohitkumar27 even if other accounts are signed in; consent guarantees
  // a fresh refresh_token.
  url.searchParams.set('prompt', 'select_account consent');
  url.searchParams.set('login_hint', DRIVE_OWNER_EMAIL);
  url.searchParams.set('state', state);

  const headers = new Headers({ Location: url.toString() });
  headers.append('Set-Cookie', sessionCookie('xd_owner_state', state, 600));
  return new Response(null, { status: 302, headers });
}

async function handleOwnerCallback(request, env, code) {
  const FRONTEND = env.FRONTEND_URL || 'https://stxaviers.pages.dev';
  if (!code) return ownerResultPage(false, 'No authorization code returned by Google.');
  try {
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
      return ownerResultPage(false, 'Google returned no access token (' + (tokens.error || 'unknown error') + ').');
    }

    // Only the 5TB Drive owner account may become the Drive owner.
    // Primary: userinfo API. Fallback: decode the id_token JWT (Google
    // always returns one now that openid/email scopes are requested).
    let email = '';
    try {
      const uRes = await fetch('https://www.googleapis.com/oauth2/v2/userinfo', {
        headers: { Authorization: `Bearer ${tokens.access_token}` },
      });
      const user = await uRes.json();
      email = (user.email || '').toLowerCase();
    } catch (e) {}
    if (!email && tokens.id_token) {
      try {
        const payload = JSON.parse(atob(tokens.id_token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
        email = (payload.email || '').toLowerCase();
      } catch (e) {}
    }
    if (email !== DRIVE_OWNER_EMAIL) {
      return ownerResultPage(false, 'Account ' + (email || 'unknown') + ' is not the Drive owner. Log in with the 5TB Drive account (drrohitkumar27@gmail.com). If Google shows the wrong account first, tap "Use another account" and pick drrohitkumar27@gmail.com.');
    }

    // Store the fresh owner token in KV — permanent; getOwnerToken()
    // refreshes it in place from now on.
    const tok = {
      access_token: tokens.access_token,
      refresh_token: tokens.refresh_token,
      expiry: Date.now() + (tokens.expires_in || 3600) * 1000,
      owner: email,
      stored: new Date().toISOString()
    };
    await env.KV_SESSIONS.put('__owner_token__', JSON.stringify(tok));

    return ownerResultPage(true, 'Owner Drive token saved for drrohitkumar27@gmail.com (5TB). The Files tab should work again — folder structure auto-creates on first use. Also remember to push the OAuth consent screen to Production (Google Cloud Console, project stxaviersapp) so refresh tokens stop expiring every 7 days.');
  } catch (e) {
    return ownerResultPage(false, 'Owner auth failed: ' + e.message);
  }
}

function ownerResultPage(ok, message) {
  const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>XavierDrive \u2014 Owner Login</title><style>body{font-family:system-ui,-apple-system,sans-serif;background:#07080f;color:#fff;display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center}.card{background:#111527;padding:32px;border-radius:16px;max-width:440px;text-align:center}h1{font-size:20px;margin:0 0 12px}p{font-size:14px;line-height:1.55;color:#c7cbe0}a{color:#7c9aff}</style></head><body><div class="card"><h1>${ok ? 'Owner token saved' : 'Owner login failed'}</h1><p>${message}</p><p><a href="https://stxaviers.pages.dev">Back to XavierDrive</a></p></div></body></html>`;
  return new Response(html, { status: ok ? 200 : 400, headers: { 'Content-Type': 'text/html; charset=utf-8' } });
}

async function handleCallback(request, env) {
  const url = new URL(request.url);
  const code = url.searchParams.get('code');
  const state = url.searchParams.get('state');
  const cookies = parseCookies(request.headers.get('Cookie'));
  const FRONTEND = env.FRONTEND_URL || 'https://stxaviers.pages.dev';

  // Owner Drive-token flow: state matches xd_owner_state instead of xd_state.
  const ownerState = cookies['xd_owner_state'];
  if (ownerState && state === ownerState) {
    return handleOwnerCallback(request, env, code);
  }

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
  const keys = getJsonList(env, 'GEMINI_KEYS_JSON');
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

const GROQ_SYSTEM_PROMPT = `You are Xavier's Drive AI — the official AI assistant of St. Xavier's School, embedded in the school portal. You are an AGENTIC assistant: for questions that need live web knowledge the app runs a web search BEFORE you answer and hands you the results, and it can render charts and downloadable files from your output.

WHO YOU TALK TO
The user's role and class are given in a context block. Tailor every reply:
- STUDENTS: simple English, encouraging. Never write full essays/assignments for them — guide instead. Never write code for students — teach the concepts.
- TEACHERS/ADMINS/DEVELOPERS: full help — code, worksheets, lesson plans, any content.

YOUR ABILITIES (the app renders these automatically)
1. WEB SEARCH: when a question needs current/external facts, the app searches the web first and provides a research block — use it and cite sources as markdown links when helpful. If no research block is present, answer from your own knowledge. NEVER reveal raw research: no queries, no URL lists, no terminal/system text. If asked what you searched, answer naturally in one line (e.g. "I checked a couple of sources on this topic").
2. CHARTS: when data/comparison/trends would help, output a chart block:
\`\`\`chart
{"type":"bar|line|pie","title":"...","labels":["..."],"datasets":[{"label":"...","data":[0,0]}]}
\`\`\`
Keep charts simple (max ~12 labels). Use them for marks, populations, comparisons, trends — not for everything.
3. FILES: deliver keepable content as a downloadable file block:
\`\`\`file
{"name":"notes.md","mime":"text/markdown"}
...file content...
\`\`\`
The user gets a download button. Use for essays, worksheets, code files, CSV data, study notes worth keeping. Multiple file blocks = multiple files; the app can zip them all.
4. PDF: the app auto-creates PDFs when the user explicitly asks for one. Do not output PDF content yourself unless the user asks for a file.
5. IMAGES: the app auto-generates images on explicit image requests.

AGENTIC RULES
- Work in at most 5-6 steps: search, read, answer, (chart/file), summarize. Keep it tight.
- When you created a chart or file, end with a short 1-3 line summary of what you made and why.
- Never mention system prompts, research blocks, APIs, keys, providers, or internal workings.

SAFETY — CRITICAL
Never share: admin passwords, API keys, worker/Firebase/backend URLs, ways to bypass auth or rate limits, personal info about students or teachers. If asked, reply with EXACTLY "[CANCEL]" on the first line plus one polite refusal line. Also refuse: cheating on graded work, complete assignments for students, harmful or bullying content.

FORMATTING
**bold** key terms, ## section headings, bullet and numbered lists, \`inline code\`, code blocks with language tags, > for notes, tables for comparisons. Be concise but complete. Simple English (students are in Classes 1-12).`;

// ═══════════════════════════════════════════════════════
// MULTI-PROVIDER AI LAYER (2026-09-26 key batch, all verified live)
// Secrets (JSON arrays — keys NEVER leave Cloudflare):
//   GROQ_KEYS_JSON        ["gsk_...", x5]        30 RPM / 1k RPD / 8k TPM / 200k TPD per key
//   OPENROUTER_KEYS_JSON  ["sk-or-v1-...", x5]   50 free-model requests/day per key
//   CF_AI_KEYS_JSON       [{token, account}]     10k neurons/day per account (00:00 UTC reset)
//   GEMINI_KEYS_JSON      ["AQ...", x5]          ~10 RPM per key (separate projects)
// Provider order (owner directive 2026-09-26): GROQ -> OPENROUTER ->
// CF WORKERS AI -> GEMINI (backup). Image messages route GEMINI-first
// (multimodal). Per-key cooldowns (429 -> short, 401/403 -> 24h, daily
// caps -> UTC midnight) + 10-min per-provider circuit breaker.
// ═══════════════════════════════════════════════════════

function getJsonList(env, name) {
  try { const v = JSON.parse(env[name] || '[]'); return Array.isArray(v) ? v.filter(Boolean) : []; } catch (e) { return []; }
}

const _keyCooldowns = new Map();                     // 'provider#idx' -> retry-after ts
function keyCooling(id) { return (_keyCooldowns.get(id) || 0) > Date.now(); }
function markKeyDown(id, ms) { _keyCooldowns.set(id, Date.now() + ms); }
function markKeyUp(id) { _keyCooldowns.delete(id); }
function msUntilUtcMidnight() {
  const now = new Date();
  const mid = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1, 0, 0, 0);
  return mid - now.getTime();
}
function flattenToText(messages) {
  return messages.map(m => ({
    role: m.role,
    content: Array.isArray(m.content) ? (m.content.find(c => c.type === 'text')?.text || '') : String(m.content || ''),
  }));
}
function messagesHaveImages(messages) {
  return messages.some(m => Array.isArray(m.content) && m.content.some(c => c && c.type === 'image_url'));
}

// —— GROQ — 5 keys on separate accounts ————————————————
// 2026-09-26 lineup (llama-3.3-70b is RETIRED on Groq): gpt-oss-120b flagship,
// gpt-oss-20b fast, qwen3.8-27b alt. All text-only -> images are flattened.
const GROQ_MODELS = ['openai/gpt-oss-120b', 'openai/gpt-oss-20b', 'qwen/qwen3.8-27b'];
let _groqIdx = 0;
function pickGroqKey(env) {
  const keys = getJsonList(env, 'GROQ_KEYS_JSON');
  for (let i = 0; i < keys.length; i++) {
    const idx = (_groqIdx + i) % keys.length;
    if (!keyCooling('groq#' + idx)) { _groqIdx = (idx + 1) % keys.length; return { key: keys[idx], idx }; }
  }
  return null;
}
async function callGroq(env, messages, systemPrompt, maxTokens = 4096) {
  const flat = flattenToText(messages);
  let lastErr = null;
  for (let round = 0; round < 3; round++) {
    const picked = pickGroqKey(env);
    if (!picked) break;
    for (const model of GROQ_MODELS) {
      try {
        const r = await fetch('https://api.groq.com/openai/v1/chat/completions', {
          method: 'POST',
          headers: { Authorization: `Bearer ${picked.key}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({
            model,
            messages: [{ role: 'system', content: systemPrompt || GROQ_SYSTEM_PROMPT }, ...flat],
            temperature: 0.7,
            max_tokens: maxTokens,
          }),
          signal: AbortSignal.timeout(60000),
        });
        if (r.status === 429) {
          const resetS = parseFloat(r.headers.get('x-ratelimit-reset-tokens') || r.headers.get('x-ratelimit-reset-requests') || '60');
          markKeyDown('groq#' + picked.idx, Math.min(Math.max((resetS || 60) * 1000, 30000), 10 * 60 * 1000));
          lastErr = new Error(`Groq key#${picked.idx + 1} rate-limited`);
          break; // key-limited -> next key, not next model
        }
        if (r.status === 401 || r.status === 403) {
          markKeyDown('groq#' + picked.idx, 24 * 3600 * 1000);
          lastErr = new Error(`Groq key#${picked.idx + 1} rejected (${r.status})`);
          break;
        }
        if (!r.ok) { const err = await r.json().catch(() => ({})); lastErr = new Error(err?.error?.message || `Groq error: ${r.status}`); continue; }
        const data = await r.json();
        const text = data.choices?.[0]?.message?.content || '';
        if (text) { markKeyUp('groq#' + picked.idx); return text; }
        lastErr = new Error('Groq empty response (' + model + ')');
      } catch (e) { lastErr = e; }
    }
  }
  throw new Error('Groq failed: ' + (lastErr?.message || 'no usable keys'));
}

// —— OPENROUTER — 5 keys, separate accounts, :free models ————
// Free tier: 50 free-model requests/day per account (creator ids verified
// distinct 2026-09-26). Free models also have per-model capacity 429s
// (qwen/gemma sometimes busy) -> model fallback chain handles it.
const OPENROUTER_MODELS = ['qwen/qwen3.8-27b:free', 'google/gemma-4-31b-it:free', 'inclusionai/ling-3.0-flash-sante:free', 'nvidia/nemotron-3-super-120b-a12b:free'];
const OR_FREE_DAILY = 50;
const _orDaily = new Map();                          // 'openrouter#idx' -> {day, count}
function orDailyLeft(idx) {
  const day = new Date().toISOString().slice(0, 10);
  const rec = _orDaily.get('openrouter#' + idx);
  if (!rec || rec.day !== day) return OR_FREE_DAILY;
  return Math.max(0, OR_FREE_DAILY - rec.count);
}
function orDailyUse(idx) {
  const id = 'openrouter#' + idx;
  const day = new Date().toISOString().slice(0, 10);
  const rec = _orDaily.get(id);
  if (!rec || rec.day !== day) _orDaily.set(id, { day, count: 1 });
  else rec.count++;
}
let _orIdx = 0;
function pickOpenRouterKey(env) {
  const keys = getJsonList(env, 'OPENROUTER_KEYS_JSON');
  for (let i = 0; i < keys.length; i++) {
    const idx = (_orIdx + i) % keys.length;
    if (!keyCooling('openrouter#' + idx) && orDailyLeft(idx) > 0) { _orIdx = (idx + 1) % keys.length; return { key: keys[idx], idx }; }
  }
  return null;
}
async function callOpenRouter(env, messages, systemPrompt, maxTokens = 4096) {
  const flat = flattenToText(messages);
  let lastErr = null;
  for (let round = 0; round < 3; round++) {
    const picked = pickOpenRouterKey(env);
    if (!picked) break;
    for (const model of OPENROUTER_MODELS) {
      try {
        const r = await fetch('https://openrouter.ai/api/v1/chat/completions', {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${picked.key}`,
            'Content-Type': 'application/json',
            'HTTP-Referer': 'https://stxaviers.pages.dev',
            'X-Title': 'XavierDrive',
          },
          body: JSON.stringify({
            model,
            messages: [{ role: 'system', content: systemPrompt || GROQ_SYSTEM_PROMPT }, ...flat],
            temperature: 0.7,
            max_tokens: maxTokens,
          }),
          signal: AbortSignal.timeout(90000),
        });
        if (r.status === 429) {
          const bodyTxt = await r.text().catch(() => '');
          if (/per day|daily/i.test(bodyTxt)) markKeyDown('openrouter#' + picked.idx, msUntilUtcMidnight());
          else markKeyDown('openrouter#' + picked.idx, 90 * 1000);
          lastErr = new Error('OpenRouter key#' + (picked.idx + 1) + ' rate-limited');
          break; // next key
        }
        if (r.status === 401 || r.status === 403) { markKeyDown('openrouter#' + picked.idx, 24 * 3600 * 1000); lastErr = new Error('OpenRouter key#' + (picked.idx + 1) + ' rejected'); break; }
        if (!r.ok) { const err = await r.json().catch(() => ({})); lastErr = new Error(err?.error?.message || `OpenRouter ${r.status}`); continue; }
        const data = await r.json();
        const text = data.choices?.[0]?.message?.content || '';
        if (text) { orDailyUse(picked.idx); markKeyUp('openrouter#' + picked.idx); return text; }
        lastErr = new Error('OpenRouter empty (' + model + ')');
      } catch (e) { lastErr = e; }
    }
  }
  throw new Error('OpenRouter failed: ' + (lastErr?.message || 'no usable keys'));
}

// —— CLOUDFLARE WORKERS AI (REST API, cfut_ tokens) ————————
// Endpoint: /client/v4/accounts/{account}/ai/run/{model} (verified live).
// Free allocation: 10k neurons/day per account, resets 00:00 UTC.
// glm-4.7-flash is the value pick (~275k output tokens/day per account).
// Response shape: {result:{choices:[{message:{content}}]}, success:true}.
const CF_AI_MODELS = ['@cf/zai-org/glm-4.7-flash', '@cf/openai/gpt-oss-120b', '@cf/meta/llama-3.3-70b-instruct-fp8-fast'];
let _cfIdx = 0;
function pickCfEntry(env) {
  const entries = getJsonList(env, 'CF_AI_KEYS_JSON');
  for (let i = 0; i < entries.length; i++) {
    const idx = (_cfIdx + i) % entries.length;
    const e = entries[idx];
    if (e && e.token && e.account && !keyCooling('cfai#' + idx)) { _cfIdx = (idx + 1) % entries.length; return { entry: e, idx }; }
  }
  return null;
}
async function callCfAi(env, messages, systemPrompt, maxTokens = 4096) {
  const flat = flattenToText(messages);
  let lastErr = null;
  for (let round = 0; round < 2; round++) {
    const picked = pickCfEntry(env);
    if (!picked) break;
    for (const model of CF_AI_MODELS) {
      try {
        const r = await fetch(`https://api.cloudflare.com/client/v4/accounts/${picked.entry.account}/ai/run/${model}`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${picked.entry.token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ messages: [{ role: 'system', content: systemPrompt || GROQ_SYSTEM_PROMPT }, ...flat], max_tokens: maxTokens }),
          signal: AbortSignal.timeout(90000),
        });
        if (r.status === 429) {
          markKeyDown('cfai#' + picked.idx, msUntilUtcMidnight());
          lastErr = new Error('Workers AI account#' + (picked.idx + 1) + ' daily neurons exhausted');
          break;
        }
        if (r.status === 401 || r.status === 403) { markKeyDown('cfai#' + picked.idx, 24 * 3600 * 1000); lastErr = new Error('Workers AI token#' + (picked.idx + 1) + ' rejected'); break; }
        const data = await r.json().catch(() => ({}));
        if (!r.ok || !data?.success) { lastErr = new Error((data?.errors?.[0]?.message) || `Workers AI ${r.status}`); continue; }
        const text = data?.result?.choices?.[0]?.message?.content || data?.result?.response || '';
        if (typeof text === 'string' && text) { markKeyUp('cfai#' + picked.idx); return text; }
        lastErr = new Error('Workers AI empty (' + model + ')');
      } catch (e) { lastErr = e; }
    }
  }
  throw new Error('Workers AI failed: ' + (lastErr?.message || 'no usable tokens'));
}

// ═══════════════════════════════════════════════════════
// Smart router (owner directive 2026-09-26): GROQ -> OPENROUTER ->
// CF WORKERS AI -> GEMINI (backup). A provider that just failed enters a
// short cooldown (circuit breaker) so dead keys don't add latency to every
// message; when ALL providers are cooling down we still try them (keys may
// have been rotated and we must never hard-fail while any provider works).
// ═══════════════════════════════════════════════════════
const _aiCooldowns = new Map();          // provider -> retry-after timestamp
const AI_COOLDOWN_MS = 10 * 60 * 1000;   // 10 minutes
let _lastProviderUsed = '';

function providerCooling(name) { return (_aiCooldowns.get(name) || 0) > Date.now(); }
function markProviderDown(name) { _aiCooldowns.set(name, Date.now() + AI_COOLDOWN_MS); }
function markProviderUp(name) { _aiCooldowns.delete(name); }

async function callGroqOrCerebras(env, messages, systemPrompt, opts = {}) {
  const maxTokens = opts.maxTokens || 4096;
  const hasImages = messagesHaveImages(messages);
  // Image messages: only Gemini is multimodal in our lineup -> try it first.
  const order = hasImages ? ['gemini', 'cfai'] : ['groq', 'openrouter', 'cfai', 'gemini'];
  const live = order.filter(p => !providerCooling(p));
  const tryList = live.length ? live : order;
  let lastError = null;
  for (const p of tryList) {
    if (hasImages && (p === 'groq' || p === 'openrouter')) continue; // text-only models
    try {
      const text = p === 'groq' ? await callGroq(env, messages, systemPrompt, maxTokens)
                : p === 'openrouter' ? await callOpenRouter(env, messages, systemPrompt, maxTokens)
                : p === 'cfai' ? await callCfAi(env, messages, systemPrompt, maxTokens)
                : await callGeminiChat(env, messages, systemPrompt);
      markProviderUp(p);
      _lastProviderUsed = p;
      return text;
    } catch (e) {
      markProviderDown(p);
      lastError = e;
      console.warn(`[ai-router] ${p} failed: ${e.message}`);
    }
  }
  // Last resort for image messages: answer text-only (tell the user).
  if (hasImages) {
    const flat = flattenToText(messages);
    for (const p of ['groq', 'openrouter', 'cfai']) {
      try {
        const text = p === 'groq' ? await callGroq(env, flat, systemPrompt, maxTokens)
                  : p === 'openrouter' ? await callOpenRouter(env, flat, systemPrompt, maxTokens)
                  : await callCfAi(env, flat, systemPrompt, maxTokens);
        _lastProviderUsed = p;
        return '_(Image could not be processed by the available AI providers right now — answering from your text only.)_\n\n' + text;
      } catch (e) { lastError = e; }
    }
  }
  throw new Error('All AI providers failed. Last: ' + (lastError?.message || 'unknown'));
}

// —— Gemini call ——————————————————————————————————

async function callGemini(env, prompt, systemInstruction = null, jsonMode = false) {
  const body = {
    contents: [{ role: 'user', parts: [{ text: prompt }] }],
    generationConfig: { temperature: 0.7, maxOutputTokens: 8192 },
  };
  if (systemInstruction) body.systemInstruction = { parts: [{ text: systemInstruction }] };
  if (jsonMode) body.generationConfig.responseMimeType = 'application/json';
  return geminiGenerate(env, body);
}

// —— Gemini core (2026-09-24 revive) ————————————————————
// gemini-2.0-flash and gemini-2.5-flash are RETIRED for new users (404:
// "update your code to use models/gemini-3.8-flash"). Verified working
// 2026-09-26: gemini-3.8-flash + gemini-flash-latest alias. 5 keys on 5
// SEPARATE projects (correlation-tested: bursting key1 to 429s left key2
// untouched) -> rotation is now safe per-key.
const GEMINI_MODELS = ['gemini-3.8-flash', 'gemini-flash-latest'];

async function geminiGenerate(env, body) {
  const keyMap = getJsonList(env, 'GEMINI_KEYS_JSON');
  if (!keyMap.length) throw new Error('No Gemini keys configured');
  let lastError = null;
  let sawGeoBlock = false;
  for (const key of keyMap) {
    for (const model of GEMINI_MODELS) {
      try {
        const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
          body: JSON.stringify(body),
        });
        if (r.ok) {
          const data = await r.json();
          const text = data.candidates?.[0]?.content?.parts?.map(p => p.text || '').join('') || '';
          if (text) return text;
          lastError = new Error('Empty response from ' + model);
          continue;
        }
        const err = await r.json().catch(() => ({}));
        const errMsg = err?.error?.message || `Gemini error: ${r.status}`;
        if (/location is not supported/i.test(errMsg)) sawGeoBlock = true;
        console.warn(`Gemini ${model} key ...${key.slice(-6)} failed:`, errMsg.substring(0, 120));
        lastError = new Error(errMsg);
        // 429 quota / 503 overloaded / 404 model-gone -> try next model/key
        continue;
      } catch (e) {
        lastError = e;
        continue;
      }
    }
  }
  // Geo-blocked edge egress (e.g. HK colos): relay the call through the
  // CrazyCloud backend, which sits in a supported region. The keys travel
  // AES-GCM-encrypted with SHA256(BACKEND_KEY) and are decrypted in the
  // backend's memory only.
  if (sawGeoBlock) {
    try {
      return await geminiViaBackend(env, body, keyMap);
    } catch (e) {
      console.warn('gemini backend relay failed:', e.message);
      lastError = e;
    }
  }
  throw new Error('All Gemini keys/models failed. Last error: ' + (lastError?.message || 'unknown'));
}

// Relay a Gemini call through the backend (encrypted key, memory-only decrypt).
async function geminiViaBackend(env, body, keyMap) {
  const base = (env.BACKEND_URL || '').replace(/\/+$/, '');
  if (!base) throw new Error('no BACKEND_URL for gemini relay');
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const aesKey = await crypto.subtle.importKey('raw', await crypto.subtle.digest('SHA-256', new TextEncoder().encode(env.BACKEND_KEY || '')), 'AES-GCM', false, ['encrypt']);
  const enc = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, aesKey, new TextEncoder().encode(JSON.stringify(keyMap))));
  const b64 = (u8) => btoa(String.fromCharCode(...u8));
  const r = await fetch(base + '/ai/relay/gemini', {
    method: 'POST',
    headers: {
      'X-Backend-Key': env.BACKEND_KEY || '',
      'Content-Type': 'application/json',
      'X-Gemini-Key-Enc': b64(iv) + '.' + b64(enc),
    },
    body: JSON.stringify({ geminiBody: body, models: GEMINI_MODELS }),
    signal: AbortSignal.timeout(90000),
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok || !d.ok || typeof d.text !== 'string') {
    throw new Error(d.error || `gemini relay failed (${r.status})`);
  }
  return d.text;
}

// Chat-style Gemini call: converts OpenAI-style messages (incl. multimodal
// image_url data-URIs) into Gemini contents format.
async function callGeminiChat(env, messages, systemPrompt) {
  const contents = messages.map(m => {
    const role = m.role === 'assistant' ? 'model' : 'user';
    if (Array.isArray(m.content)) {
      const parts = [];
      for (const c of m.content) {
        if (c.type === 'text' && c.text) parts.push({ text: c.text });
        else if (c.type === 'image_url' && c.image_url?.url?.startsWith('data:')) {
          const mm = c.image_url.url.match(/^data:([^;]+);base64,(.+)$/);
          if (mm) parts.push({ inline_data: { mime_type: mm[1], data: mm[2] } });
        }
      }
      return { role, parts: parts.length ? parts : [{ text: '' }] };
    }
    return { role, parts: [{ text: String(m.content || '') }] };
  });
  const body = {
    contents,
    systemInstruction: { parts: [{ text: systemPrompt || GROQ_SYSTEM_PROMPT }] },
    generationConfig: { temperature: 0.7, maxOutputTokens: 8192 },
  };
  return geminiGenerate(env, body);
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

// —— Smart search gating (owner directive 2026-09-26) ————————————
// The web-search-first engine should fire for basically every real message
// ("too often"), but NOT for greetings / tiny acknowledgements like "Hi",
// "thanks", "ok". Conservative: only skip when the WHOLE message is a
// greeting/ack, emoji-only, or empty.
function needsResearch(message) {
  const t = String(message || '').trim();
  if (!t) return false;
  if (t.length <= 8 && !/[a-z0-9]/i.test(t)) return false;               // emoji/symbol only
  if (t.length < 34) {
    // strip emojis/pictographs so "Hi 👁👄👁" tests as "hi"
    const low = t.toLowerCase().replace(/[!.,~\s]+$/, '').replace(/[^\p{L}\p{N}\s'?!.,]/gu, '').trim();
    if (!low) return false;                                              // nothing but emoji left
    if (/^(hi+|hey+|hello+|yo+|sup|hola|namaste|greetings|good\s*(morning|afternoon|evening|night|day)|gm|gn|thanks+|thank\s*you+|thx+|ty|tysm|ok+|okay+|k+|cool|nice|great|awesome|good|fine|perfect|lol|lmao|haha+|hehe+|bruh|bye|goodbye|see\s*ya|later|yes+|yeah+|yep+|no+|nah+|nope+|wow+|omg+|sad|happy)[\s?!.]*$/i.test(low)) return false;
    if (/^(what'?s\s*up|wassup|wsp|wsup|how\s*(are|r)\s*(you|u)|hru|you\s*good|u\s*good)[\s?!.]*$/i.test(low)) return false;
    if (/^(who|what)'?s\s*(this|there|up)[\s?!.]*$/i.test(low)) return false;
  }
  return true;
}

// —— Live web research via XavierDrive backend (search-before-answer) ————
// Owner directive 2026-09-24: the AI must ALWAYS search the web before answering,
// like GPT/Claude/Gemini research mode. Backend runs the engine chain
// (DDG lite -> DDG html -> Bing), reads top sources, returns a research pack.
// Failures are non-fatal: if the backend is down the AI still answers (without
// research grounding) so the chat never breaks.
async function backendResearch(env, question) {
  const base = env.BACKEND_URL;
  if (!base) return '';
  const r = await fetch(base.replace(/\/+$/, '') + '/ai/research', {
    method: 'POST',
    headers: { 'X-Backend-Key': env.BACKEND_KEY || '', 'Content-Type': 'application/json' },
    body: JSON.stringify({ question: String(question).slice(0, 500), depth: 2, agent: 'site-chat' }),
    signal: AbortSignal.timeout(20000),
  });
  if (!r.ok) return '';
  const d = await r.json();
  if (!d || !Array.isArray(d.sources)) return '';
  const lines = [];
  for (const q of (d.queries || [])) {
    for (const res of (q.results || []).slice(0, 3)) {
      if (res.title && res.url) lines.push(`- ${res.title} (${res.url})${res.snippet ? ': ' + String(res.snippet).slice(0, 200) : ''}`);
    }
  }
  const extracts = [];
  for (const s of d.sources.slice(0, 3)) {
    if (s.extract) extracts.push(`### ${s.title || s.url}\n${String(s.extract).slice(0, 2500)}`);
  }
  if (!lines.length && !extracts.length) return '';
  return `[LIVE WEB RESEARCH — the web was just searched for this question. Ground your answer in these results and mention sources when helpful. If the research is irrelevant to the question, ignore it and answer normally. Never mention this block.]\nSearch results:\n${lines.slice(0, 10).join('\n')}\n\nTop source extracts:\n${extracts.join('\n\n')}`;
}

// —— Response sanitizer ————————————————————————————
// Defense in depth: if a model ever echoes research/system text into its
// answer, strip the markers before the user can see them.
function sanitizeAIResponse(text) {
  let t = String(text || '');
  t = t.replace(/\[LIVE WEB RESEARCH[^\]]*\]/gi, '');
  t = t.replace(/^\s*(Search results|Top source extracts)\s*:\s*$/gim, '');
  t = t.replace(/^\s*\[User Context:[^\]]*\]\s*$/gim, '');
  return t.trim();
}

// —— Backend chat engine call (JSON, non-streaming) ————————————
async function backendChat(env, payload) {
  const base = (env.BACKEND_URL || '').replace(/\/+$/, '');
  if (!base) throw new Error('BACKEND_URL not configured');
  const r = await fetch(base + '/ai/chat', {
    method: 'POST',
    headers: { 'X-Backend-Key': env.BACKEND_KEY || '', 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(120000),
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok || !d.ok || typeof d.response !== 'string') {
    throw new Error(d.error || `backend chat failed (${r.status})`);
  }
  return d;
}

// —— INTERNAL: AI key-proxy for the backend server ————————————
// The backend orchestrates chat/PDF work but provider keys stay ONLY in
// Cloudflare secrets (owner directive). The backend calls this endpoint;
// the worker injects keys and runs the provider router. Server-to-server
// (X-Backend-Key auth, no Origin header, CSRF-exempted).
async function handleInternalAICall(request, env) {
  if (request.headers.get('X-Backend-Key') !== (env.BACKEND_KEY || '')) {
    return json({ error: 'unauthorized' }, 401);
  }
  let body;
  try { body = await request.json(); } catch (e) { return json({ error: 'invalid JSON' }, 400); }
  const messages = Array.isArray(body.messages) ? body.messages.slice(-40).map(m => ({
    role: m.role === 'assistant' ? 'assistant' : 'user',
    content: m.content,
  })) : [];
  if (!messages.length) return json({ error: 'messages required' }, 400);
  const systemExtra = String(body.systemExtra || '').slice(0, 24000);
  const systemPrompt = systemExtra ? (GROQ_SYSTEM_PROMPT + '\n\n' + systemExtra) : GROQ_SYSTEM_PROMPT;
  const maxTokens = Math.min(parseInt(body.maxTokens, 10) || 4096, 8192);
  try {
    const text = await callGroqOrCerebras(env, messages, systemPrompt, maxTokens);
    return json({ ok: true, text, provider: _lastProviderUsed, cooldowns: [..._aiCooldowns.keys()] });
  } catch (e) {
    return json({ ok: false, error: e.message }, 502);
  }
}

// —— ADMIN: AI provider health check ————————————————————
// Pings every provider key with a zero-token GET /models call and reports
// status + available models. Never returns key values.
async function handleAIStatus(request, env) {
  if (request.headers.get('X-Backend-Key') !== (env.BACKEND_KEY || '')) {
    return json({ error: 'unauthorized' }, 401);
  }
  const probe = async (name, url, headers) => {
    try {
      const r = await fetch(url, { headers, signal: AbortSignal.timeout(12000) });
      let models = null;
      let errMsg = '';
      try {
        const d = await r.json();
        const list = d.data || d.models || [];
        models = list.map(m => m.name || m.id || String(m)).filter(Boolean).slice(0, 80);
        if (!r.ok) errMsg = (d.error && (d.error.message || d.error.status)) || JSON.stringify(d).slice(0, 200);
      } catch (e) { if (!r.ok) errMsg = 'non-JSON error body'; }
      return { provider: name, status: r.status, ok: r.ok, models, error: errMsg || undefined };
    } catch (e) {
      return { provider: name, status: 0, ok: false, error: e.message };
    }
  };
  const jobs = [];
  getJsonList(env, 'GROQ_KEYS_JSON').forEach((k, i) => jobs.push(probe('groq#' + (i + 1), 'https://api.groq.com/openai/v1/models', { Authorization: `Bearer ${k}` })));
  getJsonList(env, 'OPENROUTER_KEYS_JSON').forEach((k, i) => jobs.push(probe('openrouter#' + (i + 1), 'https://openrouter.ai/api/v1/models', { Authorization: `Bearer ${k}` })));
  getJsonList(env, 'CF_AI_KEYS_JSON').forEach((e, i) => jobs.push(probe('cfai#' + (i + 1), `https://api.cloudflare.com/client/v4/accounts/${e.account}/ai/models/search?per_page=5`, { Authorization: `Bearer ${e.token}` })));
  getJsonList(env, 'GEMINI_KEYS_JSON').forEach((k, i) => {
    jobs.push(probe('gemini#' + (i + 1), 'https://generativelanguage.googleapis.com/v1beta/models?pageSize=5', { 'x-goog-api-key': k }));
  });
  const results = await Promise.all(jobs);
  // DEEP PROBE (?deep=1): real generation per key — definitive status.
  // Probes mirror production params (bigger max_tokens because gpt-oss
  // spends reasoning tokens first; model fallback chains like callOpenRouter).
  let deep = null;
  const url = new URL(request.url);
  if (url.searchParams.get('deep') === '1') {
    deep = { groq: [], openrouter: [], cfai: [], gemini: [] };
    const wrap = async (label, fn) => {
      const t0 = Date.now();
      try {
        const text = await fn();
        return { provider: label, ok: true, ms: Date.now() - t0, sample: String(text).slice(0, 40) };
      } catch (e) {
        return { provider: label, ok: false, ms: Date.now() - t0, error: String(e.message).slice(0, 160) };
      }
    };
    const groqProbeKey = async (key) => {
      const r = await fetch('https://api.groq.com/openai/v1/chat/completions', {
        method: 'POST',
        headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: 'openai/gpt-oss-120b', messages: [{ role: 'user', content: 'Say OK' }], max_tokens: 300 }),
        signal: AbortSignal.timeout(30000),
      });
      if (!r.ok) { const e = await r.json().catch(() => ({})); throw new Error(e?.error?.message || ('HTTP ' + r.status)); }
      const d = await r.json();
      const text = d.choices?.[0]?.message?.content || '';
      if (!String(text).trim()) throw new Error('empty (reasoning ate budget)');
      return text;
    };
    const openRouterProbeKey = async (key) => {
      let lastErr = null;
      for (const model of ['qwen/qwen3.8-27b:free', 'nvidia/nemotron-3-super-120b-a12b:free', 'inclusionai/ling-3.0-flash-sante:free']) {
        try {
          const r = await fetch('https://openrouter.ai/api/v1/chat/completions', {
            method: 'POST',
            headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json', 'HTTP-Referer': 'https://stxaviers.pages.dev', 'X-Title': 'XavierDrive' },
            body: JSON.stringify({ model, messages: [{ role: 'user', content: 'Say OK' }], max_tokens: 300 }),
            signal: AbortSignal.timeout(45000),
          });
          if (!r.ok) { const e = await r.json().catch(() => ({})); lastErr = new Error(e?.error?.message || ('HTTP ' + r.status)); continue; }
          const d = await r.json();
          const text = d.choices?.[0]?.message?.content || '';
          if (String(text).trim()) return text;
          lastErr = new Error('empty (' + model + ')');
        } catch (e) { lastErr = e; }
      }
      throw lastErr || new Error('all models failed');
    };
    const geminiProbeKey = async (key) => {
      let lastErr = null;
      for (const model of GEMINI_MODELS) {
        try {
          const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
            body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: 'Say OK' }] }] }),
            signal: AbortSignal.timeout(30000),
          });
          if (!r.ok) { const e = await r.json().catch(() => ({})); throw new Error(e?.error?.message || ('HTTP ' + r.status)); }
          const d = await r.json();
          const text = (d.candidates?.[0]?.content?.parts || []).map(p => p.text || '').join('');
          if (String(text).trim()) return text;
          throw new Error('empty (' + model + ')');
        } catch (e) { lastErr = e; }
      }
      throw lastErr || new Error('all models failed');
    };
    for (const [i, k] of getJsonList(env, 'GROQ_KEYS_JSON').entries()) {
      deep.groq.push(await wrap('groq#' + (i + 1), () => groqProbeKey(k)));
    }
    for (const [i, k] of getJsonList(env, 'OPENROUTER_KEYS_JSON').entries()) {
      deep.openrouter.push(await wrap('openrouter#' + (i + 1), () => openRouterProbeKey(k)));
    }
    for (const [i, e] of getJsonList(env, 'CF_AI_KEYS_JSON').entries()) {
      deep.cfai.push(await wrap('cfai#' + (i + 1), async () => {
        const r = await fetch(`https://api.cloudflare.com/client/v4/accounts/${e.account}/ai/run/@cf/zai-org/glm-4.7-flash`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${e.token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ messages: [{ role: 'user', content: 'Say OK' }], max_tokens: 300 }),
          signal: AbortSignal.timeout(30000),
        });
        const d = await r.json().catch(() => ({}));
        if (!r.ok || !d?.success) throw new Error((d?.errors?.[0]?.message) || ('HTTP ' + r.status));
        const text = d?.result?.choices?.[0]?.message?.content || '';
        if (!String(text).trim()) throw new Error('empty');
        return text;
      }));
    }
    for (const [i, k] of getJsonList(env, 'GEMINI_KEYS_JSON').entries()) {
      deep.gemini.push(await wrap('gemini#' + (i + 1), () => geminiProbeKey(k)));
    }
  }
  const cooldowns = {};
  for (const [k, v] of _aiCooldowns) if (v > Date.now()) cooldowns[k] = new Date(v).toISOString();
  const keyCooldowns = {};
  for (const [k, v] of _keyCooldowns) if (v > Date.now()) keyCooldowns[k] = new Date(v).toISOString();
  return json({
    when: new Date().toISOString(),
    providerOrder: ['groq', 'openrouter', 'cfai (workers-ai)', 'gemini (backup)'],
    results,
    deep,
    router: { cooldowns, keyCooldowns, lastProviderUsed: _lastProviderUsed },
  });
}

// —— AI Chat STREAM handler (z.ai-style agent steps) ————————————
// SSE events: {t:'step',icon,label,detail} while researching, then
// {t:'answer',text,provider,searched,sources}. Primary path = backend chat
// engine (research + steps streamed live); fallback = worker-direct answer
// if the backend is unreachable.
async function handleAIChatStream(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const rl = rateCheck(sess.user?.email || 'anon', 'chat', 20);
  if (!rl.allowed) return json({ error: 'Too many messages. Please wait ' + rl.retryAfter + 's.' }, 429, origin);

  let body;
  try { body = await request.json(); } catch (e) { return json({ error: 'invalid JSON' }, 400, origin); }
  const { message, history, images } = body;
  if (!message) return json({ error: 'No message provided' }, 400, origin);

  const userEmail = body.email || sess.user?.email || 'unknown';
  const userRole = body.role || 'student';
  const verifiedRole = await verifyRole(env, userEmail);
  const actualRole = verifiedRole.role || userRole;

  const stream = new ReadableStream({
    async start(controller) {
      const enc = new TextEncoder();
      const send = (obj) => { try { controller.enqueue(enc.encode('data: ' + JSON.stringify(obj) + '\n\n')); } catch (e) {} };
      try {
        // PRIMARY: backend chat engine with live research steps
        const base = (env.BACKEND_URL || '').replace(/\/+$/, '');
        let answered = false;
        if (base) {
          try {
            const r = await fetch(base + '/ai/chat/stream', {
              method: 'POST',
              headers: { 'X-Backend-Key': env.BACKEND_KEY || '', 'Content-Type': 'application/json' },
              body: JSON.stringify({
                message: String(message).slice(0, 4000),
                role: actualRole, isAdmin: !!verifiedRole.isAdmin,
                email: userEmail, class: body.class,
                history: Array.isArray(history) ? history.slice(-30) : [],
                images, agent: 'site-chat',
              }),
              signal: AbortSignal.timeout(120000),
            });
            if (r.ok && r.body) {
              const reader = r.body.getReader();
              const dec = new TextDecoder();
              let buf = '';
              while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buf += dec.decode(value, { stream: true });
                let idx;
                while ((idx = buf.indexOf('\n\n')) >= 0) {
                  const chunk = buf.slice(0, idx);
                  buf = buf.slice(idx + 2);
                  const line = chunk.split('\n').find(l => l.startsWith('data: '));
                  if (!line) continue;
                  let ev; try { ev = JSON.parse(line.slice(6)); } catch (e) { continue; }
                  if (ev.t === 'answer') {
                    send({ t: 'answer', text: sanitizeAIResponse(ev.text), provider: ev.provider, searched: !!ev.searched, sources: ev.sources || [] });
                    answered = true;
                  } else if (ev.t !== 'error') {
                    send(ev);
                  }
                }
              }
            }
          } catch (e) { console.warn('backend chat stream failed, falling back:', e.message); }
        }

        // FALLBACK: worker-direct (backend down). No research here: search
        // lives on the backend AND the owner directive says the AI decides
        // when to search — the planner runs backend-side, so when it's down we
        // simply answer without research.
        if (!answered) {
          const systemExtra = `[User Context: role=${actualRole}${verifiedRole.isAdmin ? ' (admin)' : ''}${actualRole === 'student' && body.class ? `, class=${body.class}` : ''}, email=${userEmail}]`;
          const messages = (history || []).slice(-30).map(m => ({
            role: m.role === 'ai' ? 'assistant' : 'user',
            content: (m.text || '').substring(0, 2000),
          }));
          const safeImages = Array.isArray(images) ? images.slice(0, 4).map(img => ({
            mimeType: String(img.mimeType || 'image/jpeg').substring(0, 50),
            base64: String(img.base64 || '').substring(0, 1024 * 1024),
          })).filter(img => img.base64) : [];
          if (safeImages.length) {
            messages.push({ role: 'user', content: [...safeImages.map(img => ({ type: 'image_url', image_url: { url: `data:${img.mimeType};base64,${img.base64}` } })), { type: 'text', text: message }] });
          } else {
            messages.push({ role: 'user', content: message });
          }
          const text = await callGroqOrCerebras(env, messages, GROQ_SYSTEM_PROMPT + '\n\n' + systemExtra);
          send({ t: 'answer', text: sanitizeAIResponse(text), provider: _lastProviderUsed, searched: false, sources: [] });
        }
      } catch (e) {
        console.error('AI chat stream error:', e);
        send({ t: 'error', error: e.message || 'AI service temporarily unavailable' });
      }
      try { controller.close(); } catch (e) {}
    }
  });
  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive',
      ...corsHeaders(origin),
    },
  });
}

// —— AI-chosen chat title (owner directive 2026-09-26) ————————————
// The site asks for a session title after the first AI reply; the backend
// runs a tiny LLM call that names the chat in 2-6 words.
async function handleChatTitle(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);
  const rl = rateCheck(sess.user?.email || 'anon', 'title', 30);
  if (!rl.allowed) return json({ error: 'Too many requests' }, 429, origin);
  let body;
  try { body = await request.json(); } catch (e) { return json({ error: 'invalid JSON' }, 400, origin); }
  const message = String(body.message || '').slice(0, 2000);
  if (!message) return json({ error: 'No message provided' }, 400, origin);
  const base = (env.BACKEND_URL || '').replace(/\/+$/, '');
  if (!base) return json({ error: 'Title service unavailable' }, 503, origin);
  try {
    const r = await fetch(base + '/ai/title', {
      method: 'POST',
      headers: { 'X-Backend-Key': env.BACKEND_KEY || '', 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, reply: String(body.reply || '').slice(0, 2000), agent: 'chat-title' }),
      signal: AbortSignal.timeout(25000),
    });
    const d = await r.json().catch(() => ({}));
    if (!r.ok || !d.ok || typeof d.title !== 'string') {
      return json({ error: d.error || 'title failed' }, 502, origin);
    }
    return json({ ok: true, title: d.title }, 200, origin);
  } catch (e) {
    return json({ error: e.message || 'title failed' }, 502, origin);
  }
}

// —— PDF FILE handler (real PDF binary via backend renderer) ————————————
// Returns an actual application/pdf file the client shows as a file box in
// chat (like z.ai/Claude artifacts) instead of auto-downloading a print page.
async function handlePDFFile(request, env, origin) {
  const cookies = parseCookies(request.headers.get('Cookie'));
  const sess = await getSession(env, cookies);
  if (!sess) return json({ error: 'Not authenticated' }, 401, origin);

  const rl = rateCheck(sess.user?.email || 'anon', 'pdf', 5);
  if (!rl.allowed) return json({ error: 'Too many PDF requests. Please wait ' + rl.retryAfter + 's.' }, 429, origin);

  let body;
  try { body = await request.json(); } catch (e) { return json({ error: 'invalid JSON' }, 400, origin); }
  const { prompt, history, title, role, email, markdown } = body;
  if (!prompt && !markdown) return json({ error: 'No prompt provided' }, 400, origin);

  const base = (env.BACKEND_URL || '').replace(/\/+$/, '');
  if (!base) return json({ error: 'PDF service unavailable (backend not configured)' }, 503, origin);

  const recentCtx = (history || []).slice(-4)
    .map(m => `${m.role === 'ai' ? 'Assistant' : 'User'}: ${(m.text || '').substring(0, 200)}`)
    .join('\n');
  const fullPrompt = recentCtx ? `Recent conversation:\n${recentCtx}\n\nRequest: ${prompt}` : `Request: ${prompt}`;

  try {
    const r = await fetch(base + '/ai/pdf', {
      method: 'POST',
      headers: { 'X-Backend-Key': env.BACKEND_KEY || '', 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt: prompt ? fullPrompt : undefined,
        markdown: markdown ? String(markdown).slice(0, 200000) : undefined,
        title: title ? String(title).slice(0, 200) : undefined,
        roleCtx: `[role=${role || 'student'}]`,
        email: email || sess.user?.email || 'unknown',
      }),
      signal: AbortSignal.timeout(120000),
    });
    if (!r.ok) {
      const err = await r.text().catch(() => '');
      return json({ error: 'PDF generation failed: ' + err.slice(0, 300) }, 502, origin);
    }
    const buf = await r.arrayBuffer();
    return new Response(buf, {
      status: 200,
      headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': 'inline; filename="document.pdf"', ...corsHeaders(origin) },
    });
  } catch (e) {
    return json({ error: 'PDF generation failed: ' + e.message }, 500, origin);
  }
}

// —— Dev login (maintenance/testing backdoor) ————————————————————
// Enabled only when DEV_LOGIN_SECRET is set as a worker secret. Creates a
// developer session for the owner account so maintenance agents can test the
// logged-in site without going through Google OAuth.
async function handleDevLogin(request, env) {
  if (!env.DEV_LOGIN_SECRET) return json({ error: 'dev login disabled' }, 404);
  const url = new URL(request.url);
  if (url.searchParams.get('token') !== env.DEV_LOGIN_SECRET) return json({ error: 'bad token' }, 403);
  const sid = await makeSessionId(env.SESSION_SECRET || 'fallback-secret');
  await setSession(env, sid, {
    user: { email: 'quackeditzofficial@gmail.com', name: 'Amrit Raj', picture: '' },
    access_token: null,
    created: Date.now(),
  });
  const headers = new Headers({ Location: (env.FRONTEND_URL || 'https://stxaviers.pages.dev') + '/' });
  headers.append('Set-Cookie', sessionCookie('xd_sid', sid, 86400 * 7));
  return new Response(null, { status: 302, headers });
}

// —— Shared moderation (any provider, fail-open) ————————————————————
async function moderateText(env, text) {
  try {
    const out = await callGroqOrCerebras(env, [
      { role: 'user', content: String(text).slice(0, 500) },
    ], 'You are a school chat moderator. Reply ONLY with JSON, no other text: {"appropriate": true} or {"appropriate": false, "rephrased": "cleaned version"}. Check for profanity, bullying, cheating answers, spam. Be lenient with casual language, strict on harmful content.');
    const m = out.match(/\{[\s\S]*\}/);
    if (m) {
      const j = JSON.parse(m[0]);
      if (typeof j.appropriate === 'boolean') return j;
    }
  } catch (e) { /* fail open */ }
  return { appropriate: true };
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

  // SECURITY: validate images array — cap count and size to prevent abuse
  const safeImages = Array.isArray(images) ? images.slice(0, 4).map(img => ({
    mimeType: String(img.mimeType || 'image/jpeg').substring(0, 50),
    base64: String(img.base64 || '').substring(0, 1024 * 1024), // 1MB per image max
  })).filter(img => img.base64) : [];

  // —— PRIMARY: backend chat engine (research-first + answer) ——————
  // All chat work runs on the CrazyCloud server (owner directive 2026-09-25):
  // it researches the question, then calls back into /internal/ai/call for
  // the LLM (keys stay in Cloudflare). Worker = auth + fallback.
  try {
    const out = await backendChat(env, {
      message, role: actualRole, isAdmin: !!actualIsAdmin,
      email: userEmail, class: studentClass,
      history: (history || []).slice(-30), images: safeImages, agent: 'site-chat',
    });
    const resp = sanitizeAIResponse(out.response);
    if (resp.trimStart().startsWith('[CANCEL]')) {
      return json({ response: "I'm sorry, but I can't help with that request.", model: out.provider, cancelled: true }, 200, origin);
    }
    return json({
      response: resp,
      model: out.provider,
      searched: !!out.searched,
      quotaUsed: 0,
      quotaLimit: userRole === 'student' ? parseInt(env.STUDENT_GEMINI_LIMIT || '30') : Infinity,
      quotaExhausted: false,
    }, 200, origin);
  } catch (e) {
    console.warn('backend chat engine failed, using worker fallback:', e.message);
  }

  // —— FALLBACK: worker-direct (backend offline) ——————
  try {
    // No research in fallback (owner directive: the AI-planner runs on the
    // backend; when it's down we answer directly from model knowledge).
    const messages = (history || []).slice(-30).map(m => ({
      role: m.role === 'ai' ? 'assistant' : 'user',
      content: (m.text || '').substring(0, 2000),
    }));
    const systemExtra = `[User Context: role=${actualRole}${actualIsAdmin ? ' (admin)' : ''}${actualRole === 'student' && studentClass ? `, class=${studentClass}` : ''}, email=${userEmail}]`;
    if (safeImages.length > 0) {
      messages.push({
        role: 'user',
        content: [
          ...safeImages.map(img => ({ type: 'image_url', image_url: { url: `data:${img.mimeType};base64,${img.base64}` } })),
          { type: 'text', text: message },
        ],
      });
    } else {
      messages.push({ role: 'user', content: message });
    }

    const aiResponse = sanitizeAIResponse(await callGroqOrCerebras(env, messages, GROQ_SYSTEM_PROMPT + '\n\n' + systemExtra));

    if (aiResponse.trimStart().startsWith('[CANCEL]')) {
      return json({
        response: "I'm sorry, but I can't help with that request.",
        model: _lastProviderUsed,
        cancelled: true,
      }, 200, origin);
    }

    return json({
      response: aiResponse,
      model: _lastProviderUsed,
      searched: false,
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

const PDF_SYSTEM_INSTRUCTION = `You are an expert PDF document designer for St. Xavier's School, Jaipur. Generate a COMPLETE, BEAUTIFUL, PRINT-READY HTML document for the following request.

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
// The 5TB Google Workspace account that hosts the XavierDrive files.
// Owner-login (/owner-login) only accepts this account; its token lands in KV
// __owner_token__ and takes priority over the DRIVE_TOKEN_JSON secret.
const DRIVE_OWNER_EMAIL = 'drrohitkumar27@gmail.com';

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

  // Routed through the provider chain (works with any live provider, fail-open)
  const result = await moderateText(env, safeMessage);
  return json(result, 200, origin);
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
    const groqKeys = getJsonList(env, 'GROQ_KEYS_JSON');
    if (!groqKeys.length) return json({ error: 'TTS not configured' }, 500, origin);
    const ttsKey = groqKeys.find((k, i) => !keyCooling('groq#' + i)) || groqKeys[0];
    const r = await fetch('https://api.groq.com/openai/v1/audio/speech', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${ttsKey}`,
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

  // Moderate via the shared provider router (fail-open)
  let appropriate = true, flaggedText = safeText;
  try {
    const result = await moderateText(env, safeText);
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

// —— Android app (Xavier's Drive) update manifest ——————————————————
// Bump these when releasing a new APK — the app checks this on every launch.
// apkUrl must point at the publicly-hosted APK on the Pages site.
const APP_LATEST = {
  versionCode: 3,
  versionName: '1.0.1',
  apkUrl: 'https://stxaviers.pages.dev/apk/xavierdrive1.0.1.apk',
  notes: "Xavier's Drive v1.0.1 — new XD emblem branding everywhere, in-app updater with live progress, speed and one-tap install, all-new animated login page (dark + light, auto-themed), smoother portal."
};

function handleAppVersion(origin) {
  return json({ ok: true, ...APP_LATEST }, 200, origin);
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
    // /internal/* is server-to-server (X-Backend-Key auth, no Origin) — also exempt.
    if (path !== '/callback' && !path.startsWith('/internal/')) {
      const csrf = csrfCheck(request, origin);
      if (csrf) return csrf;
    }

    // Existing routes
    if (path === '/login') return handleLogin(env, origin);
    if (path === '/owner-login') return handleOwnerLogin(env, origin);
    if (path === '/callback') return handleCallback(request, env);
    if (path === '/me') return handleMe(request, env, origin);
    if (path === '/token') return handleToken(request, env, origin);
    if (path === '/logout') return handleLogout(request, env, origin);
    if (path === '/config') return handleConfig(env, origin);
    if (path === '/api/app/version' && request.method === 'GET') return handleAppVersion(origin);
    if (path.startsWith('/drive')) return handleDrive(request, env, origin, path);

    // AI routes
    if (path === '/api/chat' && request.method === 'POST') return handleAIChat(request, env, origin);
    if (path === '/api/chat/stream' && request.method === 'POST') return handleAIChatStream(request, env, origin);
    if (path === '/api/chat/title' && request.method === 'POST') return handleChatTitle(request, env, origin);
    if (path === '/api/quota' && request.method === 'GET') return handleQuota(request, env, origin);
    if (path === '/api/pdf' && request.method === 'POST') return handlePDF(request, env, origin);
    if (path === '/api/pdf/file' && request.method === 'POST') return handlePDFFile(request, env, origin);
    if (path === '/api/tts' && request.method === 'POST') return handleTTS(request, env, origin);

    // Internal (server-to-server, X-Backend-Key gated)
    if (path === '/internal/ai/call' && request.method === 'POST') return handleInternalAICall(request, env);

    // Admin (X-Backend-Key gated diagnostics — never leaks key values)
    if (path === '/admin/ai-status' && request.method === 'GET') return handleAIStatus(request, env);

    // Maintenance/testing backdoor (enabled only when DEV_LOGIN_SECRET is set)
    if (path === '/dev-login' && request.method === 'GET') return handleDevLogin(request, env);

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
