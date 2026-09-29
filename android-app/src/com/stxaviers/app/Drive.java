package com.stxaviers.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Google Drive operations through the Worker proxy (v1.1.0).
 *
 * Mirrors the website's data layer exactly (website-docs/06..09): the same
 * root folder chain (Xavier-Drive -> FILES / LOGBOOK / ANNOUNCEMENTS /
 * TIMETABLE), the same description-metadata scheme, the same upload
 * multipart format. All methods are BLOCKING — run on a background thread.
 */
public final class Drive {

    public static final String ROOT_NAME = "Xavier-Drive";

    private static String rootId;                       // process cache
    private static final Map<String, String> FOLDERS
            = new HashMap<>();                          // "parent::name" -> id

    public static final class DriveException extends Exception {
        public DriveException(String m) { super(m); }
    }

    private Drive() {}

    // ── folder chain ────────────────────────────────────────────────────

    /** Root folder id ("Xavier-Drive"), creating it if needed. */
    public static String ensureRoot() throws DriveException {
        if (rootId != null) return rootId;
        String q = "mimeType='application/vnd.google-apps.folder' and name='"
                + ROOT_NAME + "' and trashed=false";
        ApiClient.Resp r = ApiClient.request("GET",
                "/drive/files?q=" + ApiClient.enc(q) + "&fields=files(id)");
        if (r.ok && r.json != null) {
            JSONArray files = r.json.optJSONArray("files");
            if (files != null && files.length() > 0) {
                rootId = files.optJSONObject(0).optString("id");
                if (!rootId.isEmpty()) return rootId;
            }
            // not found -> create
            ApiClient.Resp cr = ApiClient.requestJson("POST", "/drive/mkdir",
                    ApiClient.obj("name", ROOT_NAME));
            if (cr.ok && cr.json != null) {
                rootId = cr.json.optString("id", "");
                if (!rootId.isEmpty()) return rootId;
            }
            throw new DriveException("create root failed: " + cr.error());
        }
        throw new DriveException(r.error().isEmpty()
                ? ("HTTP " + r.code) : r.error());
    }

    /** Child folder id under a parent, creating it if needed (cached). */
    public static String ensureFolder(String name, String parentId)
            throws DriveException {
        String key = parentId + "::" + name;
        String cached = FOLDERS.get(key);
        if (cached != null) return cached;
        String q = "mimeType='application/vnd.google-apps.folder' and name='"
                + name + "' and '" + parentId + "' in parents and trashed=false";
        ApiClient.Resp r = ApiClient.request("GET",
                "/drive/files?q=" + ApiClient.enc(q) + "&fields=files(id)");
        if (r.ok && r.json != null) {
            JSONArray files = r.json.optJSONArray("files");
            if (files != null && files.length() > 0) {
                String id = files.optJSONObject(0).optString("id", "");
                if (!id.isEmpty()) {
                    FOLDERS.put(key, id);
                    return id;
                }
            }
            ApiClient.Resp cr = ApiClient.requestJson("POST", "/drive/mkdir",
                    ApiClient.obj("name", name, "parents",
                            new JSONArray().put(parentId)));
            if (cr.ok && cr.json != null) {
                String id = cr.json.optString("id", "");
                if (!id.isEmpty()) {
                    FOLDERS.put(key, id);
                    return id;
                }
            }
            throw new DriveException("create folder failed: " + cr.error());
        }
        throw new DriveException(r.error().isEmpty()
                ? ("HTTP " + r.code) : r.error());
    }

    /** FILES / LOGBOOK / ANNOUNCEMENTS / TIMETABLE roots. */
    public static String sectionRoot(String name) throws DriveException {
        return ensureFolder(name, ensureRoot());
    }

    // ── listing ─────────────────────────────────────────────────────────

    /**
     * List files matching a Drive query. Returns the "files" array
     * (id, name, mimeType, size, modifiedTime, description as requested).
     */
    public static JSONArray list(String query, String fields,
                                 String orderBy, int pageSize)
            throws DriveException {
        StringBuilder p = new StringBuilder("/drive/files?q=")
                .append(ApiClient.enc(query));
        if (fields != null) p.append("&fields=").append(ApiClient.enc(fields));
        p.append("&orderBy=").append(ApiClient.enc(
                orderBy == null ? "modifiedTime desc" : orderBy));
        p.append("&pageSize=").append(pageSize <= 0 ? 100 : pageSize);
        ApiClient.Resp r = ApiClient.request("GET", p.toString());
        if (r.ok && r.json != null) {
            JSONArray files = r.json.optJSONArray("files");
            return files == null ? new JSONArray() : files;
        }
        throw new DriveException(r.error().isEmpty()
                ? ("HTTP " + r.code) : r.error());
    }

