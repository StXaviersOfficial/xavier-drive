# 🚑 RECOVERY PLAYBOOK — StXaviersOnline Backend Restoration

**Date:** 2026-09-24 · **Status: backend DOWN** — full diagnosis + step-by-step restore

---

## 1. What happened (diagnosis)

**Symptom reported:** Files tab shows
`Owner token refresh failed: {"error":"invalid_grant","error_description":"Bad Request"}`

**Two stacked problems were found:**

### Problem 1 — Drive owner refresh token is dead
The Worker refreshes Google Drive access using the owner's `refresh_token` (stored in the
`DRIVE_TOKEN_JSON` secret, cached in KV under `__owner_token__`). Google rejected it with
`invalid_grant`, meaning the token expired or was revoked. Most common cause: the OAuth
consent screen is in **Testing mode**, which kills refresh tokens after **7 days**.

### Problem 2 — The Worker itself is no longer serving (the bigger issue)
`https://stxavers-auth.quackeditzofficial.workers.dev/*` returns **HTTP 404 + `error code: 1042`**
for every path — identical to a nonexistent worker. The frontend (stxaviers.pages.dev, byte-identical
to this repo) still points to that URL, so **login, files, AI chat, live — everything is down**, not
just the files tab.

Possible causes: the Worker was deleted, renamed, or its **workers.dev route was disabled**
(dashboard → Worker → Settings → Domains & Routes).

### Bonus bug fixed while at it (commit `3f09897`)
`getOwnerToken()` cached the token object (including the refresh_token) in KV for 24 h and
**never purged it on refresh failure** — so even after rotating the `DRIVE_TOKEN_JSON` secret,
the zombie KV copy kept shadowing it for up to a day. Now: on refresh failure the KV entry is
purged and the request retries once with the secret's refresh token. Error messages for
`invalid_grant` now explain exactly how to fix it.

---

## 2. Recovery steps

### Step 0 — Check what state the Worker is in (2 min)
Cloudflare dashboard → **Workers & Pages** → look for `stxavers-auth`:

- **CASE A — it exists:** go to Settings → Domains & Routes → make sure the **workers.dev
  route is enabled**, then just redeploy latest code:
  ```bash
  export CLOUDFLARE_API_TOKEN="..." CLOUDFLARE_ACCOUNT_ID="..."
  npx wrangler deploy
  ```
  Secrets survive a code-only deploy — nothing else to re-enter.
- **CASE B — it's gone:** full rebuild → follow Step 1B below.

### Step 1B — Full secret re-setup (only if the Worker was deleted)
Re-create these (dashboard → Worker → Settings → Variables & Secrets, or `wrangler secret put NAME`):

| Secret | Value / how to get it |
|---|---|
| `GOOGLE_CLIENT_ID` | OAuth **Web application** client ID (Google Cloud Console, project `stxaviers-official`) |
| `GOOGLE_CLIENT_SECRET` | matching client secret |
| `REDIRECT_URI` | `https://stxavers-auth.quackeditzofficial.workers.dev/callback` |
| `FRONTEND_URL` | `https://stxaviers.pages.dev` |
| `SESSION_SECRET` | any long random string |
| `DRIVE_TOKEN_JSON` | `{"refresh_token":"..."}` → regenerate in Step 2 |
| `FIREBASE_DB_URL` | `https://stxaviers-official-default-rtdb.firebaseio.com` |
| `TRANSCRIPT_SECRET` | shared secret — must match the one in `transcript.js` on the server |
| `GROQ_KEY` | Groq API key (Llama-3.3 chat + Whisper) |
| `CEREBRAS_KEYS_JSON` | JSON array of Cerebras keys |
| `GEMINI_KEY_1` … `GEMINI_KEY_5` | Gemini keys (quota display / fallbacks) |
| `STUDENT_GEMINI_LIMIT` | e.g. `30` |
| `YT_REFRESH_TOKEN` | YouTube Data API offline refresh token (owner account) |

KV binding `KV_SESSIONS` → namespace `ce420bf73a614b1cb1b8b8aa5213f40f` (already in
`wrangler.toml`; namespaces are account-level and survive worker deletion).

Then `npx wrangler deploy`.

### Step 2 — Regenerate the Drive owner refresh token (5 min, permanent fix included)
1. Google Cloud Console → the project holding the OAuth client → **APIs & Services → OAuth
   consent screen** → click **Publish app** (set to Production). ⚠️ This is the permanent fix —
   Testing mode kills refresh tokens every 7 days. Unverified-app warning is fine for school use.
2. Open **https://developers.google.com/oauthplayground**
3. Gear icon (top-right) → tick **"Use your own OAuth credentials"** → paste Client ID + Secret
4. In "Input your own scopes" enter: `https://www.googleapis.com/auth/drive`
5. **Authorize APIs** → sign in with the account that **owns the school Drive files**
6. **Exchange authorization code for tokens** → copy the `refresh_token`
7. Build the secret value (one line):
   `{"refresh_token":"1//0gXXXX....your...token...."}`
8. Update it:
   ```bash
   npx wrangler secret put DRIVE_TOKEN_JSON   # paste the JSON line above
   ```
9. Old cached token purges automatically now (the `3f09897` fix). If running an old deploy,
   delete KV key `__owner_token__` in dashboard → KV → KV_SESSIONS instead.

### Step 3 — Verify
```bash
curl https://stxavers-auth.quackeditzofficial.workers.dev/config
# expect: {"clientId":"....apps.googleusercontent.com"}
```
Then in the browser: Google login works → Files tab lists files → AI chat replies.

### If Google login itself fails after restore
The OAuth client may have been deleted/modified in Google Cloud Console:
- APIs & Services → Credentials → OAuth 2.0 Client IDs → **Web application** type
- **Authorized redirect URIs:** `https://stxavers-auth.quackeditzofficial.workers.dev/callback`
- **Authorized JavaScript origins:** `https://stxaviers.pages.dev`
- Drive API + YouTube Data API v3 must be **enabled** for the project

---

## 3. Backups in place (2026-09-24)

- Git branch `backup/pre-android-20260924` — exact pre-fix state
- `xavier-drive-backup-2026-09-24.zip` — full repo incl. `.git` history
- Private repo `StXaviersOfficial/credentials-vault` — credential backup

## 4. Prevention

- OAuth consent screen → **Production** (done in Step 2.1)
- The `invalid_grant` error message now spells out the fix in-app
- Recommended next: a GitHub Actions deploy workflow + uptime ping (e.g. ntfy alert on
  `/config` failure) so a dead worker is noticed within minutes, not weeks
