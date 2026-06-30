# StXaviersOnline (xavier-drive)

The official student/teacher portal for St. Xavier's Jr./Sr. School, Muzaffarpur, Bihar.

## 🏗️ Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Cloudflare    │     │   Cloudflare     │     │    Firebase     │
│   Pages         │────▶│   Worker         │────▶│    RTDB         │
│  (index.html)   │     │  (worker.js)     │     │ (live chat,     │
│  stxaviers.     │     │  stxaviers-auth. │     │  attendance,    │
│  pages.dev      │     │  workers.dev     │     │  profiles)      │
└─────────────────┘     └───────┬──────────┘     └─────────────────┘
                                ├────▶ Google Drive (files, logbook, roles)
                                ├────▶ Groq API (Llama-3.3 chat + Whisper TTS)
                                ├────▶ Cerebras API (10 keys, load-balanced)
                                ├────▶ Gemini API (2.0 Flash, 5 keys)
                                ├────▶ YouTube Data API (live broadcasts)
                                └────▶ Pollinations AI (image generation)
```

## 📁 Files

| File | Description |
|------|-------------|
| `index.html` | Frontend — single-file HTML/CSS/JS deployed to Cloudflare Pages |
| `worker.js` | Backend — Cloudflare Worker with all API routes |
| `transcript.js` | Node.js script for Termux — transcribes YouTube recordings |
| `wrangler.toml` | Worker config — KV binding + cron trigger |
| `privacy.html` | Privacy Policy page |
| `terms.html` | Terms of Service page |
| `firebase-token.js` | Helper — generates Firebase admin OAuth token |

## 🚀 Deployment

```bash
# 1. Set Cloudflare credentials
export CLOUDFLARE_API_TOKEN="your_token"
export CLOUDFLARE_ACCOUNT_ID="your_account_id"

# 2. Deploy Worker
wrangler deploy

# 3. Deploy Pages
mkdir -p /tmp/deploy && cp index.html privacy.html terms.html /tmp/deploy/
wrangler pages deploy /tmp/deploy --project-name=stxaviers --branch=main
```

## 🔑 Required Secrets (set via `wrangler secret put`)

```
GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, REDIRECT_URI, FRONTEND_URL
SESSION_SECRET, DRIVE_TOKEN_JSON, GROQ_KEY
GEMINI_KEY_1..5, FIREBASE_DB_URL, STUDENT_GEMINI_LIMIT
YT_REFRESH_TOKEN, CEREBRAS_KEYS_JSON
```

## 🔒 Security

- All API keys stored as Cloudflare secrets (never in frontend)
- CSRF protection on all state-changing endpoints
- Role verification (Developer > Admin > Teacher > Student)
- Session cookies: HttpOnly, Secure, SameSite=None, signed
- AI moderation on live chat (Groq checks every message)
- DOMPurify sanitizes all AI-rendered markdown
- Teacher-only actions go through worker with role verification
