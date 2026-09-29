package com.stxaviers.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * App-wide identity, rank and WORKSPACE state (v2.0.0).
 *
 * The sign-in flow and the session probe know who the user is; every screen
 * needs the same facts (name, email, rank, selected class, active menu).
 * This class is the ONE place they live: a small static cache backed by a
 * SharedPreferences JSON blob, written after every successful /me and read
 * by every activity — so no screen ever re-probes the network for identity.
 *
 * v2.0.0 — the four MENUS (workspaces):
 *   student(1) < teacher(2) < admin(3) < developer(4).
 * The server sends role + isAdmin + isDeveloper; the RANK is derived here
 * (isDeveloper wins over isAdmin wins over the raw role) — /me reports
 * role:"teacher" for every admin and developer, so the raw field must
 * never be shown on its own.
 * A user may open every menu at or below their level; the ACTIVE one is
 * persisted here. Upload/post affordances exist ONLY inside the teacher
 * menu (owner order — even admins switch to it to upload): screens gate
 * on teacherMode(), not teacherPower().
 */
public final class XDState {

    public static final String WS_STUDENT = "student";
    public static final String WS_TEACHER = "teacher";
    public static final String WS_ADMIN = "admin";
    public static final String WS_DEVELOPER = "developer";

    public String name = "";
    public String email = "";
    public String role = "";       // raw server role: "student" | "teacher"
    public boolean isAdmin;
    public boolean isDeveloper;
    public String klass = "";      // selected class, e.g. "Class 6"

    /** Firebase profile photo (data URL) — in-memory only, fetched by
     *  ProfileSync from /api/user/profile. Never written to prefs (it can
     *  be ~2 MB); sign-out clears it with the rest of the cache. */
    public String photo = "";

    private String workspace = WS_STUDENT;
    private int wsVersion = 1;     // bumped on every switch (cheap re-render)

    private static XDState sCache;

    private static final String PREFS = "xd_state";
    private static final String KEY = "identity";

    private XDState() {}

    /** Live snapshot (never null). */
    public static XDState get(Context c) {
        if (sCache == null) {
            sCache = new XDState();
            sCache.load(c.getApplicationContext());
        }
        return sCache;
    }

    // ── rank (derived — the REAL rank, shown everywhere) ────────────────

    /** 1 student · 2 teacher · 3 admin · 4 developer. */
    public int level() {
        if (isDeveloper) return 4;
        if (isAdmin) return 3;
        if ("teacher".equals(role)) return 2;
        return 1;
    }

    public static int wsLevel(String ws) {
        if (WS_DEVELOPER.equals(ws)) return 4;
        if (WS_ADMIN.equals(ws)) return 3;
        if (WS_TEACHER.equals(ws)) return 2;
        return 1;
    }

    /** The honest rank label: Developer > Admin > Teacher > Student. */
    public String rankLabel() {
        switch (level()) {
            case 4: return "Developer";
            case 3: return "Admin";
            case 2: return "Teacher";
            default: return "Student";
        }
    }

    /** Teacher-tier powers (upload/post/mark attendance/edit timetable). */
    public boolean teacherPower() {
        return "teacher".equals(role) || isAdmin || isDeveloper;
    }

    /**
     * The ONE gate for every upload / post / edit affordance: the user is
     * INSIDE the teacher menu AND holds teacher powers. Admins and
     * developers get the button only after switching to the teacher menu —
     * exactly the owner's instruction.
     */
    public boolean teacherMode() {
        return WS_TEACHER.equals(workspace) && teacherPower();
    }

    public boolean adminPower() {
        return isAdmin || isDeveloper;
    }

    // ── workspaces (the four menus) ─────────────────────────────────────

    public String workspace() {
        return workspace;
    }

    public boolean canUse(String ws) {
        return wsLevel(ws) <= level();
    }

    /** Switch the active menu (clamped to what the rank allows). */
    public void setWorkspace(Context c, String ws) {
        if (!canUse(ws)) ws = WS_STUDENT;
        if (ws.equals(workspace)) return;
        workspace = ws;
        wsVersion++;
        save(c);
    }

    /** Bumped on every switch so screens can cheaply detect changes. */
    public int wsVersion() {
        return wsVersion;
    }

    public static String wsLabel(String ws) {
        if (WS_DEVELOPER.equals(ws)) return "Developer";
        if (WS_ADMIN.equals(ws)) return "Admin";
        if (WS_TEACHER.equals(ws)) return "Teacher";
        return "Student";
    }

    public boolean known() {
        return email != null && email.length() > 3;
    }

    /** First name, capitalized ("amrit raj" -> "Amrit"). */
    public String firstName() {
        String who = name == null ? "" : name.trim();
        if (who.isEmpty() && email != null && email.contains("@")) {
            who = email.substring(0, email.indexOf('@'));
        }
        if (who.isEmpty()) return "Student";
        String first = who.split("\\s+")[0];
        if (first.length() == 0) return "Student";
        return Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }

    // ── persistence ─────────────────────────────────────────────────────

    public void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("email", email);
            o.put("role", role);
            o.put("isAdmin", isAdmin);
            o.put("isDeveloper", isDeveloper);
            o.put("class", klass);
            o.put("ws", workspace);
            prefs(c).edit().putString(KEY, o.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private void load(Context c) {
        try {
            String raw = prefs(c).getString(KEY, null);
            if (raw == null) return;
            JSONObject o = new JSONObject(raw);
            name = o.optString("name", "");
            email = o.optString("email", "");
            role = o.optString("role", "");
            isAdmin = o.optBoolean("isAdmin", false);
            isDeveloper = o.optBoolean("isDeveloper", false);
            klass = o.optString("class", "");
            workspace = o.optString("ws", WS_STUDENT);
            if (!canUse(workspace)) workspace = WS_STUDENT;
        } catch (Throwable ignored) {}
    }

    /** Merge a /me-shaped JSON into the state + persist. */
    public void applyMe(Context c, JSONObject me) {
        if (me == null) return;
        JSONObject user = me.optJSONObject("user");
        if (user != null) {
            String n = user.optString("name", "");
            String e = user.optString("email", "");
            if (!n.isEmpty()) name = n;
            if (!e.isEmpty()) email = e;
        }
        String r = me.optString("role", "");
        if (!r.isEmpty()) role = r;
        isAdmin = me.optBoolean("isAdmin", false);
        isDeveloper = me.optBoolean("isDeveloper", false);
        if (!canUse(workspace)) workspace = WS_STUDENT;
        save(c);
    }

    public void setClass(Context c, String klass) {
        this.klass = klass == null ? "" : klass;
        save(c);
    }

    /** Wipe everything (sign-out). */
    public static void clear(Context c) {
        sCache = null;
        prefs(c).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── school constants (mirror the website) ───────────────────────────

    public static final String[] CLASSES = {
            "Class 1", "Class 2", "Class 3", "Class 4", "Class 5", "Class 6",
            "Class 7", "Class 8", "Class 9", "Class 10", "Class 11", "Class 12"
    };

    public static final String[] SECTIONS = {"A", "B", "C", "D", "E", "F"};

    public static final String[] SUBJECTS = {
            "Mathematics", "Science", "Physics", "Chemistry", "Biology",
            "English", "Hindi", "Social Studies", "History", "Geography",
            "Computer Science", "Economics", "Accountancy", "GK", "Other"
    };

    public static final String[] DAYS = {
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    };

    public static final int[] PERIODS = {1, 2, 3, 4, 5, 6, 7, 8};
}
