# 10 — Attendance (teacher register)

Teachers-only daily attendance, stored in **Firebase Realtime Database**
(not Drive — it needs per-student writes, not files).

---

## 1. Firebase paths

```
attendanceRegister/<Class_key>_<Section_key>/students
    → [ {"name":"Student Name","roll":1}, … ]

attendance/<Class_key>_<Section_key>/<date_key>/marks
    → { "<roll>": "P" | "A", … }
```

- `Class_key` = class with non-alphanumerics replaced by `_` (e.g.
  `Class_6`); date format `yyyy-MM-dd` (safe chars only).
- `marks` maps **roll number → P/A**.

## 2. The flow (teacher)

1. Pick **class + section + date** (defaults to today).
2. The register loads from Firebase (`GET …/attendanceRegister/<key>.json`).
   - Empty register → the "Manage Register" prompt (add students first).
3. Existing marks for that date load (`GET …/attendance/<key>/<date>.json`)
   and pre-fill the toggles.
4. Each student row: name, roll, **Present / Absent** toggle.
5. **Save Attendance** → `PUT …/attendance/<key>/<date>/marks` with the map.

## 3. Managing the register

- Add students (name + roll number), remove students.
- The register is shared per class-section — every teacher of that section
  sees the same list.

## 4. Access rules

- Site: `role==='teacher' || isAdmin || isDeveloper`; students see a lock
  screen.
- The Android app mirrors this (teacher/admin/dev only) and uses the same
  REST calls.

## 5. Notes

- Firebase REST is called **directly from the client** (no worker hop) —
  the database's security rules govern access.
- Attendance is per-date; nothing is overwritten across days, and re-saving
  a date simply replaces that day's marks.
