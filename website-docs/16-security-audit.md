# 16 — Security Audit (v1.1.2, 2026-09-28)

> Full diagnostic of the XavierDrive system: the Cloudflare Worker
> (`stxaviers-auth`), the website (stxaviers.pages.dev) and the Android
> app. Findings marked FIXED were repaired in this release; NOTE items
> are accepted-risk or need owner action.

## 1. Worker (`stxaviers-auth.quackeditzofficial.workers.dev`)

### 1.1 Session & cookies — SOUND
- Session cookies: `HttpOnly; Secure; SameSite=None; Path=/`. SameSite=None
  is required because the worker (workers.dev) and the frontend
  (pages.dev) are cross-site to each other; HttpOnly keeps the session
  token out of JavaScript.
- Sessions live in the `KV_SESSIONS` namespace with 7-day expiry,
  server-side only.

### 1.2 CSRF — SOUND
- Every mutating request must carry an allowed `Origin` (production
  frontend + localhost dev only) or, for the Android app, the
  `X-XavierDrive-App: android` header. A browser can never send that
  custom header cross-origin: the CORS preflight only allows
  `Content-Type`/`Authorization`, and a preflight denial blocks the
  actual request. The session cookie remains the proof of identity.
- Exemptions are individually justified: `/callback` (Google redirect,
  carries its own OAuth state), `/internal/*` (server-to-server,
  X-Backend-Key), `/api/auth/mobile` (native app, verified Google ID
  token instead of ambient cookies).

### 1.3 Drive proxy — SOUND
- `/drive/*` requires a valid session (fixed in v1.1.0; previously only
  origin-gated, which is a browser-only defence). Verified live: no
  cookie → 401.

### 1.4 Firebase Realtime Database — FIXED THIS RELEASE
- **Finding (HIGH, functional):** the RTDB rules correctly deny anonymous
  access (probed: `/users`, `/liveClassChats`, `/usage` all return 401
  without auth), but the Worker called the RTDB REST API **without any
  credentials**. Consequences: `/api/user/profile` always returned
  `{name: null, photo: null}`; every profile save failed silently;
  quota counters and worker-side live-chat/hand writes never landed.
  The website still displayed names/photos from `localStorage`, which
  masked the breakage.
- **Fix:** profile storage moved into the Worker's KV namespace
  (`profile:<email>`), reachable only through the session-gated
  `/api/user/profile` endpoints (same validation as before: name 2–30
  chars, HTML-stripped; photo must be a `data:image/` URL under ~2 MB).
  Firebase stays as a read fallback and a best-effort mirror. All 20
  remaining direct RTDB calls now append a service-account OAuth2
  access token **when `FIREBASE_SERVICE_ACCOUNT` is configured** (the
  worker mints and caches the token with Web Crypto RS256; without the
  secret it degrades gracefully to the old unauthenticated behaviour).
- **Owner action (optional):** add `FIREBASE_SERVICE_ACCOUNT` (the same
  service-account JSON the backend uses) as a worker secret to restore
  the Firebase writes for quota/live-chat.

### 1.5 Rate limiting — SOUND
- Chat 20/min/user, TTS 10/min/user, in-memory buckets with periodic
  cleanup. 429 responses carry retry-after.

### 1.6 Admin & internal routes — SOUND, one NOTE
- Role changes (`/api/user/role`) are developer-gated with server-side
  `verifyRole`; the AI verifies the caller's role server-side on every
  chat request (client-sent roles are ignored).
- `/internal/ai/call` is gated by `X-Backend-Key`. NOTE: the comparison
  uses `===` (not constant-time). Practical risk is negligible (network
  jitter dwarfs comparison timing over TLS), but a constant-time compare
  is the textbook fix if the key is ever rotated.

### 1.7 Secrets — SOUND
- 17 secrets verified intact after this deploy. No provider keys appear
  in client-reachable code or responses. `/admin/ai-status` reports
  health without leaking key values. `/dev-login` is disabled unless
  `DEV_LOGIN_SECRET` is set (it is).

### 1.8 Public Firebase nodes — NOTE
- `liveClasses` is publicly readable (rules). It exposes only class
  name / subject / timing — no PII. Accepted for now (the website reads
  it logged-out too); lock it behind auth if class timings become
  sensitive.

## 2. Website (`index.html` on stxaviers.pages.dev)

- **CSP:** a strict `Content-Security-Policy` limits scripts to the
  known CDNs, connections to the worker + Firebase + Google, and frames
  to YouTube. Inline scripts/styles are allowed (single-file app).
- **XSS:** all dynamic HTML passes through `esc()`; AI markdown renders
  through DOMPurify with an explicit allow-list. Chat names are
  AI-moderated on post.
- **Sessions:** the cookie is HttpOnly; short-lived access tokens are
  fetched on demand from `/token` and never persisted in localStorage.
- **localStorage:** holds only cosmetic prefs (display name, photo,
  theme, per-user prefs). No secrets. The v2.0.0 re-login name race
  (cached name vs OAuth name) is fixed and deployed.
- **NOTE:** `xd.uname`/`xd.upic` can be edited locally by the user to
  change what THEY see — server-side identity (`/me`, worker sessions)
  is unaffected.

## 3. Android app (v1.1.2)

- **FIXED THIS RELEASE:** `android:allowBackup` is now **false**
  (was true — on rooted/old devices `adb backup` could have extracted
  the cookie store and preferences).
- `usesCleartextTraffic="false"` — every network call is HTTPS.
- The APK contains only the public OAuth client ID (safe by design);
  no secrets, no keys. The signing certificate is the upload key
  (SHA-1 9f246eba…, matching the registered OAuth client).
- The session cookie lives in the app's private cookie store; sign-out
  calls server `/logout`, clears cookies, XDState, AI cache and the
  notices marker.
- `RECORD_AUDIO` (new: AI voice input) is requested at runtime and the
  microphone feature is declared optional — mic-less devices still
  install.
- FileProvider is scoped to the app's own download/cache directories.
- In-app updates: the APK is downloaded over HTTPS from the school's own
  Pages domain and installed only if the signature matches (Android
  enforces this) — a hostile APK cannot update the app in place.

## 4. Standing recommendations

1. (Optional) Add `FIREBASE_SERVICE_ACCOUNT` to the worker secrets to
   re-enable the Firebase mirror writes (quota, live chat, hands).
2. Keep `/users` locked in RTDB rules — the KV profile store is now the
   authoritative, session-gated store.
3. Rotate the Cloudflare API key and GitHub PAT if they ever appear in
   a log or screenshot.
4. The backend server (CrazyCloud) holds the provider keys behind
   `X-Backend-Key`; keep its OS and dependencies patched.
5. When the school website (stxaviers.org) goes live, move the CSP
   `connect-src` allow-list to the final domain set.
