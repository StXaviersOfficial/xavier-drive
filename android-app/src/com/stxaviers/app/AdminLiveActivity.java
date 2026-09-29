package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * ADMIN LIVE OVERSIGHT (v1.2.0) — its OWN screen and its own UI (owner
 * order: two different designs for live classes and manage roles).
 *
 * Lists every running class from /api/live/status-all as large oversight
 * cards — subject, teacher, minutes live — with Force end via
 * /api/live/end (admins may end any teacher's class; the route checks).
 */
public class AdminLiveActivity extends XdActivity {

    private final Handler h = new Handler(Looper.getMainLooper());

    private LinearLayout list;
    private View progress;
    private XDState st;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_live);
        st = XDState.get(this);

        list = findViewById(R.id.al_list);
        progress = findViewById(R.id.al_progress);
        findViewById(R.id.al_back).setOnClickListener(v -> finish());
        findViewById(R.id.al_refresh).setOnClickListener(v -> load());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        list.removeAllViews();
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.request("GET", "/api/live/status-all");
            final JSONObject classes = (r.ok && r.json != null)
                    ? r.json.optJSONObject("classes") : null;
            final String err = r.ok ? null : r.error();
            h.post(() -> {
                if (isFinishing()) return;
                progress.setVisibility(View.GONE);
                if (classes == null || classes.length() == 0) {
                    state(err != null && !err.isEmpty()
                            ? getString(R.string.error_load) + ": " + err
                            : getString(R.string.live_none));
                    return;
                }
                Iterator<String> it = classes.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    JSONObject c = classes.optJSONObject(name);
                    if (c != null) row(name, c);
                }
            });
        }, "xd-al-load").start();
    }

    private void row(final String name, JSONObject c) {
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getResources().getDrawable(R.drawable.row_card));
        int pad = (int) (16 * dp);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins((int) (16 * dp), (int) (6 * dp),
                (int) (16 * dp), (int) (6 * dp));
        card.setLayoutParams(cp);

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
        time.setText(getString(R.string.live_minutes, Math.max(0,
                (int) mins)));
        time.setTextSize(12);
        time.setTypeface(Typefaces.interRegular(this));
        time.setTextColor(Fx.color(this, R.color.home_muted));
        head.addView(time);
        card.addView(head);

        String teacher = c.optString("teacherEmail", "");
        if (!teacher.isEmpty()) {
            TextView t2 = new TextView(this);
            t2.setText(teacher);
            t2.setTextSize(12);
            t2.setTypeface(Typefaces.interRegular(this));
            t2.setTextColor(Fx.color(this, R.color.home_muted));
            card.addView(t2);
        }

        TextView end = new TextView(this);
        end.setText(R.string.adm_force_end);
        end.setTextSize(13);
        end.setTypeface(Typefaces.interMedium(this));
        end.setGravity(Gravity.CENTER);
        end.setTextColor(Fx.color(this, R.color.danger));
        end.setBackground(getResources().getDrawable(R.drawable.chip_off));
        end.setPadding((int) (16 * dp), (int) (9 * dp),
                (int) (16 * dp), (int) (9 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * dp);
        lp.gravity = Gravity.END;
        end.setLayoutParams(lp);
        end.setOnClickListener(v -> confirmEnd(name));
        card.addView(end);

        list.addView(card);
    }

    private void confirmEnd(final String name) {
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setMessage(getString(R.string.adm_end_confirm, name))
                .setPositiveButton(R.string.end_class, (d, w) ->
                        endClass(name))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void endClass(final String name) {
        Ui.toast(this, R.string.adm_ending);
        new Thread(() -> {
            ApiClient.requestJson("POST", "/api/live/end",
                    ApiClient.obj("class", name));
            h.post(this::load);
        }, "xd-al-end").start();
    }

    private void state(String text) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setTypeface(Typefaces.interMedium(this));
        t.setTextColor(Fx.color(this, R.color.home_ink));
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (16 * dp), (int) (30 * dp),
                (int) (16 * dp), (int) (30 * dp));
        list.addView(t);
    }
}
