package com.stxaviers.app;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ATTENDANCE (v1.1.4) — the teacher register.
 *
 * v1.1.0 spoke to Firebase RTDB directly over REST — but the database's
 * rules deny anonymous reads AND writes (401), and the old helpers
 * swallowed every error, so "Add student" silently did nothing (the
 * owner's bug report). All traffic now flows through the worker's
 * session-gated /api/attendance route instead:
 *
 *   GET  /api/attendance?key=<class>_<sec>[&date=yyyy-MM-dd]
 *        -> { register: {students:[{name,roll,email}]}, day: {...} }
 *   POST /api/attendance  { key, register:{students:[...]} }
 *   POST /api/attendance  { key, date, day:{students,cls,sec} }
 *
 * The 32GB school server is the primary store (fast); Firebase stays a
 * silent mirror on the server side. Failures now SURFACE as toasts.
 */
public class AttendanceActivity extends XdActivity {

    private final Handler h = new Handler(Looper.getMainLooper());

    private LinearLayout list;
    private View progress, locked;
    private TextView dateBtn, saveBtn, addBtn;
    private Spinner classSp, sectionSp;

    private final List<JSONObject> students = new ArrayList<>();
    private final Map<String, String> marks = new HashMap<>();
    private boolean loading;
    private XDState st;
    private String date = Ui.now("yyyy-MM-dd");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attendance);
        st = XDState.get(this);

        list = findViewById(R.id.att_list);
        progress = findViewById(R.id.att_progress);
        locked = findViewById(R.id.att_locked);
        dateBtn = findViewById(R.id.att_date);
        saveBtn = findViewById(R.id.att_save);
        addBtn = findViewById(R.id.att_add);
        classSp = findViewById(R.id.att_class);
        sectionSp = findViewById(R.id.att_section);

        findViewById(R.id.att_back).setOnClickListener(v -> finish());

        if (!st.teacherPower()) {
            locked.setVisibility(View.VISIBLE);
            progress.setVisibility(View.GONE);
            return;
        }

        ArrayAdapter<String> ca = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, XDState.CLASSES);
        ca.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        classSp.setAdapter(ca);
        if (st.klass != null && !st.klass.isEmpty()) {
            for (int i = 0; i < XDState.CLASSES.length; i++) {
                if (XDState.CLASSES[i].equals(st.klass)) {
                    classSp.setSelection(i);
                    break;
                }
            }
        }

        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, XDState.SECTIONS);
        sa.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        sectionSp.setAdapter(sa);

        AdapterView.OnItemSelectedListener reload =
                new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v,
                                                 int pos, long id) {
                load();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        classSp.setOnItemSelectedListener(reload);
        sectionSp.setOnItemSelectedListener(reload);

        dateBtn.setText(date);
        dateBtn.setOnClickListener(v -> pickDate());

        addBtn.setVisibility(View.VISIBLE);
        addBtn.setOnClickListener(v -> addStudent());

        saveBtn.setVisibility(View.VISIBLE);
        saveBtn.setOnClickListener(v -> save());
    }

    private void pickDate() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText et = new EditText(this);
        et.setText(date);
        et.setHint("yyyy-mm-dd");
        box.addView(et);
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.field_date)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String v = et.getText().toString().trim();
                    if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        date = v;
                        dateBtn.setText(date);
                        load();
                    } else {
                        Ui.toast(this, "yyyy-mm-dd");
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String safeKey() {
        String cls = XDState.CLASSES[Math.max(0,
                classSp.getSelectedItemPosition())];
        String sec = XDState.SECTIONS[Math.max(0,
                sectionSp.getSelectedItemPosition())];
        return cls.replaceAll("[^a-zA-Z0-9_]", "_") + "_"
                + sec.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    // ── load ────────────────────────────────────────────────────────────

    private void load() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        list.removeAllViews();
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.request("GET",
                    "/api/attendance?key=" + ApiClient.enc(safeKey())
                            + "&date=" + ApiClient.enc(date));
            final List<JSONObject> ss = new ArrayList<>();
            final Map<String, String> mm = new HashMap<>();
            String err = null;
            if (r.ok && r.json != null) {
                JSONObject reg = r.json.optJSONObject("register");
                if (reg != null) {
                    JSONArray arr = reg.optJSONArray("students");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject s = arr.optJSONObject(i);
                            if (s != null) ss.add(s);
                        }
                    }
                }
                JSONObject day = r.json.optJSONObject("day");
                if (day != null) {
                    // shape 1: {marks: {roll: P|A}}
                    JSONObject m0 = day.optJSONObject("marks");
                    if (m0 != null) {
                        for (java.util.Iterator<String> it = m0.keys();
                                it.hasNext();) {
                            String k = it.next();
                            mm.put(k, m0.optString(k, ""));
                        }
                    }
                    // shape 2 (the site's writer): {students:[{roll,status}]}
                    JSONArray saved = day.optJSONArray("students");
                    if (saved != null) {
                        for (int i = 0; i < saved.length(); i++) {
                            JSONObject s = saved.optJSONObject(i);
                            if (s == null) continue;
                            mm.put(String.valueOf(s.opt("roll")),
                                    "present".equals(s.optString("status", ""))
                                            ? "P" : "A");
                        }
                    }
                }
            } else {
                err = r.error().isEmpty() ? ("HTTP " + r.code)
                        : r.error();
            }
            final String e = err;
            h.post(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                if (e != null && ss.isEmpty()) {
                    Ui.toast(AttendanceActivity.this,
                            getString(R.string.error_load) + ": " + e);
                }
                students.clear();
                students.addAll(ss);
                marks.clear();
                marks.putAll(mm);
                render();
            });
        }, "xd-att-load").start();
    }

    private void render() {
        list.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        if (students.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(R.string.no_register);
            t.setTextSize(13);
            t.setTextColor(Fx.color(this, R.color.home_muted));
            t.setGravity(Gravity.CENTER);
            t.setPadding((int) (16 * dp), (int) (30 * dp),
                    (int) (16 * dp), (int) (30 * dp));
            list.addView(t);
            return;
        }
        java.util.Collections.sort(students, (a, b) ->
                Integer.compare(a.optInt("roll", 0), b.optInt("roll", 0)));
        for (final JSONObject s : students) {
            final String roll = String.valueOf(s.opt("roll"));
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(getResources().getDrawable(
                    R.drawable.row_card));
            int pad = (int) (12 * dp);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.setMargins((int) (16 * dp), (int) (5 * dp),
                    (int) (16 * dp), (int) (5 * dp));
            card.setLayoutParams(cp);

            TextView rn = new TextView(this);
            rn.setText(roll);
            rn.setTextSize(13);
            rn.setTypeface(Typefaces.interMedium(this));
            rn.setTextColor(Fx.color(this, R.color.home_muted));
            rn.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (36 * dp),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(rn);

            TextView nm = new TextView(this);
            nm.setText(s.optString("name", ""));
            nm.setTextSize(14);
            nm.setTextColor(Fx.color(this, R.color.home_ink));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nm.setLayoutParams(nlp);
            card.addView(nm);

            String cur = marks.containsKey(roll)
                    ? marks.get(roll) : "A";
            final TextView p = pill("P", cur.equals("P"));
            final TextView a = pill("A", cur.equals("A"));
            View.OnClickListener toggle = v -> {
                String now = v == p ? "P" : "A";
                marks.put(roll, now);
                stylePill(p, "P".equals(now));
                stylePill(a, "A".equals(now));
            };
            p.setOnClickListener(toggle);
            a.setOnClickListener(toggle);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    (int) (40 * dp), (int) (32 * dp));
            plp.leftMargin = (int) (6 * dp);
            p.setLayoutParams(plp);
            a.setLayoutParams(plp);
            card.addView(p);
            card.addView(a);
            list.addView(card);
        }
    }

    private TextView pill(String label, boolean on) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTypeface(Typefaces.interMedium(this));
        t.setGravity(Gravity.CENTER);
        stylePill(t, on);
        return t;
    }

    private void stylePill(TextView t, boolean on) {
        boolean isP = t.getText().toString().equals("P");
        t.setBackground(getResources().getDrawable(on
                ? (isP ? R.drawable.att_on : R.drawable.att_off)
                : R.drawable.att_neutral));
        t.setTextColor(Fx.color(this, on ? R.color.white
                : R.color.home_muted));
    }

    // ── register management ─────────────────────────────────────────────

    private void addStudent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(this);
        name.setHint(R.string.student_name);
        final EditText roll = new EditText(this);
        roll.setHint(R.string.roll_number);
        roll.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        // the student's Gmail (owner order v1.2.0) — optional
        final EditText mail = new EditText(this);
        mail.setHint(R.string.student_gmail);
        mail.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * dp);
        roll.setLayoutParams(lp);
        mail.setLayoutParams(lp);
        box.addView(name);
        box.addView(roll);
        box.addView(mail);
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.add_student)
                .setView(box)
                .setPositiveButton(R.string.add_student, (d, w) -> {
                    String n = name.getText().toString().trim();
                    String r = roll.getText().toString().trim();
                    String e = mail.getText().toString().trim()
                            .toLowerCase(java.util.Locale.ROOT);
                    if (n.isEmpty() || r.isEmpty()) return;
                    addStudent(n, Integer.parseInt(r), e);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addStudent(String name, int roll, String email) {
        final ProgressDialog pd = ProgressDialog.show(this, "",
                getString(R.string.uploading), true);
        new Thread(() -> {
            // read-modify-write on the register array through the worker
            // (v1.1.4: the old direct-Firebase write silently 401'd)
            String err = null;
            ApiClient.Resp r = ApiClient.request("GET",
                    "/api/attendance?key=" + ApiClient.enc(safeKey()));
            List<JSONObject> current = new ArrayList<>();
            if (r.ok && r.json != null) {
                JSONObject reg = r.json.optJSONObject("register");
                JSONArray arr = reg == null ? null
                        : reg.optJSONArray("students");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject s = arr.optJSONObject(i);
                        if (s != null) current.add(s);
                    }
                }
            } else {
                err = r.error().isEmpty() ? ("HTTP " + r.code) : r.error();
            }
            if (err == null) {
                try {
                    JSONObject s = new JSONObject();
                    s.put("name", name);
                    s.put("roll", roll);
                    if (email != null && !email.isEmpty()) {
                        s.put("email", email);
                    }
                    current.add(s);
                    JSONArray out = new JSONArray();
                    for (JSONObject o : current) out.put(o);
                    ApiClient.Resp w = ApiClient.requestJson("POST",
                            "/api/attendance", ApiClient.obj("key", safeKey(),
                                    "register", ApiClient.obj("students", out)));
                    if (!w.ok) {
                        err = w.error().isEmpty() ? ("HTTP " + w.code)
                                : w.error();
                    }
                } catch (Throwable t) {
                    err = t.getMessage() == null ? String.valueOf(t)
                            : t.getMessage();
                }
            }
            final String e = err;
            h.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) {}
                if (e != null) Ui.toast(AttendanceActivity.this,
                        getString(R.string.upload_failed) + ": " + e);
                load();
            });
        }, "xd-att-add").start();
    }

    // ── save marks ──────────────────────────────────────────────────────

    private void save() {
        if (students.isEmpty()) return;
        final ProgressDialog pd = ProgressDialog.show(this, "",
                getString(R.string.uploading), true);
        new Thread(() -> {
            JSONArray out = new JSONArray();
            for (JSONObject s : students) {
                String roll = String.valueOf(s.opt("roll"));
                JSONObject o = new JSONObject();
                try {
                    o.put("roll", s.opt("roll"));
                    o.put("name", s.optString("name", ""));
                    o.put("email", s.optString("email", ""));
                    o.put("status", "P".equals(marks.get(roll))
                            ? "present" : "absent");
                    out.put(o);
                } catch (Throwable ignored) {}
            }
            String cls = XDState.CLASSES[Math.max(0,
                    classSp.getSelectedItemPosition())];
            String sec = XDState.SECTIONS[Math.max(0,
                    sectionSp.getSelectedItemPosition())];
            String err = null;
            try {
                ApiClient.Resp r = ApiClient.requestJson("POST",
                        "/api/attendance", ApiClient.obj(
                                "key", safeKey(), "date", date,
                                "day", ApiClient.obj(
                                        "students", out,
                                        "cls", cls, "sec", sec)));
                if (!r.ok) {
                    err = r.error().isEmpty() ? ("HTTP " + r.code)
                            : r.error();
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final String e = err;
            h.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) {}
                Ui.toast(AttendanceActivity.this, e == null
                        ? getString(R.string.attendance_saved)
                        : getString(R.string.upload_failed) + ": " + e);
            });
        }, "xd-att-save").start();
    }
}
