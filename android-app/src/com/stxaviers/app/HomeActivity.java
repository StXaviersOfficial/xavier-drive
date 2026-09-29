package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * XAVIERDRIVE HOME — v1.1.2 — the website-aligned shell.
 *
 * Top bar: [menu switcher] XAVIERDRIVE (animated wordmark) [avatar].
 * The scroll area inflates the ACTIVE MENU:
 *
 *   Student  — daily surface: greeting, newest notice, school tools
 *   Teacher  — the ONLY home of uploads and class-content tools
 *   Admin    — roles and oversight
 *   Developer— live rank/build diagnostics
 *
 * The workspace switch chip (top left) unlocks every menu at or below
 * the account's level (student < teacher < admin < developer). Students
 * see no switcher — they have exactly one menu. Settings live inside
 * the Profile tab (no top-bar icon); no version footer on this screen.
 *
 * Identity + rank come from the shared session probe through XDState —
 * the rank shown is DERIVED (Developer > Admin > Teacher > Student) —
 * and the name + photo sync from the school server (ProfileSync), so
 * the app always shows what the website shows.
 */
public class HomeActivity extends XdActivity {

    public static final String EXTRA_NAME = "user_name";
    public static final String EXTRA_EMAIL = "user_email";

    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastBackAt = 0L;

    private XDState st;
    private FrameLayout homeContent;
    private TextView avatarInitial;
    private ImageView avatarPhoto;
    private TextView wsChipLabel;
    private View wsChip;
    private int renderedWsVersion = -1;
    private String renderedRank = "";
    private String renderedPhotoKey = "";

    // student-menu views (re-bound on every render)
    private TextView greetingName, roleChip, heroNew, noticeTitle, noticeMeta;
    private View liveBadge, noticesBadge;
    private TextView noticeBody;
    private View noticeDivider, noticeViewAll, noticeCaret;
    private boolean noticeExpanded;
    // v1.1.4: classRow removed — the class selector lives in Profile only

    // the one allowed breathing element
    private Runnable livePulse;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        st = XDState.get(this);

        bindShell();
        TabNav.wire(this, TabNav.TAB_HOME);

        // seed identity from the sign-in extras while /me is in flight
        String name = getIntent() == null ? null
                : getIntent().getStringExtra(EXTRA_NAME);
        String email = getIntent() == null ? null
                : getIntent().getStringExtra(EXTRA_EMAIL);
        if (name != null && !name.trim().isEmpty() && !st.known()) {
            st.name = name.trim();
            if (email != null) st.email = email;
        }

        renderContent(true);
        softSessionProbe();
        syncProfile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // menu or identity may have changed while away (e.g. rank refresh)
        if (renderedWsVersion != st.wsVersion()
                || !renderedRank.equals(st.rankLabel())) {
            renderContent(false);
        } else if (XDState.WS_STUDENT.equals(st.workspace())) {
            // back on the student menu: refresh the newest-notice card +
            // unseen badge (the user may have just read them elsewhere)
            probeNotices();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        renderContent(false);
    }

    // ── shell ───────────────────────────────────────────────────────────

    private void bindShell() {
        homeContent = (FrameLayout) findViewById(R.id.home_content);
        wsChip = findViewById(R.id.ws_chip);
        wsChipLabel = (TextView) findViewById(R.id.ws_chip_label);

        if (wsChip != null) {
            wsChip.setOnClickListener(v -> showSwitcher());
        }
    }

