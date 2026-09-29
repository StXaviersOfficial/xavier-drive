# 02 — Authentication & Sessions

Everything about how a person proves who they are on XavierDrive — on the
website, on the Android app, and how the session stays alive (or dies).

---

## 1. The Google OAuth clients (Google Cloud project `stxaviersapp`)

| Client | ID | Used by |
|---|---|---|
| **Web application** | `433599682518-viicspv9o6hv9pi5rn54tk5f8shmqd51…` | The website's OAuth redirect **and** the Android Credential-Manager flow (`serverClientId` — the ID token audience) |
| **Android (release)** | package `com.stxaviers.app` + release SHA-1 `9F:24:6E:…:C3:18:C8` | Play Services verifying the app's signature for the native account picker |
| **Installed (legacy)** | `433599682518-pkeb26l3lrk9jbe5lft6g2g0jl6833b4…` | Kept for the record (owner-supplied originally) |

The **same web client** serves both web sign-in and Android ID-token minting —
that is why one OAuth consent screen governs everything.

---

## 2. Website sign-in flow (`/login` → `/callback`)

1. User taps **Continue with Google** on the login hero.
2. Browser goes to `GET /login` on the Worker → the Worker builds a Google
   OAuth authorize URL (client ID, `openid email profile`, state, redirect to
   the Worker's `/callback`) and 302-redirects the browser to it.
3. Google shows the account chooser (school Google accounts).
4. Google redirects back to `GET /callback?code=…&state=…`.
5. The Worker:
   - exchanges the code for tokens at Google's token endpoint,
   - verifies the identity (email, email_verified),
   - reads/creates the user's profile,
   - **mints a session**: a random signed ID stored in Cloudflare KV under
     `sess:<id>` with the user object + access token + expiry (7 days),
   - replies with `Set-Cookie: xd_sid=<id>; …` and redirects to the site.
6. From then on every browser request to the Worker carries the `xd_sid`
   cookie — that IS the session.

### 2.1 Session verification — `GET /me`
Returns `{user:{name,email,picture}, role, isAdmin, isDeveloper}` or
`401 {error:"Not authenticated"}`. Called on boot and whenever the UI needs
to re-check.

### 2.2 The `/token` gate (and the Android lesson)
- Web sessions: `GET /token` returns `{access_token:"ya29…"}` (the real
  Google token). The site uses it ONLY as a "signed in with Google" marker —
  it is never used as a Bearer credential (all privileged calls go through
  the Worker with the cookie).
- **Android-native sessions cannot have a Google access token** (an ID token
  cannot mint one). The site used to redirect such sessions to Google's web
  login — which caused the infamous "please wait → enter email/password
  loop" on the app. Fixed: `/token` now returns a synthetic
  `xdapp-<random>` marker for app sessions, and the app additionally blocks
  the web login redirect at the native layer.

### 2.3 Sign out — `GET /logout`
Deletes the KV session, clears the cookie (Set-Cookie with expiry 0), and
the client wipes its local cache (`localStorage.xd`). Firebase listeners are
detached too.

---

## 3. Android app sign-in (native — no WebView portal)

The app signs in through a **three-step chain**, falling forward only when a
step is unavailable (this is the standard big-company pattern):

1. **Credential Manager** (Android 14+ default): `GetGoogleIdOption` with
   `serverClientId` = the WEB client ID above → returns a **Google ID token**.
2. **Legacy account chooser** (older devices / old Play Services): the
   classic `GoogleSignIn` picker listing every device account.
3. **Hardened WebView bridge** (`AuthActivity`, the ONLY WebView in the app):
   presents as Chrome (Google blocks WebView user-agents), runs the web flow,
   and extracts the session cookie.

Whichever step succeeds produces either an ID token or a session cookie:

- **ID token path:** `POST /api/auth/mobile {idToken}` → the Worker verifies
  the token against Google's tokeninfo endpoint (audience = web client,
  issuer, expiry, email_verified), **creates the exact same KV session the
  website uses**, and returns `{ok, user, cookie:"xd_sid=…"}`. The app drops
  that cookie into its app-wide `CookieManager` — from this moment the app
  and website share one session model.
- The route is **CSRF-exempt by design**: a native app sends no `Origin`
  header, and the request carries its own proof (the verified ID token).

### 3.1 Returning users (no login screen)
On cold start the app's splash runs **two checks in parallel** — the update
manifest and the saved session (`SessionProbe`: cookie → `/me` →
ALIVE / DEAD / UNKNOWN). When both finish:
- ALIVE (or UNKNOWN-with-cookie — offline-friendly optimism) → **straight to
  Home**, the login page is never shown.
- DEAD → Login page.
The probe classifies network errors as UNKNOWN, never as logged-out, so a
flaky connection can't log a user out.

---

## 4. CSRF defense

All state-changing requests (POST/PUT/PATCH/DELETE) to the Worker must come
from an **allowed Origin** (the Pages site, the workers.dev host, and a few
aliases) — checked by `csrfCheck()` before routing. GET/HEAD/OPTIONS are
always allowed.

**Exemptions** (each has its own proof):
- `/callback` — Google redirects don't carry an Origin header.
- `/internal/*` — server-to-server, authenticated by `X-Backend-Key`.
- `/api/auth/mobile` — native app, no Origin; proof = the verified ID token.
- **`X-XavierDrive-App: android`** (app v1.1.0+) — the native app marks its
  mutations with this custom header; browsers cannot send it cross-origin
  (the preflight denies it because it's not in
  `Access-Control-Allow-Headers`), so CSRF protection for the website stays
  fully intact while the app can POST/DELETE legitimately.

---

## 5. Dev-login (maintenance backdoor)

`GET /dev-login?token=<DEV_LOGIN_SECRET>` (secret stored only in the Worker
and the offline vault) mints a session for the owner account without Google.
Used for automated browser testing and emergency maintenance. The client-side
recipe: dev-login first (sets the cookie), then seed `localStorage.xd` on a
static path, then load the app (the slow `/token` path would otherwise
redirect the test browser to Google).

---

## 6. Session lifetime rules

| Event | Result |
|---|---|
| 7-day KV expiry | `/me` → 401 → client drops to login |
| Manual sign-out | KV entry deleted + cookie cleared |
| App update install | Session survives (cookie jar persists across app updates) |
| Server secret rotation | All sessions invalidate (signed with `SESSION_SECRET`) |
| Network failure with cookie present | Treated as UNKNOWN — user stays signed in (offline-friendly) |
