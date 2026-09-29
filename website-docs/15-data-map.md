# 15 — Data Map (where everything physically lives)

One table per store. If you ever wonder "where is X stored?", it's here.

---

## 1. Google Drive — `Xavier-Drive/` (school owner's 5 TB Workspace)

| Path | Content | Written by |
|---|---|---|
| `FILES/<Class>/…` | Study material; description = `{cls, sub, chp}` | Teachers (upload) |
| `LOGBOOK/<Class>/Section-<X>/` | Activity photos, date-prefixed names; description = `{cls,sec,date,cap,ts,type:"logbook"}` | Teachers |
| `ANNOUNCEMENTS/WholeSchool/` + `ANNOUNCEMENTS/<Class>/` | `announcement_<ts>.json`; description = the announcement object | Teachers/admins |
| `TIMETABLE/tt_<Class>_<Section>.json` | Sparse `{Day_Period: "Subject · Teacher"}` | Teachers (save) |
| `CHATS/<session>/` + `artifacts/<type>/` | AI chat archives + their generated files | AI flow |
| `USERS/<email>` | Per-user metadata (role etc.) | Worker |
| `ADMINS/` | Admin email lists | Worker/admin |

## 2. Cloudflare KV (`KV_SESSIONS` binding)

| Key | Value |
|---|---|
| `sess:<sid>` | Session: user object, tokens, expiry (7 days) |
| `live:<class>` | Live-class state (broadcastId, videoId, stream key, teacher, startedAt, scheduledEndAt) |
| `rl:<email>:<bucket>` | Rate-limit counters (chat: 20/min) |
| quota keys | Per-user daily advanced-model usage |
| role cache keys | `verifyRole` results (short TTL) |

## 3. Firebase Realtime Database

| Path | Content |
|---|---|
| `attendanceRegister/<Class>_<Sec>/students` | `[{name, roll}]` |
| `attendance/<Class>_<Sec>/<date>/marks` | `{roll: "P"\|"A"}` |
| `liveClasses/<class>/chat` | Live chat messages |
| `liveClasses/<class>/hands` | Raised-hand queue |

## 4. Browser localStorage

| Key | Content |
|---|---|
| `xd` | Boot cache: `{rid: rootFolderId, uemail, role, tok}` |
| `xd_uprefs_<email>` | Theme, font family/size, chat background |
| `xd_classes` / `xd_subjects` | Custom class/subject lists |
| `xd_logbook` / `xd_announcements` / `xd_timetable` | Legacy caches (Drive is the truth now) |
| chat session mirrors | `_msgTextCache` etc. for artifact re-render |

## 5. Browser IndexedDB

| DB | Content |
|---|---|
| `xavierdrive-pdfs` | Generated PDF blobs (survive reloads; cold path = server re-render) |

## 6. Android app device storage

| Location | Content |
|---|---|
| System CookieManager | The `xd_sid` session cookie (app-wide) |
| App SharedPreferences | Identity cache (`name/email/role/class`), AI chat history, last-seen-notice timestamp |
| App external files dir | `xavierdrive-update-<code>.apk` (version-scoped update cache, auto-cleaned), downloaded files |

## 7. Secrets (offline vault + Worker secrets ONLY — never in the repo)

- AI provider keys (Groq ×5, OpenRouter ×5, Gemini ×5, CF Workers AI ×6)
- `SESSION_SECRET`, `BACKEND_KEY`, `DEV_LOGIN_SECRET`
- Drive owner refresh token, YouTube channel token
- Firebase service credentials
