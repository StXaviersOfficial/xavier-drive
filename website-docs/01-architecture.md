# 01 — System Architecture

This document explains **what physically runs XavierDrive**, where each piece
lives, how the pieces talk to each other, and what happens on every kind of
request. If you only read one document, read this one.

---

## 1. The five building blocks

### 1.1 Cloudflare Pages — the website itself
- **URL:** `https://stxaviers.pages.dev`
- **What it is:** a purely static hosting. The entire website UI is **one
  single file, `index.html` (~460 KB)** — HTML + CSS + ~6,000 lines of
  JavaScript in one page. There is no framework, no build step, no router
  library; tab switching is plain DOM manipulation.
- **Also hosted there:** `terms.html`, `privacy.html`, `manifest.json` (PWA),
  `icon-192.png` / `icon-512.png` / `logo.png`, `/.well-known/assetlinks.json`
  (Android app–website association), and `/apk/xavierdrive<version>.apk`
  (the Android app itself, served as the update download).
- **Deploy method:** direct upload via the Cloudflare Pages REST API (NOT
  git-connected). Asset keys are `blake3(base64(content)+ext).hex[:32]`.

### 1.2 Cloudflare Worker `stxaviers-auth` — the brain
- **URL:** `https://stxaviers-auth.quackeditzofficial.workers.dev`
- **Source:** `worker.js` (~151 KB, ~3,200 lines) in the repo root.
- **Responsibilities:**
  1. **Authentication** — Google OAuth (web) + ID-token verification (Android).
  2. **Sessions** — mints and stores signed session cookies in Cloudflare KV.
  3. **CSRF protection** — rejects state-changing requests from foreign origins.
  4. **Google Drive proxy** (`/drive/*`) — ALL file storage flows through here
     using the **school owner's** Drive token (a 5 TB Google Workspace), so
     students never need their own Drive access.
  5. **AI gateway** — `/api/chat` and `/api/chat/stream` verify the session,
     then forward the work to the AI backend (below) and relay the answer.
  6. **AI key vault** — every AI provider API key lives ONLY inside the
     Worker's secrets. The backend asks the Worker (`/internal/ai/call`) to
     make the actual LLM call so keys never leave Cloudflare.
  7. **Live-class control** — talks to the YouTube Data API to create/end
     broadcasts, and stores live-class state in KV.
  8. **App updates** — `/api/app/version` tells the Android app if a new APK
     exists and where to get it.
- **Bindings:** `KV_SESSIONS` (Cloudflare KV — sessions, live-class state,
  rate-limit counters, quota counters, role caches). Plus ~17 secret
  environment variables (API keys, session secret, backend key…).

### 1.3 THINKING server — the AI engine (CrazyCloud / Pterodactyl)
- **URL:** `http://play.crazycloud.online:25570`
- **Source:** `backend/server.js` (Node.js, no framework).
- **Runs on:** a Pterodactyl allocation on an Azure South-India node (AMD EPYC
  7763, container with ~3 vCPU / ~8 GB usable RAM).
- **Responsibilities:**
  1. **Research engine** — `/ai/search`, `/ai/fetch`, `/ai/research`: a
     multi-engine web search (DuckDuckGo → Bing fallback) + page reader that
     builds a "research pack" (top results + extracted text) before answering.
  2. **Chat orchestration** — `/ai/chat` and `/ai/chat/stream`: decides
     whether research is needed (an LLM planner pass), runs it, then calls
     the LLM **through the Worker's key proxy** (`/internal/ai/call`) so
     provider keys stay in Cloudflare.
  3. **PDF generation** — `/ai/pdf`: converts markdown into a real,
     professionally typeset PDF binary (vector text, not a screenshot).
  4. **Gemini geo-relay** — `/ai/relay/gemini`: Gemini's free tier is
     region-blocked from some Cloudflare edge locations; the Worker can relay
     the call through this India-based server, with the API key travelling
     AES-GCM-encrypted.
- **Auth:** every call from the Worker carries `X-Backend-Key` (shared secret).
- **Autostart:** the container's `.bashrc` boots the server on power-up.

### 1.4 Google Drive — the file storage (5 TB)
- **Account:** the school owner's Google Workspace (`drrohitkumar27@gmail.com`,
  5 TB quota) — this IS "the school server" for files.
- **Access path:** the browser/app never talks to Drive directly. It calls the
  Worker's `/drive/*` routes with its session cookie; the Worker attaches the
  owner's OAuth access token (auto-refreshed from a stored refresh token) and
  proxies to `googleapis.com/drive/v3`.
- **Folder tree** (created on demand, see [15-data-map.md](15-data-map.md)):
  ```
  Xavier-Drive/                (root)
  ├── FILES/                   (all class material)
  ├── LOGBOOK/                 (activity photos, by Class/Section)
  ├── ANNOUNCEMENTS/           (notices: WholeSchool/ + per-class folders)
  ├── TIMETABLE/               (tt_<Class>_<Section>.json files)
  ├── CHATS/                   (AI chat session archives)
  ├── USERS/                   (one metadata file per user, named by email)
  └── ADMINS/                  (files listing admin emails)
  ```
- **Why it works:** any user with a valid XavierDrive session gets school
  files through the proxy; nobody else can read the Drive.

### 1.5 Firebase Realtime Database — the "live" state
- **URL:** `https://stxaviersapp-default-rtdb.firebaseio.com`
- **Used for:** live-class chat messages, raised hands, the attendance
  register + daily marks, and (historically) class/subject list overrides.
