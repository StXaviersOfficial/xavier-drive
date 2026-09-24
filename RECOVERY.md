# RECOVERY.md — XavierDrive (StXaviersOnline) Backend

> **CORRECTION (2026-09-24):** an earlier version of this file claimed the
> Worker was deleted (Cloudflare "error 1042"). That diagnosis was WRONG —
> the 1042 came from a **typo'd URL** (`stavers-auth` instead of
> `stxaviers-auth`). The real Worker has been alive the whole time.
> Do not trust any diagnosis that uses the no-X URL.

## Live infrastructure (verified 2026-09-24)

| Piece | URL / ID | Status |
|---|---|---|
| Worker (backend API) | `https://stxaviers-auth.quackeditzofficial.workers.dev` | LIVE |
| Frontend | `https://stxaviers.pages.dev` | LIVE |
| KV namespace `KV_SESSIONS` | `ce420bf73a614b1cb1b8b8aa5213f40f` | bound |
| Android update endpoint | `/api/app/version` | LIVE (new) |

⚠️ The Worker hostname is `stxaviers-auth` — **with** the "x"
(s-t-a-v-e-r-s is WRONG, s-t-**x**-a-v-e-r-s is RIGHT).
The typo'd hostname produces Cloudflare error 1042, which looks exactly like
a deleted Worker. Always copy URLs from this file.

## Deploying the Worker

Cloudflare auth: **Global API Key works** (value in the credentials vault).

```bash
cd <this repo>
CLOUDFLARE_EMAIL=<email> CLOUDFLARE_API_KEY=<global key> \
CLOUDFLARE_ACCOUNT_ID=abc74356d298f6d9a0df34b542d56a3a \
  npx wrangler@4 deploy
```

Secrets survive deploys (stored separately from code). Current secrets (17):
`GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, REDIRECT_URI, FRONTEND_URL,
SESSION_SECRET, DRIVE_TOKEN_JSON, FIREBASE_DB_URL, TRANSCRIPT_SECRET,
GROQ_KEY, CEREBRAS_KEYS_JSON, GEMINI_KEY_1..5, STUDENT_GEMINI_LIMIT,
YT_REFRESH_TOKEN`

Values for most are in the credentials vault. **Unknown values** (set in an
old workspace, never echoed anywhere): `GOOGLE_CLIENT_ID`,
`GOOGLE_CLIENT_SECRET`, `SESSION_SECRET`, `YT_REFRESH_TOKEN`.
→ Therefore: **never DELETE the script** — a recreate would lose these.
Only ever deploy updates on top.

## Current known issue — Drive owner token dead (invalid_grant)

Files tab shows `Owner token refresh failed: invalid_grant`.

- **Cause:** the Google OAuth consent screen is in *Testing* mode → refresh
  tokens die every 7 days.
- **Self-heal already deployed** (commit 3f09897): on `invalid_grant` the
  Worker purges the zombie KV-cached `__owner_token__` and retries once
  with the secret's refresh token, then returns an actionable error.

### Fix (owner, one-time, ~10 minutes)

1. Google Cloud Console → project `stxaviers-official` → APIs & Services →
   OAuth consent screen → **PUBLISH APP** (push to Production).
2. Open https://developers.google.com/oauthplayground → gear icon →
   "Use your own OAuth credentials" → the Web client ID/secret (same values
   as the Worker's `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`).
3. Authorize the Drive scopes the Worker uses
   (`drive.readonly` + `drive.file`).
4. Exchange the code for tokens → copy the `refresh_token`.
5. Secret value format: `{"refresh_token":"<token>"}`
6. Apply:
   ```bash
   echo '{"refresh_token":"<token>"}' | \
     CLOUDFLARE_EMAIL=... CLOUDFLARE_API_KEY=... CLOUDFLARE_ACCOUNT_ID=... \
     npx wrangler@4 secret put DRIVE_TOKEN_JSON --name stxaviers-auth
   ```
7. The zombie KV cache clears automatically on the next failed refresh
   (self-heal), or delete key `__owner_token__` from `KV_SESSIONS` manually.

## Android app — Xavier's Drive

- Repo: `StXaviersOfficial/stxaviers-android` (private).
- **v1.0.0 = complete restart** (2026-09-24): logo + name + update checker +
  white blank page, zero dependencies, crash-proof by design.
- Update flow: app checks `GET /api/app/version` (Worker) → APK is hosted at
  `https://stxaviers.pages.dev/apk/<file>.apk`.
- Releasing a new version: bump version in `app/build.gradle.kts` →
  update `APP_LATEST` in `worker.js` → deploy Worker → upload new APK to
  Pages `apk/` dir → publish GitHub Release. Details in the Android README.
- Release signing keystore + password: credentials vault
  (`xavierdrive-release.keystore`, alias `xavierdrive`).
  All future updates MUST use this key.
