package com.stxaviers.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat history SYNC between the app and the website (v1.1.2).
 *
 * The website stores every conversation in Google Drive through the same
 * Worker the app speaks to (index.html → getUserChatFolder/saveSession):
 *
 *   Xavier-Drive / USERS / CHATS / <email> / <session-name> / chat.json
 *
 * where chat.json is exactly {id, name, history, created} and history is
 * [{role:"user"|"ai", text, time}]. This class reads and writes that SAME
 * layout, so a chat started on the website appears in the app's drawer
 * and vice-versa — one store, two clients. Deletions remove the Drive
 * folder, exactly like the website's deleteSession().
 *
 * All methods are BLOCKING (background thread), except load()/save()/
 * delete() wrappers that post results back to the UI thread.
 */
public final class ChatSync {

    /** One conversation, website-shaped. */
    public static final class Session {
        public String id = "";
        public String name = "";
        public long created;
        public JSONArray history = new JSONArray();
        public String folderId;    // Drive folder id (chat container)
        public String driveId;     // chat.json file id (latest upload)
        public String folderName;  // Drive folder NAME (uid once migrated)
        public boolean dirty;      // pending save (created locally)
        /** v1.1.5: names of the files in this chat's artifacts folder —
         *  the AI's workspace (transient, rebuilt from Drive). */
        public final java.util.List<String> ws = new ArrayList<>();
    }

    public interface Callback {
        /** sessions: newest-first; error null on success. */
        void onResult(List<Session> sessions, String error);
    }

    private static final Map<String, String> FOLDERS = new HashMap<>();

    private ChatSync() {}

    // ── folder chain ────────────────────────────────────────────────────

    private static String chatsRoot(Context c) throws Exception {
        XDState st = XDState.get(c);
        String email = st.email == null ? "" : st.email.trim().toLowerCase();
        if (email.isEmpty()) throw new Exception("no account");
        String users = Drive.ensureFolder("USERS", Drive.ensureRoot());
        String chats = Drive.ensureFolder("CHATS", users);
        return Drive.ensureFolder(email, chats);
    }

    // ── load ────────────────────────────────────────────────────────────

