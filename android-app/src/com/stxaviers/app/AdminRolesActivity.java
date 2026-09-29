package com.stxaviers.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * MANAGE ROLES (v1.1.4) — the website's admin panel, native.
 *
 * Three tabs — Students · Teachers · Admins — fed by ONE
 * GET /api/admin/directory call (Drive markers + KV profile names +
 * attendance rolls, merged server-side). Every row shows the account's
 * real name under the email; students also show class and roll number.
 *
 * v1.1.4 owner orders:
 *  - the "+ Add teacher" Gmail row is GONE, replaced by a SEARCH BAR
 *    above the tabs that filters by name, email, class or roll number
 *    (typing "7" surfaces every Class 7 student) and tells you which
 *    OTHER tabs hold matches;
 *  - admins never appear in the Teachers tab anymore (they were merged
 *    in before — exactly what the owner reported);
 *  - Role actions unchanged: promote / demote / ban via /api/user/role.
 *
 * Locks mirror the website: you can never touch yourself, the two
 * hardcoded admins, or (for non-developers) any admin.
 */
public class AdminRolesActivity extends XdActivity {

    /** The hardcoded admins — untouchable, exactly like the website. */
    private static final String[] HARDCODED_ADMINS = {
            "quackeditzofficial@gmail.com", "drrohitkumar27@gmail.com"
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private XDState st;

    private LinearLayout tabsRow, list;
    private View progress, elsewhere;
    private ProgressBar busyBar;
    private EditText search;
    private ImageView searchClear;

    /** One directory entry per account: {email, name, cls, roll}. */
    private final List<JSONObject> students = new ArrayList<>();
    private final List<JSONObject> teachers = new ArrayList<>();
    private final List<JSONObject> admins = new ArrayList<>();
    private String tab = "students";   // students | teachers | admins
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_roles);
        st = XDState.get(this);

        tabsRow = findViewById(R.id.ar_tabs);
        list = findViewById(R.id.ar_list);
        progress = findViewById(R.id.ar_progress);
        elsewhere = findViewById(R.id.ar_elsewhere);
        busyBar = findViewById(R.id.ar_busy);
        search = findViewById(R.id.ar_search);
        searchClear = findViewById(R.id.ar_search_clear);

