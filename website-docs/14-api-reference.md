# 14 — Complete API Reference

Every HTTP endpoint on the XavierDrive Worker
(`https://stxaviers-auth.quackeditzofficial.workers.dev`), plus the backend
server's API. **Auth column:** `cookie` = session cookie `xd_sid` required;
`none` = public; `backend-key` = `X-Backend-Key` header (server-to-server).

---

## A. Authentication & session

| Route | Method | Auth | Notes |
|---|---|---|---|
| `/login` | GET | none | 302 → Google OAuth authorize URL |
| `/owner-login` | GET | none | Drive-owner token mint (maintenance) |
| `/callback` | GET | none | OAuth redirect target; code→tokens; mints the KV session; sets `xd_sid`; CSRF-exempt |
| `/api/auth/mobile` | POST | none (ID token) | Body `{idToken}`; verifies with Google (aud/iss/exp/email_verified); mints the SAME session; returns `{ok, user, cookie}`; CSRF-exempt |
| `/me` | GET | cookie | `{user:{name,email,picture}, role, isAdmin, isDeveloper}` or 401 |
| `/token` | GET | cookie | Web: real Google access token. App sessions: synthetic `xdapp-…` marker |
| `/logout` | GET | cookie | Deletes the KV session + clears the cookie |
| `/config` | GET | none | `{clientId, …}` public client config |
| `/dev-login?token=` | GET | dev secret | Maintenance session for the owner account |

## B. App update (Android)

| Route | Method | Auth | Notes |
|---|---|---|---|
| `/api/app/version` | GET | none | `{ok, versionCode, versionName, apkUrl}` — `APP_LATEST` in worker.js |

## C. AI

| Route | Method | Auth | Notes |
|---|---|---|---|
| `/api/chat` | POST | cookie | Body `{message, role, email, class, history[], images[], forceModel}` → `{response, model, quotaUsed, quotaLimit, quotaExhausted, escalated}`. 20 msg/min rate limit. Server re-verifies role |
| `/api/chat/stream` | POST | cookie | Same body; **SSE**: `{"t":"step",icon,label,detail}`… then `{"t":"answer",text,provider,searched,sources}` or `{"t":"error"}` |
| `/api/chat/title` | POST | cookie | `{title?}` → LLM-chosen 2–6 word title |
| `/api/quota?email=&role=` | GET | cookie | `{used, limit, remaining}` per-user daily advanced-model quota |
| `/api/pdf` | POST | cookie | Markdown → PDF instruction flow |
| `/api/pdf/file` | POST | cookie | Re-render of a persisted PDF → **binary application/pdf** |
| `/api/tts` | POST | cookie | Text → speech audio |

## D. Drive proxy (`/drive/*`) — all cookie-authenticated, owner-token-backed

| Route | Method | Notes |
|---|---|---|
| `/drive/files?q=&fields=&orderBy=&pageSize=` | GET | Drive files.list passthrough |
| `/drive/files` | POST | Create file with metadata `{name, parents, description}` |
| `/drive/files/<id>` | PATCH | Overwrite file **content** (media upload) |
| `/drive/mkdir` | POST | `{name, parents[]}` → create folder |
| `/drive/mkpub` | POST | `{id}` → make file publicly readable |
| `/drive/delete?id=` | DELETE | Trash a file |
| `/drive/upload` | POST | multipart (metadata + file) → Drive multipart upload |
| `/drive/download?id=[&export=mime]` | GET | Stream file bytes (or export Google Docs types) |
| `/drive/media?id=` | GET | Alias of download |

## E. Live classes

