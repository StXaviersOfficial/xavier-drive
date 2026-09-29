# 12 — Themes & Personalization

XavierDrive's chat is deeply personalizable. Everything below persists
per-user (localStorage keyed by email) and re-applies on every load.

---

## 1. The 15 themes

| Theme | Vibe |
|---|---|
| Midnight | classic dark blue (default) |
| Pure Light | clean white |
| Ocean Blue / Royal Purple / Forest Green / Sunset Orange | dark colour worlds |
| Cherry Pink / Rose Quartz | pink worlds |
| Midnight Gold | gold on black |
| Mint Fresh | cool mint |
| Slate Pro | professional muted |
| Cyber Neon | neon punk |
| Day Ocean / Cream Paper / Day Mint | light themes |

Each theme defines the full CSS variable set (backgrounds, surfaces,
borders, four accent colours, text, chat panel, input row).

## 2. Chat typography

- **Font family** selector — Poppins, Merriweather, and more (plus the
  default Space Grotesk).
- **Font size** — S / M / L / XL.
- **Chat background** — solid colours + animated gradients (with
  **auto-contrast**: the AI text colour is recomputed from the dominant
  colour of the background so text stays readable on any of them, including
  the animated ones).

## 3. The white-theme font bug (fixed, historical)

`body.light` used to re-declare the font/size CSS variables on `<body>`,
shadowing the inline variables set on the root — which locked the white
theme's chat font no matter what the user picked. Fixed by removing the
re-declarations and setting all chat variables on `document.body` inline.
Auto-contrast similarly learned the animated light backgrounds.

## 4. Install-as-app

- A **PWA manifest** (`manifest.json`, XavierDrive name + sparkle icons) —
  "install" shows the browser's add-to-home-screen flow.
- The install modal is XavierDrive-branded; dismissals are remembered.

## 5. Where preferences live

- `xd` (global: last class, root folder cache), `xd_uprefs_<email>`
  (per-user: theme, font, size, background), `xd_classes` /
  `xd_subjects` (custom class/subject lists).
- Chats/sessions persist in Drive (see 04) — everything else above is
  device-local.

## 6. Android app

The app follows the **system light/dark setting** (Material 3 behaviour)
with a full dark palette — every screen is designed twice, once per mode.
