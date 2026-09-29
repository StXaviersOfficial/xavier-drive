# 06 — Files (the Drive tab)

The Files tab is the school's study-material library. Every file physically
lives in the school owner's 5 TB Google Drive; the site never talks to Drive
directly — everything flows through the Worker's `/drive/*` proxy with the
session cookie.

---

## 1. Storage model

```
Xavier-Drive/FILES/
├── Class 6/Images/<file>      (example structure created on upload)
├── Class 6/PDFs/<file>
├── Class 8/Videos/<file>
└── …
```

The **real indexing data lives in each file's Drive `description` field** as
a JSON object:

```json
{"cls":"Class 6","sub":"Science","chp":"Chapter 14 – Water"}
```

- `cls` = class, `sub` = subject, `chp` = chapter/topic (free text).
- Files without a `cls` in their description don't appear in the library.
- The `type` key is reserved for other features (announcement attachments
  carry `type:"announcement-attachment"`) — library files have no `type`.

## 2. Listing

One Drive query loads the library (client caches and filters locally):

```
GET /drive/files?q=trashed=false and mimeType!='application/vnd.google-apps.folder'
    and fullText contains 'cls'
    &fields=files(id,name,mimeType,size,modifiedTime,description)
    &orderBy=modifiedTime desc&pageSize=500
```

Client-side: keep only files whose description JSON has `cls` and no `type`.

## 3. The student view

- Header shows the selected class (the class chosen in the profile/class
  selector filters everything).
- **Filters:** Subject dropdown (15 standard subjects + custom), Chapter
  free-text search, file-name search, and type chips **All / PDF / Docs /
  Images / Video**.
- Each card: mime icon, file name, chips (class / subject / chapter),
  size + modified date.
- **Tap = viewer** — a full-screen overlay with the Drive preview iframe
  (`drive.google.com/file/d/<id>/preview`) and a Download button.
- **Download** — `GET /drive/media?id=…` streams the bytes through the
  Worker (session cookie required); on failure the browser is redirected to
  Drive's own `uc?export=download` URL.

## 4. The teacher view

Everything the student sees, plus:

- **Upload** — pick any file → a multipart POST to `/drive/upload` with:
  - metadata: `{name, parents:[<class/… folder>], description: JSON{cls,sub,chp}}`
  - the file bytes.
  - The Worker rebuilds the multipart/related body and forwards it to
    Google with the owner token.
- **Delete** — `DELETE /drive/delete?id=…` (moves to Drive trash).
- Class + subject + chapter dropdowns to organize new uploads.

## 5. Mime handling

| Kind | Detected by |
|---|---|
| PDF | mime contains `pdf` or name ends `.pdf` |
| Doc | `word`/`document` mimes or `.doc/.docx/.ppt/.pptx/.txt` |
| Image | mime starts `image/` |
| Video | mime starts `video/` |

## 6. Android app (v1.1.0)

The app's Files tab uses the exact same endpoints:
- browse + filter (class/subject/chapter/type/search),
- download to the app's files directory → **open via the system viewer**
  (FileProvider + ACTION_VIEW),
- teacher upload (system file picker → class/subject/chapter dialog) and
  long-press delete, gated by role exactly like the site.