        findViewById(R.id.ar_back).setOnClickListener(v -> finish());

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a,
                                                    int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a,
                                                int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                searchClear.setVisibility(
                        query.isEmpty() ? View.GONE : View.VISIBLE);
                render();
            }
        });
        searchClear.setOnClickListener(v -> search.setText(""));

        styleTabs();
        load();
    }

    // ── tabs ────────────────────────────────────────────────────────────

    private void styleTabs() {
        tabsRow.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        String labels[] = {getString(R.string.adm_tab_students),
                getString(R.string.adm_tab_teachers),
                getString(R.string.adm_tab_admins)};
        String keys[] = {"students", "teachers", "admins"};
        int counts[] = {students.size(), teachers.size(), admins.size()};
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i];
            TextView tabView = new TextView(this);
            tabView.setText(labels[i] + "  " + counts[i]);
            tabView.setTextSize(13);
            tabView.setTypeface(Typefaces.interMedium(this));
            tabView.setGravity(Gravity.CENTER);
            tabView.setPadding((int) (14 * dp), (int) (9 * dp),
                    (int) (14 * dp), (int) (9 * dp));
            boolean on = k.equals(tab);
            tabView.setBackground(getResources().getDrawable(
                    on ? R.drawable.chip_on : R.drawable.chip_off));
            tabView.setTextColor(Fx.color(this, on
                    ? R.color.home_role_ink : R.color.home_muted));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (8 * dp);
            tabView.setLayoutParams(lp);
            tabView.setOnClickListener(v -> {
                if (tab.equals(k)) return;
                tab = k;
                styleTabs();
                render();
            });
            tabsRow.addView(tabView);
        }
    }

    // ── data ────────────────────────────────────────────────────────────

    private void load() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String err = null;
            students.clear();
            teachers.clear();
            admins.clear();
            ApiClient.Resp r = ApiClient.request("GET", "/api/admin/directory");
            if (r.ok && r.json != null) {
                fill(students, r.json.optJSONArray("students"));
                fill(teachers, r.json.optJSONArray("teachers"));
                fill(admins, r.json.optJSONArray("admins"));
            } else {
                err = r.error().isEmpty() ? ("HTTP " + r.code) : r.error();
            }
            final String e = err;
            h.post(() -> {
                if (isFinishing()) return;
                progress.setVisibility(View.GONE);
                if (e != null) {
                    state(getString(R.string.error_load) + ": " + e);
                    return;
                }
                styleTabs();
                render();
            });
        }, "xd-ar-load").start();
    }

    private static void fill(List<JSONObject> out, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
    }

    // ── search ──────────────────────────────────────────────────────────

    /** Does this entry match the query (name, email, class or roll)? */
    private static boolean matches(JSONObject u, String q) {
        if (q.isEmpty()) return true;
        String email = u.optString("email", "").toLowerCase(Locale.ROOT);
        String name = u.optString("name", "").toLowerCase(Locale.ROOT);
        String cls = u.optString("cls", "").toLowerCase(Locale.ROOT);
        String roll = String.valueOf(u.opt("roll"));
        if ("null".equals(roll)) roll = "";
        // "class 7" and plain "7" both land on Class 7 students
        String clsDigits = cls.replaceAll("[^0-9]", "");
        String qDigits = q.replaceAll("[^0-9]", "");
        boolean digitHit = !qDigits.isEmpty() && !clsDigits.isEmpty()
                && clsDigits.equals(qDigits);
        return email.contains(q) || name.contains(q) || cls.contains(q)
                || digitHit || (!roll.isEmpty() && roll.equals(q));
    }

    // ── render ──────────────────────────────────────────────────────────

    private void render() {
        list.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        final String self = st.email == null ? ""
                : st.email.trim().toLowerCase(Locale.ROOT);

        List<JSONObject> rows;
        int roleLabel;
        int roleColor;
        if ("teachers".equals(tab)) {
            // v1.1.4: ADMINS STAY OUT of the Teachers tab (owner bug
            // report — they were merged in before).
            rows = new ArrayList<>(teachers);
            roleLabel = R.string.adm_role_teacher;
            roleColor = Fx.color(this, R.color.kind_image);
        } else if ("admins".equals(tab)) {
            rows = new ArrayList<>(admins);
            roleLabel = R.string.adm_role_admin;
            roleColor = Fx.color(this, R.color.home_ai_end);
        } else {
            rows = new ArrayList<>(students);
            roleLabel = R.string.adm_role_student;
            roleColor = Fx.color(this, R.color.home_brand);
        }

        // search filter (within the current tab)
        List<JSONObject> shown = new ArrayList<>();
        for (JSONObject u : rows) {
            if (matches(u, query)) shown.add(u);
        }
        Collections.sort(shown, (a, b) -> a.optString("email", "")
                .compareTo(b.optString("email", "")));

        // cross-tab hint — which OTHER tabs hold matches for this query?
        if (!query.isEmpty()) {
            List<String> other = new ArrayList<>();
            int found = shown.size();
            if (!"students".equals(tab)) {
                int n = 0;
                for (JSONObject u : students) if (matches(u, query)) n++;
                if (n > 0) other.add(n + " in Students");
            }
            if (!"teachers".equals(tab)) {
                int n = 0;
                for (JSONObject u : teachers) if (matches(u, query)) n++;
                if (n > 0) other.add(n + " in Teachers");
            }
            if (!"admins".equals(tab)) {
                int n = 0;
                for (JSONObject u : admins) if (matches(u, query)) n++;
                if (n > 0) other.add(n + " in Admins");
            }
            TextView ew = (TextView) elsewhere;
            if (!other.isEmpty()) {
                ew.setText(getString(R.string.adm_search_elsewhere,
                        android.text.TextUtils.join("  ·  ", other)));
                ew.setVisibility(View.VISIBLE);
            } else if (found == 0) {
                ew.setText(getString(R.string.adm_search_none));
                ew.setVisibility(View.VISIBLE);
            } else {
                ew.setVisibility(View.GONE);
            }
        } else {
            elsewhere.setVisibility(View.GONE);
        }

        if (shown.isEmpty()) {
            state(query.isEmpty()
                    ? getString(R.string.adm_no_users)
                    : getString(R.string.adm_search_none));
            return;
        }

        for (final JSONObject u : shown) {
            final String email = u.optString("email", "");
            boolean isAdmin = containsEmail(admins, email);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (14 * dp), (int) (11 * dp),
                    (int) (10 * dp), (int) (11 * dp));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = (int) (6 * dp);
            row.setLayoutParams(rp);
            row.setBackground(getResources().getDrawable(R.drawable.row_card));

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            text.setLayoutParams(tp);

            TextView name = new TextView(this);
            name.setText(u.optString("name", ""));
            name.setTextSize(13.5f);
            name.setTypeface(Typefaces.interMedium(this));
            name.setTextColor(Fx.color(this, R.color.home_ink));
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(name);

            TextView mail = new TextView(this);
            mail.setText(email);
            mail.setTextSize(11.5f);
            mail.setTypeface(Typefaces.interRegular(this));
            mail.setTextColor(Fx.color(this, R.color.home_muted));
            mail.setMaxLines(1);
            mail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            text.addView(mail);

            // the info line (owner order): students show name · class ·
            // roll; teachers and admins show their name only.
            String info = "";
            if ("students".equals(tab)) {
                String cls = u.optString("cls", "");
                String roll = u.has("roll") && !u.isNull("roll")
                        ? String.valueOf(u.opt("roll")) : "";
                StringBuilder sb = new StringBuilder();
                if (!cls.isEmpty()) sb.append(cls);
                if (!roll.isEmpty()) {
                    if (sb.length() > 0) sb.append("  ·  ");
                    sb.append(getString(R.string.adm_roll_no, roll));
                }
                info = sb.toString();
            }
            if (!info.isEmpty()) {
                TextView sub = new TextView(this);
                sub.setText(info);
                sub.setTextSize(11f);
                sub.setTypeface(Typefaces.interRegular(this));
                sub.setTextColor(Fx.color(this, R.color.home_brand));
                text.addView(sub);
            }

            TextView role = new TextView(this);
            role.setText(getString(roleLabel) + (email.equals(self)
                    ? "  ·  " + getString(R.string.adm_you) : ""));
            role.setTextSize(11f);
            role.setTypeface(Typefaces.interRegular(this));
            role.setTextColor(isAdmin
                    ? Fx.color(this, R.color.home_ai_end) : roleColor);
            text.addView(role);
            row.addView(text);

            boolean locked = email.equals(self) || isHardcoded(email)
                    || (isAdmin && !st.isDeveloper);
            if (locked) {
                TextView lock = new TextView(this);
                lock.setText(email.equals(self)
                        ? getString(R.string.adm_you)
                        : (isAdmin && !st.isDeveloper
                            ? getString(R.string.adm_role_admin)
                            : getString(R.string.adm_locked_short)));
                lock.setTextSize(11);
                lock.setTypeface(Typefaces.interMedium(this));
                lock.setTextColor(Fx.color(this, R.color.home_muted));
                lock.setPadding((int) (10 * dp), (int) (6 * dp),
                        (int) (10 * dp), (int) (6 * dp));
                lock.setBackground(getResources()
                        .getDrawable(R.drawable.chip_off));
                row.addView(lock);
            } else {
                TextView roleBtn = new TextView(this);
                roleBtn.setText(R.string.adm_role_btn);
                roleBtn.setTextSize(12);
                roleBtn.setTypeface(Typefaces.interMedium(this));
                roleBtn.setTextColor(Fx.color(this, R.color.home_ink));
                roleBtn.setGravity(Gravity.CENTER);
                roleBtn.setPadding((int) (14 * dp), (int) (7 * dp),
                        (int) (14 * dp), (int) (7 * dp));
                roleBtn.setBackground(getResources()
                        .getDrawable(R.drawable.chip_off));
                roleBtn.setOnClickListener(v -> openRoleMenu(email,
                        containsEmail(students, email), isAdmin));
                row.addView(roleBtn);
            }
            list.addView(row);
        }
    }

    private static boolean containsEmail(List<JSONObject> list, String email) {
        for (JSONObject o : list) {
            if (o.optString("email", "").equals(email)) return true;
        }
        return false;
    }

    private boolean isHardcoded(String email) {
        for (String a : HARDCODED_ADMINS) {
            if (a.equals(email)) return true;
        }
        return false;
    }

    private void state(String text) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typefaces.interRegular(this));
        t.setTextColor(Fx.color(this, R.color.home_muted));
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (16 * dp), (int) (26 * dp),
                (int) (16 * dp), (int) (26 * dp));
        list.addView(t);
    }

    // ── the Role action menu (the website's three options) ──────────────

    private void openRoleMenu(final String email, boolean isStudent,
                              boolean isAdmin) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        box.setBackground(getResources().getDrawable(R.drawable.dialog_bg));
        box.setElevation(10 * dp);

        List<int[]> items = new ArrayList<>();   // {label, action-ix}
        List<String> actions = new ArrayList<>();
        if (isStudent) {
            items.add(new int[]{R.string.adm_promote_teacher});
            actions.add("promote-teacher");
            items.add(new int[]{R.string.adm_promote_admin});
            actions.add("promote-admin");
            items.add(new int[]{R.string.adm_ban});
            actions.add("ban");
        } else if (isAdmin) {
            items.add(new int[]{R.string.adm_demote_teacher});
            actions.add("demote-teacher");
            items.add(new int[]{R.string.adm_demote_student});
            actions.add("demote-student");
            items.add(new int[]{R.string.adm_ban});
            actions.add("ban");
        } else {
            items.add(new int[]{R.string.adm_promote_admin});
            actions.add("promote-admin");
            items.add(new int[]{R.string.adm_demote_student});
            actions.add("demote-student");
            items.add(new int[]{R.string.adm_ban});
            actions.add("ban");
        }

        for (int i = 0; i < items.size(); i++) {
            final String action = actions.get(i);
            final boolean danger = "ban".equals(action);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (16 * dp), (int) (11 * dp),
                    (int) (16 * dp), (int) (11 * dp));
            ImageView ic = new ImageView(this);
            ic.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (17 * dp), (int) (17 * dp)));
            ic.setImageResource(danger ? R.drawable.ic_trash
                    : "promote-admin".equals(action)
                        ? R.drawable.ic_key : "promote-teacher".equals(action)
                        ? R.drawable.ic_upload : R.drawable.ic_people);
            ic.setColorFilter(Fx.color(this, danger
                    ? R.color.danger : R.color.home_muted));
            row.addView(ic);
            TextView label = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = (int) (12 * dp);
            label.setLayoutParams(lp);
            label.setText(items.get(i)[0]);
            label.setTextSize(14);
            label.setTypeface(Typefaces.interMedium(this));
            label.setTextColor(Fx.color(this, danger
                    ? R.color.danger : R.color.home_ink));
            row.addView(label);
            row.setOnClickListener(v -> {
                if (roleMenu != null) {
                    try { roleMenu.dismiss(); } catch (Throwable ignored) {}
                    roleMenu = null;
                }
                confirm(email, action);
            });
            box.addView(row);
        }

        android.widget.PopupWindow pop = new android.widget.PopupWindow(box,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setOutsideTouchable(true);
        pop.setElevation(10 * dp);
        pop.setBackgroundDrawable(getResources()
                .getDrawable(R.drawable.dialog_bg));
        pop.showAtLocation(list, Gravity.CENTER, 0, 0);
        roleMenu = pop;
    }

    private android.widget.PopupWindow roleMenu;

    private void confirm(final String email, final String action) {
        int label = "ban".equals(action) ? R.string.adm_ban_confirm
                : R.string.adm_role_confirm;
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setMessage(getString(label, email))
                .setPositiveButton(R.string.save, (d, w) ->
                        runAction(email, action))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runAction(final String email, final String action) {
        busyBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.requestJson("POST", "/api/user/role",
                    ApiClient.obj("email", email, "action", action));
            final String out;
            if (r.ok && r.json != null) {
                out = r.json.optString("message",
                        getString(R.string.adm_done));
            } else {
                String e = r.error();
                out = e.isEmpty() ? getString(R.string.adm_failed) : e;
            }
            h.post(() -> {
                if (isFinishing()) return;
                busyBar.setVisibility(View.GONE);
                Ui.toast(AdminRolesActivity.this, out);
                load();
            });
        }, "xd-ar-action").start();
    }
}