| Route | Method | Auth | Notes |
|---|---|---|---|
| `/api/live/start` | POST | cookie (teacher) | `{class, subject, method:"obs", presetId?}` → creates YouTube broadcast+stream, KV `live:<class>`, returns stream URL/key |
| `/api/live/status?class=` | GET | cookie | State for one class |
| `/api/live/status-all` | GET | cookie | `{classes:{<name>:{teacherEmail, subject, startedAt, scheduledEndAt, videoId, broadcastId, …}}}` |
| `/api/live/extend` | POST | cookie (teacher) | Extend the scheduled end |
| `/api/live/end` | POST | cookie (teacher) | End broadcast → playlist, delete KV state |
| `/api/live/recordings` | GET | cookie | Class playlists (YouTube) |
| `/api/live/chat/post` | POST | cookie | Firebase chat post |
| `/api/live/hand` | POST | cookie | Raise/lower hand |
| `/api/live/chat/moderate` | POST | cookie (teacher) | Delete message / ban |
| `/api/live/action` | POST | cookie | Chat actions |

## F. Schedule & presets

| Route | Method | Auth | Notes |
|---|---|---|---|
| `/api/schedule` | GET | cookie | `{schedule:{Monday:[{time,class,subject,teacher}], …}}` |
| `/api/schedule/set` | POST | cookie | Replace the whole schedule |
| `/api/presets/create` | POST | cookie | `{name, chatMode, …}` |
| `/api/presets/my` | GET | cookie | Own presets |
| `/api/presets/shared` | GET | cookie | Shared presets |
| `/api/presets/<id>` | PUT/DELETE/POST | cookie | Update / delete / share |
| `/api/presets/shared/<id>` | DELETE/POST | cookie | Unshare / import |

## G. Users & admin

| Route | Method | Auth | Notes |
|---|---|---|---|
| `/api/user/profile` | GET/POST | cookie | Read/write own profile |
| `/api/user/role` | POST | cookie | Change role (admin) |
| `/api/admin/promote` | POST | cookie (admin) | Promote to teacher |
| `/api/admin/revoke` | POST | cookie (admin) | Revoke teacher |
| `/api/transcript/verify` | POST | — | Report-card verification |
| `/admin/ai-status?deep=1` | GET | backend-key | Per-key AI provider health (real generation probes) |

## H. Internal (server-to-server, `X-Backend-Key`)

| Route | Method | Notes |
|---|---|---|
| `/internal/ai/call` | POST | The **key proxy**: backend sends messages, Worker attaches provider keys and performs the LLM call |

## I. THINKING backend (`http://play.crazycloud.online:25570`)

All backend-key authenticated:

| Route | Method | Notes |
|---|---|---|
| `/health` | GET | Version/uptime |
| `/ai/search` | POST | `{query,max,agent}` → search results |
| `/ai/fetch` | POST | `{url,maxChars,agent}` → page text |
| `/ai/research` | POST | `{question|queries[], depth, agent}` → research pack |
| `/ai/chat` | POST | Full orchestration (planner→research→answer via key proxy) |
| `/ai/chat/stream` | POST | Same, SSE steps + answer |
| `/ai/title` | POST | Chat title LLM pass |
| `/ai/pdf` | POST | Markdown → real PDF binary |
| `/ai/relay/gemini` | POST | AES-GCM key relay for geo-blocked Gemini |
| `/ai/log?agent=` | GET | Agent terminal trail |
| `/pdf` | POST | PDF generation (legacy path) |
| `/files/*` | * | File ops used by the AI pipeline |
| `/exec` | POST | Sandboxed exec (admin/maintenance) |

## J. Firebase Realtime DB (direct REST, no worker)

```
GET/PUT  /attendanceRegister/<class>_<sec>/students.json
GET/PUT  /attendance/<class>_<sec>/<yyyy-MM-dd>/marks.json
GET/POST /liveClasses/<class>/chat.json        (live chat)
GET/POST /liveClasses/<class>/hands.json       (hand raise)
```

---

### Common error shapes

- `{ "error": "Not authenticated" }` — 401, no/dead session.
- `{ "error": "Forbidden origin" }` — 403, CSRF block on a mutation.
- `{ "error": "Only teachers can…" }` — 403, role gate.
- `{ "error": "Too many messages. Please wait Ns." }` — 429.
- Drive routes pass Google's error JSON through with the upstream status.
