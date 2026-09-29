# 11 — Live Classes

Real-time live teaching over **YouTube Live**, with an in-page student
experience (watch, chat, raise hand) and an OBS-based teacher workflow.

---

## 1. How it works (concept)

- The teacher's camera/screen is streamed with **OBS Studio** (RTMP) into a
  YouTube **live broadcast** created by the Worker.
- Students join from the Live tab — the page embeds the YouTube player and
  connects to the class's **Firebase chat + hand-raise**.
- Class state (who is live, stream keys, teacher, subject, start time) lives
  in Worker KV under `live:<class>`; the chat itself lives in Firebase.
- Every class also lands in a **YouTube playlist** per class → the
  Recordings tab.

## 2. Starting a class (teacher)

`POST /api/live/start {class, subject, method:"obs", presetId?}` →

1. Role check (teachers only) + no existing broadcast for that class (409
   otherwise).
2. Worker creates a **liveBroadcast** (unlisted, not made-for-kids, 90-min
   scheduled window) and a **liveStream** (RTMP ingestion, 720p/30fps) via
   the YouTube Data API with the school channel's token.
3. Binds stream↔broadcast, stores the state in KV.
4. Returns the **Stream URL + Stream Key** for OBS + a join link.

**Teacher workflow:** Start → copy URL+key into OBS → Start Streaming →
students see LIVE in ~10 s → **End Class** in XavierDrive (never just close
OBS — ending moves the recording into the class playlist and cleans state).
`POST /api/live/extend` extends the window; a **cron trigger auto-ends**
any broadcast older than 3 hours (and files it into the playlist).

## 3. Student experience

- **Live Now:** every active class with subject, teacher and elapsed time;
  Join → embedded YouTube player.
- **Live chat:** Firebase-backed, updates in near-real-time (2 s polling on
  mobile), with the class name as the room.
- **Raise hand:** a hand-raise queue the teacher sees live; the teacher can
  call on / dismiss hands (`/api/live/hand`).
- **Recordings:** `GET /api/live/recordings` lists the class playlists.
- **Schedule:** `GET /api/schedule` — the weekly class schedule (day →
  entries with time, class, subject, teacher).

## 4. Moderation (teachers)

- `/api/live/chat/moderate` — delete a chat message or ban a chatter for
  the session.
- The admin tab can force-end ANY class and clear the chat.

## 5. Presets

Teachers can save **preset configurations** (e.g. "Math class — chat
restricted, hands auto-cleared"):

- `POST /api/presets/create {name, chatMode, …}`
- `GET /api/presets/my` — own presets; `GET /api/presets/shared` — presets
  shared by others; `POST /api/presets/<id>` share, `PUT` update, `DELETE`
  remove, `POST /api/presets/shared/<id>` import.

## 6. Android app (v1.1.0)

- Students: Live Now list → **Join** opens the class in the YouTube app
  (or browser fallback); Recordings and Schedule tabs.
- Teachers: start a class (class+subject form), see the stream URL + key,
  and end it — the same KV state drives both platforms.
- In-class chat/hands remain on the website (noted as the follow-up).
