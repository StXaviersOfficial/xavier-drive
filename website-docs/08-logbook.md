# 08 — Logbook (class activity photos)

The Logbook is the school's photographic class diary: what happened in each
class-section on each day.

---

## 1. Storage model

```
Xavier-Drive/LOGBOOK/
└── <Class>/               e.g. "Class 6"
    └── Section-<X>/       e.g. "Section-A"
        └── <date>_<photo name>
```

Each photo's Drive `description`:

```json
{"cls":"Class 6","sec":"A","date":"2026-09-24","cap":"Science experiment","ts":1690000000000,"type":"logbook"}
```

- Filenames are **prefixed with the date** so Drive lists them in order.
- Grouping happens client-side: photos with the same
  `cls | sec | date | caption` form one **entry**.

## 2. Teacher: add an entry

1. Pick class + section + date (defaults to today) + optional caption.
2. Select one or more photos.
3. Each photo uploads via `/drive/upload` (multipart) into
   `LOGBOOK/<Class>/Section-<X>/` with the metadata description above;
   a progress bar tracks per-photo uploads.

## 3. Browsing

1. Walk `LOGBOOK → class folders → section folders → files`.
2. Group into entries; sort by newest.
3. **Filters:** by date, by class, by section.
4. Entries render as photo grids with the class/section/date/caption header.

## 4. Android app (v1.1.0)

The app's Logbook screen lists grouped entries with thumbnails (downsampled
bitmaps for memory safety), supports class/date filters, and gives teachers
the same upload flow (class, section, date, caption, multi-photo picker).
Tapping a photo opens a full-screen viewer.
