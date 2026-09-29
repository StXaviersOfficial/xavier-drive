# 07 — Notices & Announcements

The notice board of the school: official announcements from teachers/admins,
targeted at the whole school or a single class.

---

## 1. Storage model

```
Xavier-Drive/ANNOUNCEMENTS/
├── WholeSchool/announcement_<timestamp>.json
├── Class 6/announcement_<timestamp>.json
└── …
```

Each announcement is a **JSON file** whose Drive `description` carries the
same metadata (so listings don't need to download every file):

```json
{
  "id": "an<timestamp>",
  "title": "Half-yearly exam schedule",
  "body": "Exams begin 12 October…",
  "target": "all" | "class",
  "cls": "Class 6",
  "attachments": [ {"id":"<driveId>","name":"schedule.pdf","type":"application/pdf"} ],
  "ts": 1690000000000,
  "date": "24 Sep, 2026",
  "time": "10:30 AM"
}
```

## 2. Posting (teachers / admins)

1. Fill title (required), message, choose **Entire School** or a specific
   class (class picker appears only then).
2. Optionally attach files (PDF/images) — each attachment is uploaded first
   via `/drive/upload` into the same folder with
   `description {cls, type:"announcement-attachment"}`, and its Drive ID is
   recorded in the announcement JSON.
3. The announcement JSON itself is uploaded as `announcement_<ts>.json`
   with the metadata as its description.

## 3. Listing

1. Find the ANNOUNCEMENTS root (the standard root-chain: `Xavier-Drive` →
   `ANNOUNCEMENTS`).
2. List JSON files directly in the root (whole-school) **and** in every
   class subfolder (per-class).
3. Parse each file's description metadata; keep entries with a `title`;
   sort by `ts` descending.

## 4. Reading (students)

- Notices are filtered: a student with a selected class sees whole-school
   notices **plus** their own class's notices.
- Cards show title, body, target chip, human date/time, and attachments
   (image/PDF chips that open the attachment).

## 5. Deletion (teachers / admins)

`DELETE /drive/delete?id=<driveId>` on the announcement JSON file.

## 6. Unread badge

The tab button shows a badge with the count of **unseen** announcements
(tracked client-side); opening the Notices tab clears it.

## 7. Android app (v1.1.0)

The app's Notices tab lists the same cards (title, body, date, target chip),
opens attachments via the same Drive media endpoint, and gives teachers the
post + delete flows natively (title, body, whole-school/class target).
