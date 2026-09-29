# 04 — XavierDrive AI (the chat brain)

The AI assistant is the platform's flagship feature. This document explains
the complete pipeline: what happens from the moment a user hits Send to the
final rendered answer — including live web research, provider routing,
quotas, streaming, titles, moderation, and image understanding.

---

## 1. The request

`POST /api/chat` (single JSON response) or `POST /api/chat/stream`
(Server-Sent Events). Body:

```json
{
  "message": "Explain the water cycle for class 6",
  "role": "student",
  "email": "user@school",
  "class": "Class 6",
  "history": [ {"role":"user","text":"…"}, {"role":"ai","text":"…"} ],
  "images": [ {"mimeType":"image/jpeg","base64":"…"} ],
  "forceModel": null
}
```

- `history` is the last 30 messages (client trims).
- `images` up to 4, ≤1 MB base64 each (server-enforced).
- The **server re-verifies the claimed role** via `verifyRole` — a student
  cannot claim to be a teacher by editing the request.

---

## 2. The agent pipeline (backend `/ai/chat[/stream]`)

### 2.1 Planner — "does this question need research?"
A fast, small LLM pass (~150 max tokens, 14 s timeout) returns
`{search: true/false, queries: [...]}`. 
- Small talk ("hi", "thanks", emoji) → no search, answer directly.
- Factual/current/school-relevant questions → search runs with the
  **AI's own chosen queries** (not the user's words).
- If the planner itself fails, a heuristic (`needsResearch`) takes over.

### 2.2 Research (the AI's "terminal")
- `webSearch()` — DuckDuckGo (lite POST → HTML GET fallback) → **Bing**
  fallback chain; returns ranked results.
- `fetchPageText()` — downloads and strips up to N chars of readable text
  per page.
- `researchPack()` — multi-query, deduplicated, capped at 8 page reads;
  produces a compact `[LIVE WEB RESEARCH]` block: top results + extracts.
- Every search/read is logged to the agent trail (`/ai/log?agent=site-chat`).

### 2.3 Answer composition
- The system prompt is **school-aware**: St. Xavier's Jr./Sr. School,
  Muzaffarpur; the user's role, class and email are injected as context.
- The LLM call is made **through the Worker's key proxy**
  (`POST /internal/ai/call` with `X-Backend-Key`): the backend sends the
  messages, the Worker attaches the provider API key and performs the
  actual HTTP call. **Provider keys never leave Cloudflare.**
- The agent system prompt caps the flow at 5–6 steps and instructs the
  model to use its artifact abilities (see 05) and to never reveal the
  research/terminal text.

### 2.4 Moderation & sanitize
- The final answer passes a moderation screen (same provider chain).
- `sanitizeAIResponse()` strips any leaked `[LIVE WEB RESEARCH]` or
  `[User Context]` blocks, and `[CANCEL]`-flagged answers are replaced with
  a safe refusal.

---

## 3. Provider routing (the "key vault" layer)

Priority chain (all keys live ONLY in Worker secrets):

1. **Groq** — 5 keys, round-robin (gpt-oss-120b / gpt-oss-20b / qwen class
   models). ~30 RPM / 1k RPD per key.
2. **OpenRouter** — 5 keys, model chains, 50 free-model requests/day per key.
3. **Cloudflare Workers AI** — per-account tokens + account IDs.
4. **Gemini** (backup) — 5 keys, `gemini-3.8-flash` / `gemini-flash-latest`;
   needs the `x-goog-api-key` header. Free tier is **geo-blocked** from some
   Cloudflare edge locations → the Worker detects the "location not
   supported" error and relays the call through the India-based backend
   (`/ai/relay/gemini`, key travelling AES-GCM-encrypted).

Each key has individual cooldowns on 429/5xx; each provider has a 10-minute
circuit breaker. When everything is cooling down, one retry-anyway pass
still runs. `/admin/ai-status?deep=1` (backend key auth) live-probes every
key with a real generation call and reports per-key health.

**Images** (vision) route Gemini-first; if the chosen provider can't see
images the text is flattened as a last resort.

---

## 4. Streaming (SSE) — the z.ai-style steps

`/api/chat/stream` emits Server-Sent Events:

```
data: {"t":"step","icon":"🔍","label":"Searching the web","detail":"water cycle class 6"}
data: {"t":"step","icon":"📖","label":"Reading source","detail":"learncbse.net"}
data: {"t":"step","icon":"✍️","label":"Composing answer","detail":"…"}
data: {"t":"answer","text":"The water cycle is …","provider":"groq/gpt-oss-120b","searched":true,"sources":[…]}
```

- The site renders live "agent step" rows (icon + label + one-line detail,
   active row highlighted, previous rows ticked) that collapse into chips
   after the answer lands, and persist on the message after reload.
- If the stream dies before the first step, the client silently falls back
   to the JSON endpoint.

---

## 5. Chat sessions (Chats tab)

- Every conversation is a **session** with an AI-chosen title.
- **AI-chosen titles:** after the first real reply, `POST /api/chat/title`
   asks the LLM for a 2–6 word Title Case name (a quick local placeholder
   shows first, the LLM name replaces it).
- Sessions are **saved into Google Drive** (`Xavier-Drive/CHATS/<session>/`
   with an `artifacts/` subfolder for generated files), so they survive
   devices. A dedupe-by-id guard fixed the historical "split-brain chat"
   (two Drive folders for one session).
- **A fresh "Untitled" chat starts on every page load** (owner directive) —
   old conversations remain listed in the Chats tab.

---

## 6. Quotas

- **Rate limit:** 20 messages/minute per user (worker KV counter).
- **Advanced-model quota:** Gemini usage counts against a per-user daily
  quota (`GET /api/quota?email=&role=`). On exhaustion the user is silently
  switched to standard models until midnight and shown a small banner.

---

## 7. Attachments (what the AI can see)

- Users can attach files to a message (and pick files straight from their
  XavierDrive Drive). Their **text content is sent with the message** (a
  past bug sent them nowhere — fixed).
- Images are sent as base64 `images[]` to vision-capable providers.

---

## 8. Failure modes (by design)

| Failure | User experience |
|---|---|
| Backend down | Worker answers directly itself (no research trail) |
| All providers cooling | Steps render, then a graceful error message |
| Research fails | Answer still generated, marked `searched:false` |
| Stream breaks early | Automatic fallback to non-streaming call |
| Rate limited | "Too many messages — wait Ns" |
| Moderation flag | Refusal message, no content shown |