- **Access:** direct REST calls from the browser (`GET/PUT/POST/DELETE
  <url>/<path>.json`) — reads and writes go straight from the client to
  Firebase, NOT through the Worker. (The Worker uses Firebase only from its
  own side for live-class cleanup and some admin actions.)

---

## 2. Request paths — what happens when…

### 2.1 A user opens the site
1. Browser downloads `index.html` from Cloudflare Pages (CDN edge).
2. The boot code (`init()`) checks localStorage `xd` for a cached identity.
3. It calls `GET /me` on the Worker **with the session cookie**:
   - valid session → user object + role → the app shell renders.
   - 401 → the login screen (animated hero + "Continue with Google").
4. The app shell also calls `GET /token`: web sessions have a real Google
   access token; **Android-native sessions get a synthetic `xdapp-…` token**
   (the site only uses it as a signed-in marker — it is never sent to Google).
5. Tabs (Files / Live / Logbook / Notices / Timetable / Attendance[teachers])
   render lazily when first opened.

### 2.2 A user opens a file
1. Tab "Files" → `GET /drive/files?q=…` (Worker → Drive, owner token).
2. The file list is filtered client-side by the JSON metadata in each file's
   Drive `description` field (class / subject / chapter).
3. Tap a file → in-page viewer overlay with a **Drive preview iframe**
   (`drive.google.com/file/d/<id>/preview`).
4. "Download" → `GET /drive/media?id=…` streams the bytes through the Worker
   → saved as a local file; if that fails, the browser is sent to Drive's
   own download URL.

### 2.3 A user sends an AI message
1. `POST /api/chat/stream` (or `/api/chat`) with the session cookie and the
   body `{message, role, email, class, history, images}`.
2. Worker: session check → per-user rate limit (20 msg/min) → server-side
   role verification (never trusts the client's claimed role).
3. Worker forwards to the backend `/ai/chat/stream` with `X-Backend-Key`.
4. Backend:
   a. **Planner** — a fast LLM pass decides whether web research is needed
      and what queries to run (greetings and small talk skip research).
   b. **Research** (if planned) — search engine → pick top results → read
      pages → build a compact research pack.
   c. **Answer** — the LLM is called **through the Worker's
      `/internal/ai/call`** (so the API key is attached inside Cloudflare,
      never sent to the backend) with the school context + research pack.
   d. **Moderation** — the answer is screened by the same provider chain.
5. The streamed version emits Server-Sent Events: `step` events (icon +
   label + one-line detail, e.g. "Searching the web…") then a final `answer`
   event. The site renders these as live agent steps like z.ai/ChatGPT do.
6. The answer is sanitized (no leaked research text, no leaked user context)
   and rendered with markdown, charts, file boxes, or PDF boxes if present.

### 2.4 A teacher starts a live class
1. `POST /api/live/start {class, subject, method:'obs', presetId?}`.
2. Worker: verifies the teacher's role → asks the YouTube Data API (with the
   school channel's OAuth token) to create a **live broadcast + stream**
   endpoint → stores the state in KV (`live:<class>`).
3. The teacher gets a **Stream URL + Stream Key** to paste into OBS Studio;
   students see "LIVE" within ~10 s of the teacher pressing Start Streaming.
4. `POST /api/live/end` ends the broadcast and moves the recording into the
   class's YouTube playlist.
5. A **cron trigger** auto-ends any broadcast older than 3 hours.

---

## 3. Where code lives (source of truth)

| Path in repo | What it is |
|---|---|
| `index.html` | The entire website UI (HTML + CSS + JS) |
| `worker.js` | The Cloudflare Worker (auth, Drive proxy, AI gateway, live classes, admin) |
| `backend/server.js` | The THINKING AI engine (research, orchestration, PDF, relay) |
| `android-app/` | The native Android app (pure Java, no Gradle — see [13-android-app.md](13-android-app.md)) |
| `transcript.js` | Report-card/transcript generator + verification helper |
| `firebase-token.js` | Helper for minting Firebase auth tokens |
| `credentials.txt` | Public-facing credentials summary (no secrets) |
| `school-info.md` | The school's identity/contact reference used across the product |
| `terms.html` / `privacy.html` | Legal pages |
| `manifest.json` | PWA manifest (install-as-app) |

---

## 4. Cross-cutting concerns

- **Errors:** every API returns JSON; errors are `{error: "message"}` with a
  meaningful HTTP code. The UI surfaces them as toasts/inline panels.
- **Timeouts:** AI calls cap at ~60 s; research at ~20 s per stage. The chat
  UI always shows progress steps, so a slow answer never looks frozen.
- **Failover:** if the THINKING backend is down, the Worker answers the chat
  itself directly (it holds the same provider keys) — the user just loses the
  research trail for that message. If ALL providers are cooling down, a
  graceful error is returned.
- **Quotas:** Gemini "advanced" usage is capped per user per day
  (`/api/quota`); when exhausted the user silently falls back to standard
  models until midnight.
- **Security model:** session cookie (HttpOnly-style handling by the client),
  CSRF origin checks on all mutations, server-side role verification on every
  privileged call, per-user rate limits, image-count/size caps on AI input,
  and moderation on AI output. Secrets only ever live in Worker secrets and
  the offline vault — never in the repo.
