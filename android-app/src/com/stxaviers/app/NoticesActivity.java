package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * NOTICES (v1.1.2) — the announcement feed, sectioned.
 *
 * Loads the JSON announcement files from the Drive ANNOUNCEMENTS root and
 * its class subfolders, then groups them into SECTIONS — School-wide
 * first, then each class in order (the separation system the owner
 * asked for). Cards expand to the full message on tap; a thin separator
 * runs between the cards of a section. Students see whole-school +
 * their own class (their Profile class is the default); teachers/admins
 * in the teacher menu also get post + delete.
 */
public class NoticesActivity extends XdActivity {

    private final Handler h = new Handler(Looper.getMainLooper());

    private final List<JSONObject> all = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    /** Sectioned rows: a header marker (null) or a notice (object). */
    private final List<Object> rows = new ArrayList<>();
    private boolean loading;

    private NoticesAdapter adapter;
    private ListView list;
    private View progress, empty;
    private TextView error;
    private Spinner classSp;
    private XDState st;
    private long lastSeenTs;

    /** Shared prefs name Home probes for the "NEW notices" badge. */
    static final String PREFS = "xd_notices";
    static final String KEY_SEEN = "last_seen_ts";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notices);
        st = XDState.get(this);
        lastSeenTs = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_SEEN, 0L);

        list = findViewById(R.id.notices_list);
        progress = findViewById(R.id.notices_progress);
        empty = findViewById(R.id.notices_empty);
        error = (TextView) findViewById(R.id.notices_error);
        findViewById(R.id.notices_back).setOnClickListener(v -> finish());

        // the class filter (owner order v1.2.0): All classes + 1–12
        classSp = findViewById(R.id.notices_class);
        List<String> classes = new ArrayList<>();
        classes.add(getString(R.string.all_classes));
        for (String c : XDState.CLASSES) classes.add(c);
        ArrayAdapter<String> ca = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, classes);
        ca.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        classSp.setAdapter(ca);
        if (st.teacherPower() && st.klass != null && !st.klass.isEmpty()) {
            int ix = classes.indexOf(st.klass);
            if (ix > 0) classSp.setSelection(ix);
        }
        classSp.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v,
                                                 int pos, long id) {
                if (!all.isEmpty()) applyFilter();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        adapter = new NoticesAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object row = rows.get(pos);
            if (row instanceof JSONObject) toggleExpand(pos);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            // posting + deleting live ONLY in the teacher menu
            if (st.teacherMode() && rows.get(pos) instanceof JSONObject) {
                askDelete((JSONObject) rows.get(pos));
                return true;
            }
            return false;
        });

        if (st.teacherMode()) {
            TextView post = findViewById(R.id.notices_new);
            post.setVisibility(View.VISIBLE);
            post.setOnClickListener(v -> askPost());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ── data ────────────────────────────────────────────────────────────

    private void load() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        new Thread(() -> {
            String err = null;
            List<JSONObject> found = new ArrayList<>();
            try {
                String root = Drive.sectionRoot("ANNOUNCEMENTS");
                // JSON files directly under the root (whole-school)
                collect(found, root, true);
                // class subfolders
                JSONArray folders = Drive.list(
                        "'" + root + "' in parents and mimeType='application/"
                                + "vnd.google-apps.folder' and trashed=false",
                        "files(id,name)", null, 50);
                for (int i = 0; i < folders.length(); i++) {
                    JSONObject f = folders.optJSONObject(i);
                    if (f != null) collect(found, f.optString("id"), false);
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final List<JSONObject> ff = found;
            final String e = err;
            h.post(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                if (e != null) {
                    if (all.isEmpty()) {
                        error.setText(getString(R.string.error_load)
                                + ": " + e);
                        error.setVisibility(View.VISIBLE);
                    } else {
                        Ui.toast(NoticesActivity.this,
                                getString(R.string.error_load) + ": " + e);
                    }
                    return;
                }
                all.clear();
                all.addAll(ff);
                Collections.sort(all, (a, b) -> Long.compare(
                        b.optLong("ts", 0), a.optLong("ts", 0)));
                applyFilter();
                markSeen();
            });
        }, "xd-notices-load").start();
    }

    /** Gather announcement JSON files under one folder. */
    private void collect(List<JSONObject> out, String folderId,
                         boolean schoolWide) {
        try {
            JSONArray files = Drive.list(
                    "'" + folderId + "' in parents and "
                            + "mimeType='application/json' and trashed=false",
                    "files(id,name,description,createdTime)",
                    "createdTime desc", 200);
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                JSONObject meta = Drive.meta(f);
                if (!meta.has("title")) continue;
                JSONObject n = new JSONObject();
                try {
                    n.put("driveId", f.optString("id"));
                    for (java.util.Iterator<String> it = meta.keys();
                            it.hasNext();) {
                        String k = it.next();
                        n.put(k, meta.opt(k));
                    }
                    n.put("schoolWide", schoolWide);
                } catch (Throwable ignored) {}
                out.add(n);
            }
        } catch (Throwable ignored) {}
    }

    private void applyFilter() {
        // the chosen class filter narrows the feed on top of the
        // visibility rules (students always see their own class);
        // school-wide notices stay — they are for everyone
        String classFilter = classSp == null
                || classSp.getSelectedItemPosition() <= 0 ? ""
                : XDState.CLASSES[classSp.getSelectedItemPosition() - 1];
        shown.clear();
        for (JSONObject n : all) {
            if (!st.teacherPower()) {
                String target = n.optString("target", "all");
                if ("class".equals(target)) {
                    String cls = n.optString("cls", "");
                    if (st.klass == null || st.klass.isEmpty()
                            || !cls.equals(st.klass)) continue;
                }
            }
            if (!classFilter.isEmpty()
                    && "class".equals(n.optString("target", "all"))
                    && !n.optString("cls", "").equals(classFilter)) {
                continue;
            }
            shown.add(n);
        }
        buildRows();
        adapter.notifyDataSetChanged();
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * The separation system: School-wide first, then every class that
     * has notices — in Class 1..12 order, newest within each section.
     */
    private void buildRows() {
        rows.clear();
        if (shown.isEmpty()) return;

        List<JSONObject> school = new ArrayList<>();
        java.util.LinkedHashMap<String, List<JSONObject>> byClass =
                new java.util.LinkedHashMap<>();
        for (JSONObject n : shown) {
            if ("class".equals(n.optString("target", "all"))) {
                String c = n.optString("cls", "");
                if (!byClass.containsKey(c)) byClass.put(c, new ArrayList<>());
                byClass.get(c).add(n);
            } else {
                school.add(n);
            }
        }

        if (!school.isEmpty()) {
            rows.add(getString(R.string.notice_school_wide));
            rows.addAll(school);
        }

        List<String> classes = new ArrayList<>(byClass.keySet());
        Collections.sort(classes, (a, b) -> {
            int ia = classIndex(a), ib = classIndex(b);
            if (ia != ib) return Integer.compare(ia, ib);
            return a.compareTo(b);
        });
        for (String c : classes) {
            rows.add(c.isEmpty() ? getString(R.string.all_classes) : c);
            rows.addAll(byClass.get(c));
        }
    }

    private static int classIndex(String cls) {
        if (cls == null) return 99;
        String digits = cls.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 99;
        try {
            return Integer.parseInt(digits);
        } catch (Throwable t) {
            return 99;
        }
    }

    /** Expand / collapse one card in place. */
    private void toggleExpand(int pos) {
        View v = pos >= 0 && pos < list.getChildCount()
                ? list.getChildAt(pos - list.getFirstVisiblePosition()) : null;
        if (v == null) {
            adapter.notifyDataSetChanged();
            return;
        }
        TextView body = v.findViewById(R.id.notice_body);
        if (body == null) return;
        boolean expanded = body.getMaxLines() == Integer.MAX_VALUE;
        body.setMaxLines(expanded ? 4 : Integer.MAX_VALUE);
        body.setEllipsize(expanded ? android.text.TextUtils.TruncateAt.END
                : null);
    }

    /** Remember the newest ts — powers the Home "NEW" badge. */
    private void markSeen() {
        long newest = 0;
        for (JSONObject n : shown) newest = Math.max(newest,
                n.optLong("ts", 0));
        if (newest > lastSeenTs) {
            lastSeenTs = newest;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_SEEN, newest).apply();
        }
    }

    private static String[] concat(String head, String[] tail) {
        String[] out = new String[tail.length + 1];
        out[0] = head;
        for (int i = 0; i < tail.length; i++) out[i + 1] = tail[i];
        return out;
    }

    // ── post (teachers) ─────────────────────────────────────────────────

    private void askPost() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText title = new EditText(this);
        title.setHint(R.string.field_title);
        final EditText body = new EditText(this);
        body.setHint(R.string.field_message);
        body.setMinLines(3);

        final SpinnerWrap target = new SpinnerWrap(this,
                new String[]{getString(R.string.target_all),
                        getString(R.string.target_class)});
        final SpinnerWrap cls = new SpinnerWrap(this, XDState.CLASSES);
        final SpinnerWrap sec = new SpinnerWrap(this,
                new String[]{getString(R.string.all_sections)}
                        .length == 0 ? new String[]{} : concat(
                        getString(R.string.all_sections), XDState.SECTIONS));
        cls.view.setVisibility(View.GONE);
        sec.view.setVisibility(View.GONE);
        target.view.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v,
                                                 int pos, long id) {
                cls.view.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                sec.view.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        int tm = (int) (10 * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = tm;
        title.setLayoutParams(lp);
        body.setLayoutParams(lp);
        target.view.setLayoutParams(lp);
        cls.view.setLayoutParams(lp);

        box.addView(title);
        box.addView(body);
        box.addView(target.view);
        box.addView(cls.view);
        box.addView(sec.view);

        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.post_notice)
                .setView(box)
                .setPositiveButton(R.string.post, (d, w) -> {
                    String t = title.getText().toString().trim();
                    String b = body.getText().toString().trim();
                    boolean toClass = target.view
                            .getSelectedItemPosition() == 1;
                    String c = toClass
                            ? XDState.CLASSES[Math.max(0,
                                cls.view.getSelectedItemPosition())]
                            : "";
                    String s = "";
                    if (toClass && sec.view.getSelectedItemPosition() > 0) {
                        s = XDState.SECTIONS[
                                sec.view.getSelectedItemPosition() - 1];
                    }
                    if (t.isEmpty()) {
                        Ui.toast(this, getString(R.string.field_title));
                        return;
                    }
                    if (toClass && c.isEmpty()) {
                        Ui.toast(this, getString(R.string.field_class));
                        return;
                    }
                    post(t, b, toClass ? "class" : "all", c, s);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void post(String title, String body, String target, String cls,
                      String sec) {
        final ProgressDialog pd = ProgressDialog.show(this, "",
                getString(R.string.uploading), true);
        new Thread(() -> {
            String err = null;
            try {
                long ts = System.currentTimeMillis();
                String folder = Drive.ensureFolder(
                        "class".equals(target) ? cls : "WholeSchool",
                        Drive.sectionRoot("ANNOUNCEMENTS"));
                JSONObject entry = new JSONObject();
                entry.put("id", "an" + ts);
                entry.put("title", title);
                entry.put("body", body);
                entry.put("target", target);
                entry.put("cls", cls);
                if (sec != null && !sec.isEmpty()) entry.put("sec", sec);
                if (st.email != null && !st.email.isEmpty()) {
                    entry.put("uploader", st.email);
                }
                entry.put("attachments", new JSONArray());
                entry.put("ts", ts);
                entry.put("date", Ui.now("d MMM, yyyy"));
                entry.put("time", Ui.now("h:mm a"));
                byte[] bytes = entry.toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Drive.upload("announcement_" + ts + ".json", folder,
                        entry.toString(), bytes, "application/json");
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final String e = err;
            h.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) {}
                if (e == null) {
                    Ui.toast(NoticesActivity.this,
                            getString(R.string.posted));
                    load();
                } else {
                    Ui.toast(NoticesActivity.this,
                            getString(R.string.upload_failed) + ": " + e);
                }
            });
        }, "xd-notice-post").start();
    }

    private void askDelete(final JSONObject n) {
        final String id = n.optString("driveId", "");
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setMessage(getString(R.string.delete_confirm,
                        n.optString("title", "")))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    final ProgressDialog pd = ProgressDialog.show(this, "",
                            getString(R.string.uploading), true);
                    new Thread(() -> {
                        String err = null;
                        try { Drive.delete(id); }
                        catch (Throwable t) { err = t.getMessage(); }
                        final String e = err;
                        h.post(() -> {
                            try { pd.dismiss(); } catch (Throwable ignored) {}
                            if (e == null) {
                                Ui.toast(NoticesActivity.this,
                                        getString(R.string.deleted));
                                load();
                            } else {
                                Ui.toast(NoticesActivity.this,
                                        getString(R.string.delete_failed)
                                                + ": " + e);
                            }
                        });
                    }, "xd-notice-del").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── adapter ─────────────────────────────────────────────────────────

    private class NoticesAdapter extends BaseAdapter {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_NOTICE = 1;

        private final LayoutInflater inf;

        NoticesAdapter() { inf = LayoutInflater.from(NoticesActivity.this); }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int pos) { return rows.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public int getViewTypeCount() { return 2; }

        @Override
        public int getItemViewType(int pos) {
            return rows.get(pos) instanceof JSONObject
                    ? TYPE_NOTICE : TYPE_HEADER;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            if (getItemViewType(pos) == TYPE_HEADER) {
                if (convert == null) {
                    convert = inf.inflate(R.layout.item_notice_header,
                            parent, false);
                }
                TextView label = convert.findViewById(R.id.section_label);
                label.setText(String.valueOf(rows.get(pos)));
                return convert;
            }

            if (convert == null) {
                convert = inf.inflate(R.layout.item_notice, parent, false);
            }
            final JSONObject n = (JSONObject) rows.get(pos);

            TextView target = convert.findViewById(R.id.notice_target);
            String t = n.optString("target", "all");
            String label = "class".equals(t)
                    ? n.optString("cls", "")
                    : getString(R.string.notice_school_wide);
            String sec = n.optString("sec", "");
            if ("class".equals(t) && !sec.isEmpty()) {
                label += " · " + getString(R.string.section_short, sec);
            }
            target.setText(label);

            TextView date = convert.findViewById(R.id.notice_date);
            String when = n.optString("date", "") + " · "
                    + n.optString("time", "");
            date.setText(when);

            TextView title = convert.findViewById(R.id.notice_title);
            title.setText(n.optString("title", ""));

            final TextView body = convert.findViewById(R.id.notice_body);
            String b = n.optString("body", "");
            body.setText(b);
            body.setVisibility(b.isEmpty() ? View.GONE : View.VISIBLE);
            body.setMaxLines(4);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);

            // separator between cards of the same section (not after the
            // last card of a section — the next header draws its own rule)
            View sep = convert.findViewById(R.id.notice_sep);
            boolean last = pos == rows.size() - 1
                    || !(rows.get(pos + 1) instanceof JSONObject);
            if (sep != null) sep.setVisibility(last ? View.GONE
                    : View.VISIBLE);

            LinearLayout atts = convert.findViewById(R.id.notice_atts);
            atts.removeAllViews();
            JSONArray files = n.optJSONArray("attachments");
            if (files != null && files.length() > 0) {
                atts.setVisibility(View.VISIBLE);
                for (int i = 0; i < files.length(); i++) {
                    JSONObject a = files.optJSONObject(i);
                    if (a == null) continue;
                    final String aid = a.optString("id", "");
                    final String aname = a.optString("name", "file");
                    final String amime = a.optString("type", "");
                    TextView chip = new TextView(NoticesActivity.this);
                    chip.setText(getString(R.string.notice_attachment, aname));
                    chip.setTextSize(13);
                    chip.setTypeface(Typefaces.interMedium(NoticesActivity.this));
                    chip.setTextColor(Fx.color(NoticesActivity.this,
                            R.color.home_brand));
                    chip.setPadding(0, (int) (6 * getResources()
                            .getDisplayMetrics().density), 0, 0);
                    chip.setOnClickListener(v -> {
                        Ui.toast(NoticesActivity.this,
                                getString(R.string.download_started));
                        new Thread(() -> Ui.downloadAndOpen(
                                NoticesActivity.this, aid, aname, amime))
                                .start();
                    });
                    atts.addView(chip);
                }
            } else {
                atts.setVisibility(View.GONE);
            }
            return convert;
        }
    }

    /** Tiny Spinner + ArrayAdapter helper for the dialog forms. */
    static final class SpinnerWrap {
        final android.widget.Spinner view;
        final ArrayAdapter<String> adapter;

        SpinnerWrap(Activity a, String[] options) {
            view = new android.widget.Spinner(a);
            adapter = new ArrayAdapter<>(a,
                    android.R.layout.simple_spinner_item, options);
            adapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            view.setAdapter(adapter);
        }
    }
}
