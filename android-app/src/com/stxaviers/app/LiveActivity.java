package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

/**
 * LIVE (v1.1.0) — live classes (website-docs/11).
 *
 * Students: what's live now (join via the YouTube app), the class
 * recordings playlists, and the weekly schedule.
 * Teachers: start a class (OBS method) — the stream URL/key appear
 * immediately — plus end-class for their own running broadcast.
 * In-class chat / hand-raise remain on the website for now.
 */
public class LiveActivity extends XdActivity {

    /** Optional intent extra: sub-tab to open (TAB_START for teachers). */
    public static final String EXTRA_TAB = "open_tab";
    public static final int TAB_LIVE = 0;
    public static final int TAB_RECORDINGS = 1;
    public static final int TAB_SCHEDULE = 2;
    public static final int TAB_START = 3;

    private final Handler h = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private View progress;
    private TextView subs[] = new TextView[4];
    private int sub = 0;      // 0 live, 1 recordings, 2 schedule, 3 start
    private boolean loading;

    private XDState st;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);
        st = XDState.get(this);

        content = findViewById(R.id.live_content);
        progress = findViewById(R.id.live_progress);
        findViewById(R.id.live_back).setOnClickListener(v -> finish());

        int ids[] = {R.id.sub_live, R.id.sub_recordings,
                R.id.sub_schedule, R.id.sub_start};
        for (int i = 0; i < ids.length; i++) subs[i] = findViewById(ids[i]);
        // v2.0.0: going live lives ONLY in the teacher menu
        if (st.teacherMode()) subs[3].setVisibility(View.VISIBLE);

        // the teacher menu's "Start a live class" lands straight on the
        // start form
        int openTab = getIntent() == null ? -1
                : getIntent().getIntExtra(EXTRA_TAB, -1);
        if (openTab >= 0 && openTab <= 3
                && (openTab != TAB_START || st.teacherMode())) {
            sub = openTab;
        }

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            subs[i].setOnClickListener(v -> {
                if (sub == idx) return;
                sub = idx;
                styleSubs();
                refresh();
            });
        }
        styleSubs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void styleSubs() {
        for (int i = 0; i < 4; i++) {
            boolean on = (i == sub);
            subs[i].setBackground(getResources().getDrawable(
                    on ? R.drawable.chip_on : R.drawable.chip_off));
            subs[i].setTextColor(Fx.color(this, on
                    ? R.color.home_role_ink : R.color.home_muted));
        }
    }

    private void refresh() {
        if (loading) return;
        content.removeAllViews();
        if (sub == 0) loadLive();
        else if (sub == 1) loadRecordings();
        else if (sub == 2) loadSchedule();
        else showStartForm();
    }

    // ── live now ────────────────────────────────────────────────────────

    private String liveClassFilter = "";
    private JSONObject liveMap = new JSONObject();

    private void loadLive() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.request("GET",
                    "/api/live/status-all");
            final JSONObject classes = (r.ok && r.json != null)
                    ? r.json.optJSONObject("classes") : null;
            final String err = r.ok ? null : r.error();
            h.post(() -> {
                progress.setVisibility(View.GONE);
                liveMap = classes == null ? new JSONObject() : classes;
                content.removeAllViews();
                addLiveFilter();
                if (liveMap.length() == 0) {
                    emptyCard(err);
                    return;
                }
                renderLiveFiltered();
            });
        }, "xd-live-status").start();
    }

    /** The class filter (owner order v1.2.0 — class, not section). */
    private boolean filterArmed = false;

    private void addLiveFilter() {
        filterArmed = false;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) (16 * getResources()
                .getDisplayMetrics().density), 0,
                (int) (16 * getResources()
                        .getDisplayMetrics().density), 0);
        List<String> classes = new ArrayList<>();
        classes.add(getString(R.string.all_classes));
        for (String c : XDState.CLASSES) classes.add(c);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, classes);
        a.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        final Spinner sp = new Spinner(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * dp()));
        lp.topMargin = (int) (4 * dp());
        lp.bottomMargin = (int) (6 * dp());
        sp.setLayoutParams(lp);
        sp.setBackground(getResources().getDrawable(R.drawable.chip_off));
        sp.setAdapter(a);
        if (liveClassFilter != null) {
            int ix = classes.indexOf(liveClassFilter);
            if (ix > 0) sp.setSelection(ix);
        }
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v,
                                                 int pos, long id) {
                if (!filterArmed) {
                    filterArmed = true;   // skip the initial layout fire
                    return;
                }
                liveClassFilter = pos == 0 ? "" : classes.get(pos);
                if (sub == 0) renderLiveFiltered();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        row.addView(sp);
        content.addView(row);
    }

    /** Draw the live cards under the filter row, honouring the filter. */
    private void renderLiveFiltered() {
        // keep the filter row, drop everything below it
        while (content.getChildCount() > 1) {
            content.removeViewAt(content.getChildCount() - 1);
        }
        int drawn = 0;
        Iterator<String> it = liveMap.keys();
        while (it.hasNext()) {
            String name = it.next();
            if (!liveClassFilter.isEmpty() && !name.equals(liveClassFilter)) {
                continue;
            }
            JSONObject c = liveMap.optJSONObject(name);
            if (c == null) continue;
            boolean own = st.email != null && st.email.equals(
                    c.optString("teacherEmail", ""));
            liveCard(name, c, own);
            drawn++;
        }
        if (drawn == 0) {
            emptyCard(null);
        }
    }

    private void liveCard(String name, JSONObject c, boolean own) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        android.widget.FrameLayout.LayoutParams dlp =
                new android.widget.FrameLayout.LayoutParams(
                        (int) (10 * dp), (int) (10 * dp));
        dot.setLayoutParams(dlp);
        dot.setBackground(getResources().getDrawable(R.drawable.home_badge_dot));
        head.addView(dot);

        TextView title = new TextView(this);
        title.setText(c.optString("subject", name) + " · " + name);
        title.setTextSize(15);
        title.setTypeface(Typefaces.interMedium(this));
        title.setTextColor(Fx.color(this, R.color.home_ink));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = (int) (10 * dp);
        title.setLayoutParams(tlp);
        head.addView(title);

        long mins = (System.currentTimeMillis()
                - c.optLong("startedAt", 0)) / 60000L;
        TextView time = new TextView(this);
        time.setText(getString(R.string.live_minutes,
                Math.max(0, (int) mins)));
        time.setTextSize(12);
        time.setTextColor(Fx.color(this, R.color.home_muted));
        head.addView(time);
        card.addView(head);

        TextView teacher = new TextView(this);
        teacher.setText(c.optString("teacherEmail", ""));
        teacher.setTextSize(12);
        teacher.setTextColor(Fx.color(this, R.color.home_muted));
        card.addView(teacher);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.RIGHT);

        if (own && st.teacherPower()) {
            TextView end = action(getString(R.string.end_class), true);
            end.setOnClickListener(v -> endClass(name));
            row.addView(end);
        }
        TextView join = action(getString(R.string.join), !own);
        join.setOnClickListener(v ->
                Ui.openYouTube(this, c.optString("videoId", "")));
        LinearLayout.LayoutParams jlp =
                (LinearLayout.LayoutParams) join.getLayoutParams();
        if (own) jlp.leftMargin = (int) (8 * dp);
        row.addView(join);
        card.addView(row);
        content.addView(card);
    }

    /** End a running broadcast (teacher-owned). */
    private void endClass(final String name) {
        final ProgressDialog pd = ProgressDialog.show(this, "",
                getString(R.string.uploading), true);
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.requestJson("POST",
                    "/api/live/end", ApiClient.obj("class", name));
            final String e = r.ok ? null : r.error();
            h.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) {}
                Ui.toast(LiveActivity.this, e == null
                        ? getString(R.string.deleted) : e);
                refresh();
            });
        }, "xd-live-end").start();
    }

    // ── recordings ──────────────────────────────────────────────────────

    private void loadRecordings() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            // list per class playlist; try the user's class first, else all
            ApiClient.Resp r = ApiClient.request("GET",
                    "/api/live/recordings?class="
                            + ApiClient.enc(st.klass == null
                                    || st.klass.isEmpty()
                                    ? "Class 6" : st.klass));
            JSONArray recs = null;
            String err = r.ok ? null : r.error();
            if (r.ok && r.json != null) {
                recs = r.json.optJSONArray("recordings");
            }
            final JSONArray rr = recs;
            final String e = err;
            h.post(() -> {
                progress.setVisibility(View.GONE);
                if (rr == null || rr.length() == 0) {
                    emptyCard(e);
                    return;
                }
                for (int i = 0; i < rr.length(); i++) {
                    JSONObject v = rr.optJSONObject(i);
                    if (v != null) recordingCard(v);
                }
            });
        }, "xd-live-recs").start();
    }

    private void recordingCard(JSONObject v) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout card = card();
        TextView title = new TextView(this);
        title.setText(v.optString("title", "Recording"));
        title.setTextSize(14);
        title.setTypeface(Typefaces.interMedium(this));
        title.setTextColor(Fx.color(this, R.color.home_ink));
        card.addView(title);

        TextView when = new TextView(this);
        when.setText(Ui.dateOf(v.optString("publishedAt", "")));
        when.setTextSize(12);
        when.setTextColor(Fx.color(this, R.color.home_muted));
        card.addView(when);

        TextView watch = action(getString(R.string.join), true);
        watch.setOnClickListener(v2 ->
                Ui.openYouTube(this, v.optString("videoId", "")));
        card.addView(watch);
        content.addView(card);
    }

    // ── schedule ────────────────────────────────────────────────────────

    private void loadSchedule() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.request("GET", "/api/schedule");
            JSONObject sched = r.ok && r.json != null
                    ? r.json.optJSONObject("schedule") : null;
            final JSONObject s = sched;
            final String e = r.ok ? null : r.error();
            h.post(() -> {
                progress.setVisibility(View.GONE);
                if (s == null || s.length() == 0) {
                    emptyCard(e);
                    return;
                }
                for (String day : XDState.DAYS) {
                    JSONArray items = s.optJSONArray(day);
                    if (items == null || items.length() == 0) continue;
                    TextView dayHead = new TextView(this);
                    dayHead.setText(day);
                    dayHead.setTextSize(12);
                    dayHead.setTypeface(Typefaces.interMedium(this));
                    dayHead.setTextColor(Fx.color(this, R.color.home_muted));
                    dayHead.setPadding((int) (18 * getResources()
                            .getDisplayMetrics().density), (int) (10 * dp()),
                            0, (int) (4 * dp()));
                    content.addView(dayHead);
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.optJSONObject(i);
                        if (it == null) continue;
                        LinearLayout card = card();
                        TextView line = new TextView(this);
                        line.setText(it.optString("time", "") + " · "
                                + it.optString("class", "") + " · "
                                + it.optString("subject", ""));
                        line.setTextSize(14);
                        line.setTextColor(Fx.color(this, R.color.home_ink));
                        card.addView(line);
                        String teacher = it.optString("teacher", "");
                        if (!teacher.isEmpty()) {
                            TextView t2 = new TextView(this);
                            t2.setText(teacher);
                            t2.setTextSize(12);
                            t2.setTextColor(Fx.color(this,
                                    R.color.home_muted));
                            card.addView(t2);
                        }
                        content.addView(card);
                    }
                }
            });
        }, "xd-live-sched").start();
    }

    // ── teacher: start ──────────────────────────────────────────────────

    private void showStartForm() {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout card = card();

        TextView title = new TextView(this);
        title.setText(R.string.start_class);
        title.setTextSize(15);
        title.setTypeface(Typefaces.interMedium(this));
        title.setTextColor(Fx.color(this, R.color.home_ink));
        card.addView(title);

        final Spinner cls = new Spinner(this);
        ArrayAdapter<String> ca = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, XDState.CLASSES);
        ca.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        cls.setAdapter(ca);
        if (st.klass != null && !st.klass.isEmpty()) {
            for (int i = 0; i < XDState.CLASSES.length; i++) {
                if (XDState.CLASSES[i].equals(st.klass)) {
                    cls.setSelection(i);
                    break;
                }
            }
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (44 * dp));
        slp.topMargin = (int) (10 * dp);
        cls.setLayoutParams(slp);
        cls.setBackground(getResources().getDrawable(R.drawable.chip_off));
        card.addView(cls);

        final Spinner subj = new Spinner(this);
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, XDState.SUBJECTS);
        sa.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        subj.setAdapter(sa);
        subj.setLayoutParams(slp);
        subj.setBackground(getResources().getDrawable(R.drawable.chip_off));
        card.addView(subj);

        TextView go = action(getString(R.string.start_class), true);
        go.setOnClickListener(v -> startClass(
                XDState.CLASSES[Math.max(0,
                        cls.getSelectedItemPosition())],
                XDState.SUBJECTS[Math.max(0,
                        subj.getSelectedItemPosition())]));
        card.addView(go);
        // (card() already attached the card to the content column)
    }

    private void startClass(final String cls, final String subject) {
        final ProgressDialog pd = ProgressDialog.show(this, "",
                getString(R.string.uploading), true);
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.requestJson("POST",
                    "/api/live/start",
                    ApiClient.obj("class", cls, "subject", subject,
                            "method", "obs"));
            final boolean ok = r.ok;
            final JSONObject j = r.json;
            final String e = r.ok ? null : r.error();
            h.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) {}
                if (ok) {
                    Ui.toast(LiveActivity.this,
                            getString(R.string.live_started));
                    sub = 0;
                    styleSubs();
                    loadLive();
                    // show stream key panel
                    if (j != null) showKeyPanel(j);
                } else {
                    Ui.toast(LiveActivity.this, e == null
                            ? getString(R.string.upload_failed) : e);
                }
            });
        }, "xd-live-start").start();
    }

    private void showKeyPanel(JSONObject j) {
        float dp = getResources().getDisplayMetrics().density;
        JSONObject st0 = j.optJSONObject("status") != null
                ? j.optJSONObject("status") : j;
        String url = st0.optString("streamUrl",
                st0.optString("rtmpUrl", ""));
        String key = st0.optString("streamKey", "");
        LinearLayout card = card();
        TextView t1 = new TextView(this);
        t1.setText(R.string.stream_url);
        t1.setTextSize(12);
        t1.setTextColor(Fx.color(this, R.color.home_muted));
        card.addView(t1);
        TextView v1 = new TextView(this);
        v1.setText(url.isEmpty() ? "—" : url);
        v1.setTextSize(13);
        v1.setTextColor(Fx.color(this, R.color.home_ink));
        v1.setBackground(getResources().getDrawable(R.drawable.key_panel));
        v1.setPadding((int) (10 * dp), (int) (8 * dp), (int) (10 * dp),
                (int) (8 * dp));
        card.addView(v1);
        TextView t2 = new TextView(this);
        t2.setText(R.string.stream_key);
        t2.setTextSize(12);
        t2.setTextColor(Fx.color(this, R.color.home_muted));
        t2.setPadding(0, (int) (10 * dp), 0, 0);
        card.addView(t2);
        TextView v2 = new TextView(this);
        v2.setText(key.isEmpty() ? "—" : key);
        v2.setTextSize(13);
        v2.setTextColor(Fx.color(this, R.color.home_ink));
        v2.setBackground(getResources().getDrawable(R.drawable.key_panel));
        v2.setPadding((int) (10 * dp), (int) (8 * dp), (int) (10 * dp),
                (int) (8 * dp));
        card.addView(v2);
        content.addView(card);
    }

    // ── card helpers ────────────────────────────────────────────────────

    private LinearLayout card() {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getResources().getDrawable(R.drawable.row_card));
        int pad = (int) (14 * dp);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins((int) (16 * dp), (int) (5 * dp), (int) (16 * dp),
                (int) (5 * dp));
        card.setLayoutParams(cp);
        content.addView(card);
        return card;
    }

    private TextView action(String label, boolean primary) {
        float dp = getResources().getDisplayMetrics().density;
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTypeface(Typefaces.interMedium(this));
        b.setGravity(Gravity.CENTER);
        b.setTextColor(Fx.color(this, primary
                ? R.color.home_hero_ink : R.color.home_brand));
        b.setBackground(getResources().getDrawable(primary
                ? R.drawable.btn_primary : R.drawable.btn_outline));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (int) (38 * dp));
        lp.topMargin = (int) (10 * dp);
        lp.rightMargin = (int) (8 * dp);
        lp.gravity = Gravity.RIGHT;
        b.setLayoutParams(lp);
        b.setPadding((int) (18 * dp), 0, (int) (18 * dp), 0);
        return b;
    }

    private void emptyCard(String err) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding((int) (16 * dp), (int) (40 * dp),
                (int) (16 * dp), (int) (40 * dp));
        TextView t = new TextView(this);
        t.setText(err == null ? getString(R.string.live_none)
                : getString(R.string.error_load) + ": " + err);
        t.setTextSize(15);
        t.setTypeface(Typefaces.interMedium(this));
        t.setTextColor(Fx.color(this, R.color.home_ink));
        t.setGravity(Gravity.CENTER);
        wrap.addView(t);
        TextView s = new TextView(this);
        s.setText(err == null ? getString(R.string.live_none_sub) : "");
        s.setTextSize(13);
        s.setTextColor(Fx.color(this, R.color.home_muted));
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, (int) (4 * dp), 0, 0);
        wrap.addView(s);
        content.addView(wrap);
    }

    private void note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Fx.color(this, R.color.home_muted));
        t.setPadding((int) (18 * getResources().getDisplayMetrics().density),
                (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        content.addView(t);
    }

    private int dp() {
        return (int) getResources().getDisplayMetrics().density;
    }
}
