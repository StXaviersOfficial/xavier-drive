# 09 — Timetable

Per class-and-section weekly period grid.

---

## 1. Storage model

```
Xavier-Drive/TIMETABLE/tt_<Class>_<Section>.json
```

Example `tt_Class_6_A.json`:

```json
{
  "Monday_1": "Mathematics · R. Kumar",
  "Monday_2": "Science · A. Sinha",
  "Tuesday_1": "English · …",
  …
}
```

- Keys are `<Day>_<Period>` (Monday–Saturday, periods 1–8).
- Values are free text ("Subject / Teacher").
- The file's description: `{"cls":"Class 6","sec":"A","type":"timetable"}`.
- **Only non-empty cells are stored.**

## 2. Opening

On the site the timetable is a **modal overlay** on top of the current tab
(so whatever was underneath is untouched when it closes). Controls: class
dropdown (Class 1–12) + section dropdown (A–F).

## 3. Loading

1. Find the TIMETABLE folder (root-chain).
2. Look up `tt_<Class>_<Section>.json` by name.
3. Fetch its content via `GET /drive/media?id=…` (JSON).

## 4. Editing & saving (teachers / admins)

- Teachers get **editable cells** (a textarea per period/day); students see
  read-only text.
- **Save** builds the sparse JSON from non-empty cells, then:
  - file exists → `PATCH /drive/files/<id>` (overwrite content), or
  - no file → multipart `POST /drive/upload` with
    `{name:"tt_<Class>_<Section>.json", parents:[TIMETABLE], description:{cls,sec,type:"timetable"}}`.

## 5. Android app (v1.1.0)

Native screen with class/section pickers; renders the 8×6 grid in a
horizontally scrollable table; teachers edit cells and save through the
same PATCH/upload paths.