    /** The workspace switcher — every menu at or below the account level. */
    private void showSwitcher() {
        final String[] all = {XDState.WS_STUDENT, XDState.WS_TEACHER,
                XDState.WS_ADMIN, XDState.WS_DEVELOPER};
        int[] icons = {R.drawable.ic_school, R.drawable.ic_upload,
                R.drawable.ic_people, R.drawable.ic_key};
        int[] descs = {R.string.ws_student_desc, R.string.ws_teacher_desc,
                R.string.ws_admin_desc, R.string.ws_developer_desc};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        for (int i = 0; i < all.length; i++) {
            if (!st.canUse(all[i])) continue;
            final String ws = all[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (10 * getResources()
                    .getDisplayMetrics().density), 0, 0);

            FrameLayout icon = new FrameLayout(this);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    (int) (40 * getResources().getDisplayMetrics().density),
                    (int) (40 * getResources().getDisplayMetrics().density));
            icon.setLayoutParams(ip);
            icon.setBackground(getResources()
                    .getDrawable(R.drawable.home_tile_bg));
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setPadding((int) (9 * getResources().getDisplayMetrics().density),
                    (int) (9 * getResources().getDisplayMetrics().density),
                    (int) (9 * getResources().getDisplayMetrics().density),
                    (int) (9 * getResources().getDisplayMetrics().density));
            iv.setImageResource(icons[i]);
            iv.setColorFilter(Fx.color(this, R.color.home_tile_ink));
            icon.addView(iv);
            row.addView(icon);

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = (int) (14 * getResources()
                    .getDisplayMetrics().density);
            text.setLayoutParams(tp);

            TextView label = new TextView(this);
            label.setText(XDState.wsLabel(ws));
            label.setTextSize(15);
            label.setTypeface(Typefaces.interMedium(this));
            label.setTextColor(Fx.color(this, R.color.home_ink));
            text.addView(label);

            TextView desc = new TextView(this);
            desc.setText(descs[i]);
            desc.setTextSize(12);
            desc.setTypeface(Typefaces.interRegular(this));
            desc.setTextColor(Fx.color(this, R.color.home_muted));
            text.addView(desc);
            row.addView(text);

            if (ws.equals(st.workspace())) {
                ImageView check = new ImageView(this);
                check.setImageResource(R.drawable.ic_check);
                check.setColorFilter(Fx.color(this, R.color.home_brand));
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                try { dismissDialogSafe(); } catch (Throwable ignored) {}
                st.setWorkspace(HomeActivity.this, ws);
                renderContent(true);
            });
            row.setTag("ws-" + ws);
            box.addView(row);
        }

        currentDialog = new AlertDialog.Builder(this,
                R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.ws_switch_title)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private android.app.Dialog currentDialog;

    private void dismissDialogSafe() {
        if (currentDialog != null) {
            try { currentDialog.dismiss(); } catch (Throwable ignored) {}
            currentDialog = null;
        }
    }

    // ── the active menu ─────────────────────────────────────────────────

    private void renderContent(boolean animate) {
        renderedWsVersion = st.wsVersion();
        renderedRank = st.rankLabel();

        homeContent.removeAllViews();
        int layout;
        String ws = st.workspace();
        if (XDState.WS_TEACHER.equals(ws)) layout = R.layout.home_teacher;
        else if (XDState.WS_ADMIN.equals(ws)) layout = R.layout.home_admin;
        else if (XDState.WS_DEVELOPER.equals(ws)) layout = R.layout.home_dev;
        else layout = R.layout.home_student;

        LayoutInflater.from(this).inflate(layout, homeContent, true);
        bindIdentity();

        if (XDState.WS_TEACHER.equals(ws)) bindTeacherMenu();
        else if (XDState.WS_ADMIN.equals(ws)) bindAdminMenu();
        else if (XDState.WS_DEVELOPER.equals(ws)) bindDevMenu();
        else bindStudentMenu();

        // switch chip: only accounts with more than the student menu
        if (st.level() >= 2) {
            wsChip.setVisibility(View.VISIBLE);
            wsChipLabel.setText(XDState.wsLabel(ws));
        } else {
            wsChip.setVisibility(View.GONE);
        }

        if (animate) entrance();
    }

    /** Greeting, avatar, rank chip, date — shared with the student menu. */
    private void bindIdentity() {
        greetingName = (TextView) findViewById(R.id.greeting_name);
        roleChip = (TextView) findViewById(R.id.role_chip);
        avatarInitial = (TextView) findViewById(R.id.avatar_initial);
        avatarPhoto = (ImageView) findViewById(R.id.avatar_photo);

        TextView greeting = (TextView) findViewById(R.id.greeting_line);
        if (greeting != null) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            int hour = c.get(java.util.Calendar.HOUR_OF_DAY);
            int res = (hour >= 5 && hour < 12) ? R.string.home_greeting_morning
                    : (hour >= 12 && hour < 17) ? R.string.home_greeting_afternoon
                    : R.string.home_greeting_evening;
            greeting.setText(res);
        }

        String first = st.firstName();
        if (greetingName != null) greetingName.setText(first);
        applyAvatar(first);
        if (roleChip != null) roleChip.setText(st.rankLabel());

        TextView date = (TextView) findViewById(R.id.date_line);
        if (date != null) {
            date.setText(new SimpleDateFormat("EEEE, d MMMM",
                    Locale.getDefault()).format(new Date()));
        }
    }

    /** Photo from the school server when the account has one, initial
     *  otherwise — the same identity the website shows. v1.2.0: the photo
     *  is TRUE-circular (owner order) and taps open the Profile tab. */
    private void applyAvatar(String first) {
        android.graphics.Bitmap photo = ProfileSync.photo();
        View avatar = findViewById(R.id.avatar);
        if (avatar != null) {
            Ui.circle(avatar);
            avatar.setOnClickListener(v ->
                    startActivity(new Intent(this, ProfileActivity.class)));
        }
        if (avatarPhoto != null && avatarInitial != null) {
            if (photo != null) {
                avatarPhoto.setImageBitmap(photo);
                avatarPhoto.setVisibility(View.VISIBLE);
                avatarInitial.setVisibility(View.GONE);
            } else {
                avatarPhoto.setVisibility(View.GONE);
                avatarInitial.setVisibility(View.VISIBLE);
                if (first != null && first.length() > 0) {
                    avatarInitial.setText(first.substring(0, 1)
                            .toUpperCase(Locale.ROOT));
                }
            }
        }
    }

    // ── student menu ────────────────────────────────────────────────────

    private void bindStudentMenu() {
        // the role switcher lives ON the greeting line now (owner order
        // v1.2.0) — only accounts with more than the student menu see it
        View sw = findViewById(R.id.std_switch);
        if (sw != null) {
            if (st.level() >= 2) {
                sw.setVisibility(View.VISIBLE);
                sw.setOnClickListener(v -> showSwitcher());
            } else {
                sw.setVisibility(View.GONE);
            }
        }

        // v1.1.4: the class selector lives ONLY in Profile (owner order —
        // it was duplicated here). Home stays clean.

        // the newest notice as REAL content: tap once to read the whole
        // message inline, tap the link to browse every notice
        noticeBody = (TextView) findViewById(R.id.notice_body);
        noticeDivider = findViewById(R.id.notice_divider);
        noticeViewAll = findViewById(R.id.notice_view_all);
        noticeCaret = findViewById(R.id.notice_caret);
        noticeExpanded = false;

        View notice = findViewById(R.id.notice_card);
        if (notice != null) notice.setOnClickListener(v -> toggleNotice());
        if (noticeViewAll != null) {
            noticeViewAll.setOnClickListener(v -> go(NoticesActivity.class));
        }

        heroNew = (TextView) findViewById(R.id.notice_badge);
        noticeTitle = (TextView) findViewById(R.id.notice_title);
        noticeMeta = (TextView) findViewById(R.id.notice_meta);
        liveBadge = findViewById(R.id.live_badge);
        noticesBadge = findViewById(R.id.notices_badge);

        goIf(R.id.tile_timetable, TimetableActivity.class);
        goIf(R.id.tile_live, LiveActivity.class);
        goIf(R.id.tile_logbook, LogbookActivity.class);
        goIf(R.id.tile_notices, NoticesActivity.class);
        goIf(R.id.tile_files, FilesActivity.class);

        probeNotices();
        startLivePulse();
    }

    // v1.1.4: the Home class selector is GONE (owner order — it lives in
    // Profile only). addHomeClassChip/highlightHomeClass removed with it.

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Expand / collapse the newest notice card (accordion, like the
     *  website's school card — the owner liked that motion). */
    private void toggleNotice() {
        noticeExpanded = !noticeExpanded;
        boolean hasBody = noticeBody != null
                && noticeBody.getText().length() > 0;
        if (noticeBody != null) {
            noticeBody.setVisibility(
                    noticeExpanded && hasBody ? View.VISIBLE : View.GONE);
        }
        if (noticeDivider != null) {
            noticeDivider.setVisibility(
                    noticeExpanded && hasBody ? View.VISIBLE : View.GONE);
        }
        if (noticeViewAll != null) {
            noticeViewAll.setVisibility(
                    noticeExpanded ? View.VISIBLE : View.GONE);
        }
        if (noticeCaret != null) {
            noticeCaret.animate().rotation(noticeExpanded ? 90f : 0f)
                    .setDuration(200L).start();
        }
    }

    /** Newest notice (real content) + unseen badge, in one background walk. */
    private void probeNotices() {
        if (noticeTitle == null) return;
        final long seen = getSharedPreferences(
                NoticesActivity.PREFS, MODE_PRIVATE)
                .getLong(NoticesActivity.KEY_SEEN, 0L);
        new Thread(() -> {
            JSONObject newest = null;
            int fresh = 0;
            try {
                String root = Drive.sectionRoot("ANNOUNCEMENTS");
                // whole-school + the student's own class folder
                JSONArray files = Drive.list(
                        "'" + root + "' in parents and "
                                + "mimeType='application/json' and "
                                + "trashed=false",
                        "files(id,name,description,createdTime)",
                        "createdTime desc", 20);
                if (st.klass != null && !st.klass.isEmpty()) {
                    try {
                        String clsId = Drive.ensureFolder(st.klass, root);
                        JSONArray more = Drive.list(
                                "'" + clsId + "' in parents and "
                                        + "mimeType='application/json' and "
                                        + "trashed=false",
                                "files(id,name,description,createdTime)",
                                "createdTime desc", 20);
                        for (int i = 0; i < more.length(); i++) {
                            files.put(more.optJSONObject(i));
                        }
                    } catch (Throwable ignored) {}
                }
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    JSONObject meta = Drive.meta(f);
                    if (!meta.has("title")) continue;
                    long ts = meta.optLong("ts", 0);
                    if (ts <= 0) {
                        String ct = f.optString("createdTime", "");
                        if (ct.length() >= 19) {
                            try {
                                ts = new SimpleDateFormat(
                                        "yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                                        .parse(ct.substring(0, 19))
                                        .getTime();
                            } catch (Throwable ignored) {}
                        }
                    }
                    meta.put("_ts", ts);
                    if (newest == null || ts > newest.optLong("_ts", 0)) {
                        newest = meta;
                    }
                    if (ts > seen) fresh++;
                }
            } catch (Throwable ignored) {}
            final JSONObject n = newest;
            final int count = fresh;
            h.post(() -> {
                if (isFinishing()) return;
                if (noticeTitle != null) {
                    if (n != null) {
                        noticeTitle.setText(n.optString("title", ""));
                        String meta = n.optString("date", "");
                        String time = n.optString("time", "");
                        if (!time.isEmpty()) {
                            meta = meta.isEmpty() ? time : meta + " · " + time;
                        }
                        noticeMeta.setText(meta.isEmpty()
                                ? getString(R.string.latest_notice_meta_none)
                                : meta);
                        if (noticeBody != null) {
                            noticeBody.setText(n.optString("body", ""));
                            // keep the card coherent if it was expanded
                            boolean hasBody = n.optString("body", "")
                                    .length() > 0;
                            noticeBody.setVisibility(noticeExpanded
                                    && hasBody ? View.VISIBLE : View.GONE);
                            if (noticeDivider != null) noticeDivider
                                    .setVisibility(noticeExpanded && hasBody
                                            ? View.VISIBLE : View.GONE);
                        }
                    } else {
                        noticeTitle.setText(
                                getString(R.string.latest_notice_none));
                        noticeMeta.setText(
                                getString(R.string.latest_notice_meta_none));
                        if (noticeBody != null) {
                            noticeBody.setText("");
                            noticeBody.setVisibility(View.GONE);
                        }
                    }
                }
                if (heroNew != null) {
                    if (count > 0 && seen > 0) {
                        heroNew.setText(getString(
                                R.string.notices_new_badge, count));
                        heroNew.setVisibility(View.VISIBLE);
                    } else {
                        heroNew.setVisibility(View.GONE);
                    }
                }
                if (noticesBadge != null) {
                    noticesBadge.setVisibility(count > 0 && seen > 0
                            ? View.VISIBLE : View.GONE);
                }
            });
        }, "xd-home-notices").start();
    }

    private void startLivePulse() {
        stopLivePulse();
        if (liveBadge == null) return;
        final long t0 = System.currentTimeMillis();
        livePulse = new Runnable() {
            @Override
            public void run() {
                if (liveBadge == null) return;
                float t = (System.currentTimeMillis() - t0) / 1000f;
                float a = 0.62f + 0.38f
                        * (float) Math.cos(2 * Math.PI * t / 1.6f);
                liveBadge.setAlpha(a);
                h.postDelayed(this, 32L);
            }
        };
        h.post(livePulse);
    }

    private void stopLivePulse() {
        if (livePulse != null) {
            h.removeCallbacks(livePulse);
            livePulse = null;
        }
    }

    // ── teacher menu ────────────────────────────────────────────────────

    private void bindTeacherMenu() {
        goIf(R.id.tch_upload, FilesActivity.class);
        goIf(R.id.tch_notice, NoticesActivity.class);
        goIf(R.id.tch_logbook, LogbookActivity.class);
        goIf(R.id.tch_timetable, TimetableActivity.class);
        goIf(R.id.tch_attendance, AttendanceActivity.class);
        View live = findViewById(R.id.tch_live);
        if (live != null) {
            live.setOnClickListener(v -> {
                Intent i = new Intent(this, LiveActivity.class);
                i.putExtra(LiveActivity.EXTRA_TAB, LiveActivity.TAB_START);
                startActivity(i);
            });
        }
        stopLivePulse();
    }

    // ── admin menu ──────────────────────────────────────────────────────

    private void bindAdminMenu() {
        // three dedicated screens now (v1.2.0): live oversight, file
        // oversight, and Manage roles — the notice board is gone
        goIf(R.id.adm_live, AdminLiveActivity.class);
        goIf(R.id.adm_files, AdminFilesActivity.class);
        goIf(R.id.adm_roles, AdminRolesActivity.class);
        stopLivePulse();
    }

    // ── developer menu ──────────────────────────────────────────────────

    private void bindDevMenu() {
        setText(R.id.dev_email, st.email == null ? "—" : st.email);
        setText(R.id.dev_rank, st.rankLabel());
        setText(R.id.dev_admin, st.isAdmin
                ? getString(R.string.dev_yes) : getString(R.string.dev_no));
        setText(R.id.dev_developer, st.isDeveloper
                ? getString(R.string.dev_yes) : getString(R.string.dev_no));
        setText(R.id.dev_level, st.level() + " — " + st.rankLabel());
        setText(R.id.dev_ws, XDState.wsLabel(st.workspace()));

        String vn = UpdateCheck.currentVersionName(this);
        int vc = UpdateCheck.currentVersionCode(this);
        setText(R.id.dev_version, (vn.isEmpty() ? "1.1.2" : vn)
                + " (" + vc + ")");

        goIf(R.id.dev_admin_center, AdminRolesActivity.class);
        probeUpdateService();
        stopLivePulse();
    }

    private void probeUpdateService() {
        final TextView status = (TextView) findViewById(R.id.dev_update_status);
        if (status == null) return;
        status.setText(R.string.dev_checking);
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            ApiClient.Resp r = ApiClient.request("GET", "/api/app/version");
            long ms = System.currentTimeMillis() - t0;
            final String out;
            if (r.ok && r.json != null) {
                String remote = r.json.optString("versionName", "?");
                int remoteCode = r.json.optInt("versionCode", 0);
                int local = UpdateCheck.currentVersionCode(this);
                out = remote + (remoteCode > local
                        ? " · " + getString(R.string.dev_update_available)
                        : " · " + ms + " ms");
            } else {
                out = getString(R.string.dev_unreachable);
            }
            h.post(() -> {
                if (!isFinishing() && status != null) status.setText(out);
            });
        }, "xd-dev-probe").start();
    }

    // ── session (identity + rank truth) ─────────────────────────────────

    /**
     * Name + photo sync from the school server (the same Firebase
     * profile the website edits) — whatever the website shows, the app
     * shows. A visible change rebinds the identity block only.
     */
    private void syncProfile() {
        ProfileSync.fetch(this, changed -> {
            if (isFinishing() || !changed) return;
            String key = XDState.get(HomeActivity.this).photo;
            if (!key.equals(renderedPhotoKey)) {
                renderedPhotoKey = key;
                bindIdentity();
            }
        });
    }

    /**
     * Soft /me probe (the shared SessionProbe): fills the real identity +
     * the app-wide XDState (rank powers every screen's gating); a
     * DEFINITIVE dead session sends the user back to login; network
     * trouble is ignored — offline users keep their home screen.
     */
    private void softSessionProbe() {
        SessionProbe.check(this, r -> {
            if (isFinishing()) return;
            if (r.state == SessionProbe.DEAD) {
                try {
                    Toast.makeText(HomeActivity.this,
                            R.string.session_ended, Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(HomeActivity.this,
                            LoginActivity.class));
                    finish();
                } catch (Throwable ignored) {}
                return;
            }
            if (r.hasUser() || !r.role.isEmpty()) {
                XDState st = XDState.get(HomeActivity.this);
                if (r.name != null && !r.name.isEmpty()) st.name = r.name;
                if (r.email != null && !r.email.isEmpty()) st.email = r.email;
                if (!r.role.isEmpty()) st.role = r.role;
                st.isAdmin = r.isAdmin;
                st.isDeveloper = r.isDeveloper;
                st.save(HomeActivity.this);
                if (!renderedRank.equals(st.rankLabel())
                        || renderedWsVersion != st.wsVersion()) {
                    renderContent(false);
                } else {
                    bindIdentity();
                }
            }
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void goIf(int id, final Class<?> cls) {
        View v = findViewById(id);
        if (v != null) v.setOnClickListener(x -> go(cls));
    }

    private void go(Class<?> cls) {
        try {
            startActivity(new Intent(this, cls));
        } catch (Throwable t) {
            Ui.toast(this, String.valueOf(t));
        }
    }

    private void setText(int id, String text) {
        TextView v = (TextView) findViewById(id);
        if (v != null) v.setText(text == null || text.isEmpty()
                ? "—" : text);
    }

    // ── choreography (one restrained stagger per render) ────────────────

    private void entrance() {
        View bar = findViewById(R.id.home_topbar);
        View content = findViewById(R.id.home_content);
        DecelerateInterpolator dec = new DecelerateInterpolator();
        if (bar != null) {
            bar.setAlpha(0f);
            bar.setTranslationY(-14f);
            bar.animate().alpha(1f).translationY(0f)
                    .setDuration(320L).setInterpolator(dec).start();
        }
        if (content != null) {
            content.setAlpha(0f);
            content.setTranslationY(22f);
            content.animate().alpha(1f).translationY(0f)
                    .setDuration(420L).setStartDelay(60L)
                    .setInterpolator(dec).start();
        }
        View nav = findViewById(R.id.bottom_nav);
        if (nav != null) {
            nav.setAlpha(0f);
            nav.setTranslationY(24f);
            nav.animate().alpha(1f).translationY(0f)
                    .setDuration(420L).setStartDelay(120L)
                    .setInterpolator(dec).start();
        }
    }

    // ── system ──────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2200L) {
            super.onBackPressed();
        } else {
            lastBackAt = now;
            try {
                Toast.makeText(this, R.string.exit_hint,
                        Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        stopLivePulse();
        h.removeCallbacksAndMessages(null);
        dismissDialogSafe();
        super.onDestroy();
    }
}
