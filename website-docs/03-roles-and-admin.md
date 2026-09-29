# 03 — Roles & Admin

Who can do what on XavierDrive, how roles are assigned, and the admin tools.

---

## 1. The four roles

| Role | Who | Sees / can do |
|---|---|---|
| **student** | everyone by default | Files (browse + download), Live (watch + chat + raise hand), Logbook (view), Notices (read, filtered to their class), Timetable (read), XavierDrive AI, own profile |
| **teacher** | assigned by an admin | Everything a student can, PLUS: upload/delete class files, post notices, add logbook entries, edit timetables, **the Attendance register**, start/end live classes, live-class moderation |
| **admin** | promoted via the admin center | Everything a teacher can, PLUS: promote/revoke teachers, delete any notice, admin center, live admin tab |
| **developer** | hardcoded owner account | Full admin powers + developer diagnostics (never shown as a normal role) |

Role checks in the UI are `role==='teacher' || isAdmin || isDeveloper` for
teacher-gated features — and every privileged **server** route re-verifies
the role itself (`verifyRole`) so a client can never escalate itself by
editing JavaScript.

---

## 2. Where roles live

1. **USERS folder in Drive** — `Xavier-Drive/USERS/<email>` (a metadata
   file per user; the description JSON carries the role).
2. **ADMINS folder in Drive** — files listing admin emails.
3. **Hardcoded developer email** — the owner's account, checked directly in
   `verifyRole`.
4. **A KV role cache** per email (with short TTL) to keep `/me` fast.

`verifyRole(env, email)` is the single source of truth — `/me`, `/api/chat`,
all live-class routes and the admin routes all call it server-side.

---

## 3. The admin center

Available to admins (and the developer) from the site. Functions:

- **Promote to teacher** — `POST /api/admin/promote` (adds the email to the
  teacher list in Drive).
- **Revoke teacher** — `POST /api/admin/revoke`.
- **Change role** — `POST /api/user/role`.
- **User profile read/write** — `GET/POST /api/user/profile`.
- Live-class **admin tab** — force-end any class, clear chat, moderate.

---

## 4. Android app

The app reads `role`, `isAdmin`, `isDeveloper` from `/me` and mirrors the
same gating: the Attendance screen is teacher/admin/developer-only; upload
buttons (files, notices, logbook, timetable editing, live start) appear only
for those roles. The server still re-verifies every privileged call, so the
app's gating is purely presentational.
