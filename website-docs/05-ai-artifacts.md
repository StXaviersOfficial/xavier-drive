# 05 — AI Artifacts (charts, files, PDFs, voice)

XavierDrive AI doesn't just talk — it **produces things**. The answer
markdown can carry special fenced blocks the client renders as interactive
artifacts. This document explains each one end-to-end.

---

## 1. Charts — ` ```chart ` blocks

The model emits:

    ```chart
    {"type":"bar","title":"Class test averages",
     "labels":["A","B","C"],"series":[{"label":"Math","data":[62,71,58]}]}
    ```

- `type` supports **bar / line / pie** (bar groups multiple series).
- The site renders a crisp **canvas** chart (device-pixel-ratio aware,
  auto width, horizontally scrollable when wide — verified up to 1238 px of
  content in a 358 px viewport).
- Each chart has a **Download PNG** button.
- Charts **persist across reloads** — the raw block lives in the message
  text, so re-rendering rebuilds the canvas.

## 2. File boxes — ` ```file ` blocks

    ```file
    {"name":"answers.txt","mime":"text/plain","content":"…"}
    ```

- Rendered as a file card (icon by mime, name, size) with a **download**
  button.
- The **"Download all"** button ZIPs every file in a message using a
  zero-dependency STORE-method ZIP writer implemented in the page (CRC32
  computed in JS; validated against Python's `zipfile`).
- Like charts, file boxes re-render from the stored message text after a
  reload.

## 3. PDFs — ` ```pdffile ` blocks + the PDF pipeline

The AI is instructed to answer "create a PDF of …" requests by emitting a
`pdffile` block with markdown content. The pipeline:

1. Client shows a **PDF file box** in the chat.
2. The PDF binary comes from `POST /api/pdf` (worker) → backend `/ai/pdf`:
   the markdown is typeset into a **real vector PDF** (embedded fonts,
   headings, lists, tables — not a screenshot).
3. **Persistence:** the blob is stored in **IndexedDB** (`xavierdrive-pdfs`
   database) so it survives reloads and chat switches without a re-render.
4. **Cold path:** if the blob is missing, the client transparently asks
   `POST /api/pdf/file` to re-render the identical PDF server-side, then
   downloads it. The button ALWAYS says **Download** (the old
   Regenerate→Download two-step was removed by owner order).
5. The PDF system prompt is school-aware (correct school name/identity).

## 4. Images

- The AI can generate images (Gemini image path) — delivered as image
  artifacts in the chat.
- Users can attach images; vision-capable providers read them.

## 5. Text-to-speech — `/api/tts`

- `POST /api/tts {text}` returns synthesized speech audio (provider chain;
  Groq TTS when available).
- Used for reading answers aloud.

## 6. Artifact storage & Drive

When a chat session is saved, its artifacts are written under
`Xavier-Drive/CHATS/<session>/artifacts/<type>/…` in the school Drive, so a
conversation's files are available later from any device (and from the
Files ecosystem).