    /** Parse a file's description JSON (the {cls,sub,chp} metadata). */
    public static JSONObject meta(org.json.JSONObject file) {
        String d = file == null ? "" : file.optString("description", "");
        if (d.isEmpty()) return new JSONObject();
        try { return new JSONObject(d); } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** First file id matching a query, or null. */
    public static String findId(String query) throws DriveException {
        JSONArray a = list(query, "files(id)", null, 1);
        if (a.length() > 0) {
            String id = a.optJSONObject(0).optString("id", "");
            return id.isEmpty() ? null : id;
        }
        return null;
    }

    // ── upload / update / delete ────────────────────────────────────────

    /**
     * Multipart upload — the WEBSITE's exact format (multipart/form-data
     * with fields "metadata" + "file"). v1.1.2 sent multipart/related,
     * which the Worker's request.formData() parser cannot read → HTTP 500
     * on every app upload (logbook photos, chat.json saves, new timetable
     * files). The worker now tolerates both, but the app speaks the same
     * dialect as the site from here on.
     */
    public static JSONObject upload(String name, String parentId,
                                    String descriptionJson, byte[] bytes,
                                    String mimeType) throws DriveException {
        return upload(name, parentId, descriptionJson, bytes, mimeType, null);
    }

    /** v1.1.4: same upload, reporting percent (0-100) as bytes stream out. */
    public static JSONObject upload(String name, String parentId,
                                    String descriptionJson, byte[] bytes,
                                    String mimeType,
                                    java.util.function.IntConsumer progress)
            throws DriveException {
        String boundary = "----XavierDriveApp" + System.currentTimeMillis();
        String mime = mimeType == null ? "application/octet-stream" : mimeType;
        JSONObject meta = new JSONObject();
        try {
            meta.put("name", name);
            // description stays a STRING containing JSON — Google stores it
            // as text and every reader (app + website) JSON.parse()s it.
            if (descriptionJson != null) meta.put("description",
                    descriptionJson);
            if (parentId != null) meta.put("parents",
                    new JSONArray().put(parentId));
        } catch (Throwable t) {
            throw new DriveException("metadata: " + t);
        }

        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"metadata\""
                + "\r\nContent-Type: application/json\r\n\r\n"
                + meta.toString() + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + safeName(name) + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--";

        byte[] headB = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailB = tail.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(
                headB.length + bytes.length + tailB.length);
        try {
            bos.write(headB);
            bos.write(bytes);
            bos.write(tailB);
        } catch (Throwable t) {
            throw new DriveException("build body: " + t);
        }

        ApiClient.Resp r = ApiClient.requestBytes("POST", "/drive/upload",
                bos.toByteArray(),
                "multipart/form-data; boundary=" + boundary, progress);
        if (r.ok && r.json != null) return r.json;
        throw new DriveException(r.error().isEmpty()
                ? ("HTTP " + r.code) : r.error());
    }

    /** Overwrite an existing file's content (timetable save). */
    public static void updateContent(String fileId, byte[] bytes)
            throws DriveException {
        ApiClient.Resp r = ApiClient.requestBytes("PATCH",
                "/drive/files/" + fileId, bytes, "application/json");
        if (!r.ok) {
            throw new DriveException(r.error().isEmpty()
                    ? ("HTTP " + r.code) : r.error());
        }
    }

    /** Trash a file. */
    public static void delete(String id) throws DriveException {
        ApiClient.Resp r = ApiClient.request("DELETE",
                "/drive/delete?id=" + ApiClient.enc(id));
        if (!r.ok) {
            throw new DriveException(r.error().isEmpty()
                    ? ("HTTP " + r.code) : r.error());
        }
    }

    /** Read a file's content as UTF-8 text (JSON configs, timetables). */
    public static String readText(String id) throws DriveException {
        try {
            InputStream in = ApiClient.openStream(
                    "/drive/media?id=" + ApiClient.enc(id), null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new DriveException("read: " + t);
        }
    }

    // ── small helpers ───────────────────────────────────────────────────

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':  b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n");  break;
                    case '\r': b.append("\\r");  break;
                    case '\t': b.append("\\t");  break;
                    default:
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String safeName(String s) {
        if (s == null) return "upload";
        return s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
    }
}
