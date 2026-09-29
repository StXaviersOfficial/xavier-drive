# XavierDrive — Complete Website & System Documentation

> **St. Xavier's Jr./Sr. School, Goshala Road, Muzaffarpur 842002 — official digital platform.**
> This folder documents **every single part and function** of the XavierDrive
> website and its supporting systems, in full detail. It is written so that a
> brand-new developer (or the owner, or an AI agent) can understand exactly
> how everything works — every screen, every button, every API route, every
> storage location, every background process — without reading a single line
> of source code (although file/line pointers are included for those who do).

---

## What XavierDrive is — one paragraph

XavierDrive is the school's all-in-one platform: students and teachers sign in
with their **Google accounts**, and get **class files (study material)**, a
**live-class system (YouTube streaming + chat + hand-raise)**, a **class
logbook (activity photos)**, **notices & announcements**, **class timetables**,
a **teacher attendance register**, and **XavierDrive AI** — an agentic AI
assistant that searches the live web before answering, draws charts, creates
files and real PDFs, and can see attached images. Everything runs without any
school-hosted server: the frontend is on **Cloudflare Pages**, the brain is a
**Cloudflare Worker**, the AI engine runs on a **CrazyCloud (Pterodactyl)
server**, files live in the school owner's **5 TB Google Drive**, live-class
chat/attendance state lives in **Firebase Realtime Database**, and sessions
live in **Cloudflare KV**. There is also a **native Android app** that speaks
to the exact same backend.

---

## Folder map (read in order, or jump to what you need)

| # | File | What it covers |
|---|------|----------------|
| 01 | [01-architecture.md](01-architecture.md) | The full system: every server, every data store, every request path, deployment layout |
| 02 | [02-authentication-and-sessions.md](02-authentication-and-sessions.md) | Google sign-in (web + Android), session cookies, KV sessions, the /token gate, CSRF defense, dev-login |
| 03 | [03-roles-and-admin.md](03-roles-and-admin.md) | Student / teacher / admin / developer roles, how they're stored, promotion & revocation |
| 04 | [04-ai-chat.md](04-ai-chat.md) | XavierDrive AI end-to-end: the agent loop, live web research, all AI providers, quotas, streaming steps, chat titles, moderation, image understanding |
| 05 | [05-ai-artifacts.md](05-ai-artifacts.md) | Everything the AI can *produce*: charts, file boxes, ZIP downloads, real PDF generation, text-to-speech |
| 06 | [06-files-drive.md](06-files-drive.md) | The Files tab: Drive folder tree, upload with class/subject/chapter metadata, browsing, filtering, viewer, downloads, deletion |
| 07 | [07-notices.md](07-notices.md) | Announcements: posting, whole-school vs per-class targeting, attachments, listing & deletion |
| 08 | [08-logbook.md](08-logbook.md) | The class diary: photo entries grouped by class/section/date, upload, browsing, filters |
| 09 | [09-timetable.md](09-timetable.md) | Per class+section timetable grid, where it's stored, how editing & saving works |
| 10 | [10-attendance.md](10-attendance.md) | Teacher attendance register: student lists, marking, saving, Firebase layout |
| 11 | [11-live-classes.md](11-live-classes.md) | Live classes: YouTube broadcasts, OBS streaming, join links, live chat, hand raise, recordings, weekly schedule, presets, moderation, the 3-hour auto-end cron |
| 12 | [12-themes-and-personalization.md](12-themes-and-personalization.md) | All 15 themes, font & size settings, chat backgrounds, auto-contrast, install-as-app modal |
| 13 | [13-android-app.md](13-android-app.md) | The native Android app: every screen, sign-in chain, update system, session handling |
| 14 | [14-api-reference.md](14-api-reference.md) | **Every single HTTP endpoint** — method, auth, request body, response body, error cases |
| 15 | [15-data-map.md](15-data-map.md) | Where every piece of data physically lives (Drive folders, KV keys, Firebase paths, IndexedDB, localStorage) |
| 16 | [16-security-audit.md](16-security-audit.md) | The v1.1.2 full security audit: cookies, CSRF, Drive gating, the Firebase-auth fix, app hardening, standing recommendations |

---

## The five systems at a glance

```
                    ┌──────────────────────────────┐
                    │   Cloudflare Pages (static)  │
                    │   stxaviers.pages.dev        │
                    │   index.html (the whole UI)  │
                    └──────────────┬───────────────┘
                                   │ fetch (cookie)
                                   ▼
┌──────────────────┐     ┌──────────────────────────────┐     ┌─────────────────────┐
│ Google Drive     │◄────│   Cloudflare Worker          │────►│ Firebase RTDB       │
│ (5TB Workspace)  │     │   stxaviers-auth…workers.dev │     │ live chat, hands,   │
│ FILES/LOGBOOK/   │     │  auth · sessions · CSRF      │     │ attendance          │
│ ANNOUNCEMENTS/   │     │  /drive/* proxy (owner token)│     └─────────────────────┘
│ TIMETABLE/CHATS  │     │  /api/chat → backend         │
└──────────────────┘     │  /api/live/* → YouTube API   │
                         └───────┬──────────────────────┘
                                 │ X-Backend-Key
                                 ▼
                         ┌──────────────────────────────┐
                         │  THINKING server (CrazyCloud)│
                         │  play.crazycloud.online:25570│
                         │  research engine + chat      │
                         │  orchestration + PDF builder │
                         └───────┬──────────────────────┘
                                 │ calls back for LLM keys
                                 ▼
                         Groq / OpenRouter / CF Workers AI / Gemini
```

---

## Version & maintenance notes

- The live site URL is **https://stxaviers.pages.dev** (Cloudflare Pages,
  direct-upload deploys — NOT git-connected).
- The Worker is **stxaviers-auth** on the quackeditzofficial Cloudflare
  account (workers.dev URL above).
- The AI backend lives on the CrazyCloud Pterodactyl panel ("THINKING"
  allocation, India/Azure) and autostarts with the container.
- Source of truth for all code: GitHub **StXaviersOfficial/xavier-drive**
  (`worker.js`, `index.html`, `backend/server.js`, `android-app/`).
- This documentation reflects the system as of **September 2026, app v1.1.0**.