    /** Load every conversation from Drive (newest first), on a worker
     *  thread; the callback lands on the UI thread. */
    public static void load(final Context c, final Callback cb) {
        new Thread(() -> {
            List<Session> out = loadBlocking(c);
            String error = loadError;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> { if (cb != null) cb.onResult(out, error); });
        }, "xd-chatsync-load").start();
    }

    private static String loadError;

    /** Blocking Drive load — same result as load(), for callers already
     *  on a background thread (export-all, settings delete-all). */
    public static List<Session> loadBlocking(Context c) {
        List<Session> out = new ArrayList<>();
        loadError = null;
        try {
            String root = chatsRoot(c);
            JSONArray folders = Drive.list(
                    "'" + root + "' in parents and mimeType='application/"
                            + "vnd.google-apps.folder' and trashed=false",
                    "files(id,name,modifiedTime)",
                    "modifiedTime desc", 100);
            for (int i = 0; i < folders.length(); i++) {
                JSONObject f = folders.optJSONObject(i);
                if (f == null) continue;
                Session s = readFolder(f.optString("id", ""),
                        f.optString("name", ""));
                if (s != null) out.add(s);
            }
            // v1.1.5 DEDUP (owner bug: "2 copies of 1 chat showing at
            // once"): one chat = ONE row no matter how many folders the
            // Drive store holds for it (legacy title-named + uid-named
            // twins from app/website save races). Keep the longest
            // history; ties prefer the uid-named folder. The worker's
            // /api/chats/dedup sweep heals the store itself; this keeps
            // the drawer clean on every single load regardless.
            java.util.LinkedHashMap<String, Session> byId =
                    new java.util.LinkedHashMap<>();
            for (Session s : out) {
                Session prev = byId.get(s.id);
                if (prev == null || betterCopy(s, prev)) byId.put(s.id, s);
            }
            out = new ArrayList<>(byId.values());
        } catch (Throwable t) {
            loadError = t.getMessage() == null ? String.valueOf(t)
                    : t.getMessage();
        }
        return out;
    }

    /** a beats b as the keeper copy of the same chat id. */
    private static boolean betterCopy(Session a, Session b) {
        if (a.history.length() != b.history.length()) {
            return a.history.length() > b.history.length();
        }
        boolean an = a.id.equals(a.folderName), bn = b.id.equals(b.folderName);
        if (an != bn) return an;
        return a.created >= b.created;
    }

    /** Read one session folder's chat.json (null when unreadable). */
    private static Session readFolder(String folderId, String folderName) {
        try {
            String q = "'" + folderId + "' in parents and "
                    + "mimeType='application/json' and trashed=false";
            JSONArray files = Drive.list(q, "files(id,name,modifiedTime)",
                    "modifiedTime desc", 2);
            String fileId = null;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f != null && "chat.json".equals(f.optString("name", ""))) {
                    fileId = f.optString("id", "");
                    break;
                }
            }
            if (fileId == null && files.length() > 0) {
                JSONObject alt = files.optJSONObject(0);
                fileId = alt == null ? null : alt.optString("id", "");
            }
            if (fileId == null || fileId.isEmpty()) return null;

            String raw = Drive.readText(fileId);
            JSONObject o = new JSONObject(raw);
            Session s = new Session();
            s.id = o.optString("id", "");
            s.name = o.optString("name", "");
            s.created = o.optLong("created", 0);
            s.folderId = folderId;
            s.folderName = folderName;
            s.driveId = fileId;
            JSONArray h = o.optJSONArray("history");
            if (h == null) h = new JSONArray();
            // website messages may carry extra keys — keep {role,text,time}
            // plus the rich keys (inline images, file cards' payloads)
            for (int i = 0; i < h.length(); i++) {
                JSONObject m = h.optJSONObject(i);
                if (m == null) continue;
                JSONObject clean = new JSONObject();
                String role = m.optString("role", "user");
                clean.put("role", "user".equals(role) ? "user" : "ai");
                clean.put("text", m.optString("text", ""));
                clean.put("time", m.optString("time", ""));
                if (!m.optString("img", "").isEmpty()) {
                    clean.put("img", m.optString("img"));
                }
                JSONArray extra = m.optJSONArray("files");
                if (extra != null) clean.put("files", extra);
                s.history.put(clean);
            }
            if (s.id.isEmpty()) return null;
            if (s.history.length() == 0) return null;  // skip empty shells
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── save ────────────────────────────────────────────────────────────

    /** Persist one session to Drive (upsert). Blocking; callback on UI. */
    public static void save(final Context c, final Session s,
                            final Runnable done) {
        new Thread(() -> {
            try {
                saveBlocking(c, s);
            } catch (Throwable ignored) {}
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> { if (done != null) done.run(); });
        }, "xd-chatsync-save").start();
    }

    /** Same as save() but throws — for callers already on a thread. */
    public static void saveBlocking(Context c, Session s) throws Exception {
        if (s == null || s.id.isEmpty()) return;
        if (s.history.length() == 0) return;   // never save empty chats
        String root = chatsRoot(c);

        if (s.folderId == null || s.folderId.isEmpty()) {
            // v1.1.5 CHAT STORE LAW (owner spec 2026-09-29): the folder is
            //   chats/<gmail>/<chatUid>/chat.json
            // The folder NAME is the chat UID — NEVER the title. Drive
            // allows same-name folders, and title-named folders were the
            // source of one chat doubling into two. Lookup order:
            //   1) uid-named folder
            //   2) legacy title-named folder → ADOPT + RENAME to the uid
            //   3) create the uid-named folder
            String key = root + "::" + s.id;
            String cached = FOLDERS.get(key);
            if (cached != null) {
                s.folderId = cached;
            } else {
                String found = findByQuery(
                        "mimeType='application/vnd.google-apps.folder' "
                                + "and name='" + s.id.replace("'", "\\'")
                                + "' and '" + root + "' in parents "
                                + "and trashed=false");
                if (found == null || found.isEmpty()) {
                    // legacy: the old layout titled folders by the chat
                    // name — adopt that folder and migrate it onto the law
                    String legacy = legacyFolderName(s);
                    if (legacy != null) {
                        found = findByQuery(
                                "mimeType='application/vnd.google-apps.folder' "
                                        + "and name='" + legacy.replace("'", "\\'")
                                        + "' and '" + root + "' in parents "
                                        + "and trashed=false");
                        if (found != null && !found.isEmpty()) {
                            try {
                                ApiClient.requestJson("POST", "/drive/rename",
                                        ApiClient.obj("id", found,
                                                "name", s.id));
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                if (found == null || found.isEmpty()) {
                    JSONObject mk = ApiClient.requestJson("POST",
                            "/drive/mkdir", ApiClient.obj("name", s.id,
                                    "parents", new JSONArray().put(root))).json;
                    found = mk == null ? "" : mk.optString("id", "");
                }
                if (found == null || found.isEmpty()) {
                    throw new Exception("chat folder create failed");
                }
                FOLDERS.put(key, found);
                s.folderId = found;
            }
        }
        s.folderName = s.id;

        // chat.json body — the website's exact shape
        JSONObject body = new JSONObject();
        body.put("id", s.id);
        body.put("name", s.name == null ? "" : s.name);
        body.put("created", s.created <= 0
                ? System.currentTimeMillis() : s.created);
        body.put("history", s.history);

        // the website deletes the previous chat.json then uploads the new
        // one (no PATCH) — mirror it so both clients see identical state
        if (s.driveId != null && !s.driveId.isEmpty()) {
            try { Drive.delete(s.driveId); } catch (Throwable ignored) {}
        }
        JSONObject up = Drive.upload("chat.json", s.folderId, null,
                body.toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json");
        s.driveId = up.optString("id", "");
        s.dirty = false;
    }

    // ── delete ──────────────────────────────────────────────────────────

    /** Remove a conversation everywhere (app + website). Callback on UI. */
    public static void delete(final Context c, final Session s,
                              final Runnable done) {
        new Thread(() -> {
            try {
                if (s.driveId != null && !s.driveId.isEmpty()) {
                    try { Drive.delete(s.driveId); } catch (Throwable ignored) {}
                }
                if (s.folderId != null && !s.folderId.isEmpty()) {
                    try { Drive.delete(s.folderId); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> { if (done != null) done.run(); });
        }, "xd-chatsync-del").start();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** First folder id matching a query, or null. */
    private static String findByQuery(String q) {
        try {
            JSONArray a = Drive.list(q, "files(id)", null, 1);
            if (a.length() > 0) {
                String id = a.optJSONObject(0).optString("id", "");
                return id.isEmpty() ? null : id;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** The v1.2.0-and-older folder name for this session (title-based). */
    private static String legacyFolderName(Session s) {
        String base = (s.name == null || s.name.trim().isEmpty())
                ? ("chat_" + s.id) : s.name.trim();
        String n = base.replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
        if (n.length() > 40) n = n.substring(0, 40);
        return n.isEmpty() ? "untitled" : n;
    }

    // ── artifacts (the AI's workspace, v1.1.5) ──────────────────────

    /** The chat's Drive folder id, saving the chat first if it has no
     *  folder yet. Null when unavailable (offline, new empty chat). */
    public static String ensureFolderOf(Context c, Session s) {
        try {
            if (s.folderId == null || s.folderId.isEmpty()) {
                saveBlocking(c, s);
            }
        } catch (Throwable ignored) {}
        return (s.folderId == null || s.folderId.isEmpty()) ? null : s.folderId;
    }

    /** Every file name in the chat's artifacts folder — the AI's
     *  workspace. Covers BOTH layouts: the app's flat artifacts/* and
     *  the website's artifacts/<type>/*. Blocking; never throws. */
    public static List<String> artifactsOf(Context c, Session s) {
        List<String> names = new ArrayList<>();
        try {
            String folder = ensureFolderOf(c, s);
            if (folder == null) return names;
            String arts = Drive.ensureFolder("artifacts", folder);
            JSONArray lvl1 = Drive.list(
                    "'" + arts + "' in parents and trashed=false",
                    "files(id,name,mimeType)", null, 100);
            for (int i = 0; i < lvl1.length(); i++) {
                JSONObject f = lvl1.optJSONObject(i);
                if (f == null) continue;
                String n = f.optString("name", "");
                if (n.isEmpty() || "chat.json".equals(n)) continue;
                if ("application/vnd.google-apps.folder".equals(
                        f.optString("mimeType", ""))) {
                    JSONArray lvl2 = Drive.list(
                            "'" + f.optString("id", "")
                                    + "' in parents and trashed=false",
                            "files(name)", null, 100);
                    for (int j = 0; j < lvl2.length(); j++) {
                        JSONObject g = lvl2.optJSONObject(j);
                        if (g != null && !g.optString("name", "").isEmpty()) {
                            names.add(g.optString("name", ""));
                        }
                    }
                } else {
                    names.add(n);
                }
            }
        } catch (Throwable ignored) {}
        return names;
    }

    /** Store one file in the chat's artifacts folder — every upload and
     *  every AI-created file lands here (owner spec). Blocking; safe. */
    public static void saveArtifact(Context c, Session s, String name,
                                    byte[] bytes, String mime) {
        try {
            String folder = ensureFolderOf(c, s);
            if (folder == null || bytes == null || bytes.length == 0) return;
            String arts = Drive.ensureFolder("artifacts", folder);
            Drive.upload(name, arts, null, bytes,
                    mime == null ? "application/octet-stream" : mime);
            if (name != null && !name.isEmpty() && !s.ws.contains(name)) {
                s.ws.add(name);
            }
        } catch (Throwable ignored) {}
    }

    /** Fire the worker's dedup sweep (heals doubled chat folders).
     *  Fire-and-forget; never blocks the UI. */
    public static void dedupSweep(Context c) {
        try {
            new Thread(() -> {
                try {
                    ApiClient.requestJson("POST", "/api/chats/dedup",
                            new JSONObject());
                } catch (Throwable ignored) {}
            }, "xd-chat-dedup").start();
        } catch (Throwable ignored) {}
    }

    /** Title from the first user message (website behaviour). */
    public static String titleOf(JSONArray hist) {
        try {
            for (int i = 0; i < hist.length(); i++) {
                JSONObject m = hist.optJSONObject(i);
                if (m != null && "user".equals(m.optString("role", ""))) {
                    String t = m.optString("text", "")
                            .replace('\n', ' ').trim();
                    if (!t.isEmpty()) {
                        return t.length() > 42 ? t.substring(0, 42) + "…" : t;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** The website's session id shape ("s" + timestamp). */
    public static String newId() {
        return "s" + System.currentTimeMillis();
    }
}
