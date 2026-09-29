# 13 — The Android App (XavierDrive)

A **100% native** Android app (pure Java, zero Gradle, zero WebView portal)
that talks to the exact same Worker backend as the website. This is the
owner's standing architecture: **native screens only** — WebViews are
allowed solely as invisible bridges (the OAuth fallback and the legal
document viewer).

---

## 1. Build facts

- Package: `com.stxaviers.app` · minSdk 24 · targetSdk 34.
- Built with `aapt2 + javac + d8 + zipalign + apksigner` (no Gradle) via
  `android-app/build-apk.sh`; the AAR stack (Credential Manager, Play
  Services auth, androidx closure) is merged by hand — the manifest declares
  the library components a Gradle build would merge automatically.
- Release signing SHA-1 `9F:24:6E:BA:…:C3:18:C8` — matches the Google
  Cloud Android client, so the native account picker is verified.
- Distributed via GitHub Releases (StXaviersOfficial/stxaviers-android) and
  `https://stxaviers.pages.dev/apk/xavierdrive<version>.apk`.

## 2. Screens (v1.1.0)

| Screen | Role | What it does |
|---|---|---|
| **Splash** | all | Animated fluid background; runs the **update check + session check in parallel**; routes to Home (signed in) or Login; owns the update downloader |
| **Login** | signed-out | Brand hero + Continue with Google (3-step native chain) |
| **Auth bridge** | fallback | The ONLY sign-in WebView (hardened, Chrome UA) |
| **Home** | all | Greeting + identity, notices hero, quick access (Files/Live/Timetable/Logbook), explore grid (Notices/Attendance/AI/School), bottom nav |
| **Files** | all | Drive library: browse/filter/search, download & open, teacher upload/delete |
| **XavierDrive AI** | all | Chat with the agent (same /api/chat), typing states, local session persistence, new-chat |
| **Notices** | all | Announcement feed + teacher post/delete |
| **Profile** | all | Identity, class selector, sign out, **Terms of Service + Privacy Policy** (legal lives here, NOT in the main UI) |
| **Timetable** | all | Class+section grid; teacher editing + save |
| **Logbook** | all | Photo diary browse + teacher upload |
| **Live** | all | Live-now list, join via YouTube, recordings, schedule; teacher start/end + stream keys |
| **Attendance** | teacher/admin | Register + daily marking (Firebase) |
| **School** | all | School identity, contacts, leadership |
| **Settings** | all | Version, manual update check, website link |

Every screen exists in **light AND dark** (Material 3 tokens, night
palette twins for every colour).

## 3. The update system (v1.0.10 semantics)

1. Splash fetches `GET /api/app/version` → `{versionCode, versionName, apkUrl}`.
2. Out-of-date → the update panel offers **Download**.
3. The APK downloads to a **version-scoped file**
   (`xavierdrive-update-<code>.apk`); progress shows live.
4. **If the file is already on disk** (user ignored it, install failed,
     app restarted) the button becomes **Install Update** — it reuses the
     cached file (size-verified against the server HEAD, corrupt partials
     deleted) and **never re-downloads**.
5. Stale update files for older versions are cleaned automatically.
6. Install runs through the system installer
   (`REQUEST_INSTALL_PACKAGES`), with a friendly permission hint.

## 4. Session handling

- The app keeps the session **cookie in the system CookieManager** — one
  shared jar for every activity.
- `SessionProbe` (one shared class): cookie → `GET /me` →
  **ALIVE / DEAD / UNKNOWN(cookie present)**. Login skips only on
  definitive ALIVE; splash routes UNKNOWN-with-cookie optimistically to
  Home (offline-friendly); Home logs out only on definitive DEAD.
- Returning users land on **Home directly** — the login page appears only
  when there is no session.

## 5. Mutation header

All POST/PATCH/DELETE calls from the app carry
`X-XavierDrive-App: android` (see 02-authentication §4) — the Worker's CSRF
layer accepts it because browsers cannot send custom headers cross-origin.

## 6. What's intentionally NOT in the app (yet)

- In-class live chat + hand-raise (website for now).
- AI streaming steps (the app uses the JSON endpoint; steps UI is a
  follow-up), charts/PDF artifact rendering (plain text answers render
  today; artifact blocks are shown as text).
- Theme picker (follows system dark/light).
