package com.stxaviers.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ADMIN FILE OVERSIGHT (v1.2.0) — who uploaded what, and when.
 *
 * Walks Xavier-Drive/FILES class folders and lists every uploaded file
 * with its name, class · subject, the uploader (v1.2.0 app uploads
 * stamp it) and the upload time + size. A class filter narrows it.
 */
public class AdminFilesActivity extends XdActivity {

    private final Handler h = new Handler(Looper.getMainLooper());

    private LinearLayout list;
    private View progress;
    private Spinner classSp;

    private static final class Row {
        String name, cls, sub, uploader, when, size, mime;
        long ts;
    }

    private final List<Row> all = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_files);

        list = findViewById(R.id.af_list);
        progress = findViewById(R.id.af_progress);
        classSp = findViewById(R.id.af_class);
        findViewById(R.id.af_back).setOnClickListener(v -> finish());

        List<String> classes = new ArrayList<>();
        classes.add(getString(R.string.all_classes));
        for (String c : XDState.CLASSES) classes.add(c);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, classes);
        a.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        classSp.setAdapter(a);
        classSp.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v,
                                                 int pos, long id) {
                render();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        list.removeAllViews();
        new Thread(() -> {
            String err = null;
            List<Row> found = new ArrayList<>();
            try {
                String root = Drive.sectionRoot("FILES");
                JSONArray folders = Drive.list(
                        "'" + root + "' in parents and mimeType='application/"
                                + "vnd.google-apps.folder' and trashed=false",
                        "files(id,name)", null, 50);
                for (int i = 0; i < folders.length(); i++) {
                    JSONObject cf = folders.optJSONObject(i);
                    if (cf == null) continue;
                    JSONArray files = Drive.list(
                            "'" + cf.optString("id") + "' in parents and "
                                    + "trashed=false and mimeType!='application"
                                    + "/vnd.google-apps.folder'",
                            "files(id,name,description,size,createdTime,"
                                    + "mimeType)",
                            "createdTime desc", 200);
                    for (int j = 0; j < files.length(); j++) {
                        JSONObject f = files.optJSONObject(j);
                        if (f == null) continue;
                        JSONObject meta = Drive.meta(f);
                        Row r = new Row();
                        r.name = f.optString("name", "");
                        r.cls = cf.optString("name", "");
                        r.sub = meta.optString("sub", "");
                        r.uploader = meta.optString("uploader", "");
                        r.when = f.optString("createdTime", "");
                        r.mime = f.optString("mimeType", "");
                        r.ts = 0;
                        try {
                            r.ts = new java.text.SimpleDateFormat(
                                    "yyyy-MM-dd'T'HH:mm:ss",
                                    java.util.Locale.US)
                                    .parse(r.when.substring(0,
                                            Math.min(19, r.when.length())))
                                    .getTime();
                        } catch (Throwable ignored) {}
                        r.size = Ui.size(f.optLong("size", 0));
                        found.add(r);
                    }
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            found.sort(Comparator.comparingLong((Row r) -> r.ts).reversed());
            final List<Row> ff = found;
            final String e = err;
            h.post(() -> {
                if (isFinishing()) return;
                progress.setVisibility(View.GONE);
                if (e != null) {
                    state(getString(R.string.error_load) + ": " + e);
                    return;
                }
                all.clear();
                all.addAll(ff);
                render();
            });
        }, "xd-af-load").start();
    }

    private void render() {
        list.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        String filter = classSp.getSelectedItemPosition() == 0 ? ""
                : XDState.CLASSES[classSp.getSelectedItemPosition() - 1];

        int drawn = 0;
        for (Row r : all) {
            if (!filter.isEmpty() && !r.cls.equals(filter)) continue;
            drawn++;

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(getResources().getDrawable(R.drawable.row_card));
            int pad = (int) (14 * dp);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMargins((int) (16 * dp), (int) (5 * dp),
                    (int) (16 * dp), (int) (5 * dp));
            card.setLayoutParams(cp);

            FrameLayoutIcon icon = new FrameLayoutIcon(this, dp,
                    Ui.iconFor(Ui.kind(r.mime, r.name)));

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = (int) (12 * dp);
            text.setLayoutParams(tp);

            TextView name = new TextView(this);
            name.setText(r.name);
            name.setTextSize(13.5f);
            name.setTypeface(Typefaces.interMedium(this));
            name.setTextColor(Fx.color(this, R.color.home_ink));
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            text.addView(name);

            TextView line1 = new TextView(this);
            StringBuilder b1 = new StringBuilder(r.cls);
            if (!r.sub.isEmpty()) b1.append(" · ").append(r.sub);
            line1.setText(b1.toString());
            line1.setTextSize(11.5f);
            line1.setTypeface(Typefaces.interRegular(this));
            line1.setTextColor(Fx.color(this, R.color.home_muted));
            text.addView(line1);

            TextView line2 = new TextView(this);
            String when = r.ts > 0
                    ? Ui.longDate(new java.text.SimpleDateFormat(
                            "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date(r.ts))
                            .substring(0, 10)) : "";
            String by = r.uploader.isEmpty()
                    ? getString(R.string.af_unknown_uploader) : r.uploader;
            line2.setText(when + " · " + by);
            line2.setTextSize(11.5f);
            line2.setTypeface(Typefaces.interRegular(this));
            line2.setTextColor(Fx.color(this, R.color.home_muted));
            text.addView(line2);
            card.addView(icon.view);
            card.addView(text);

            TextView size = new TextView(this);
            size.setText(r.size);
            size.setTextSize(11);
            size.setTypeface(Typefaces.interMedium(this));
            size.setTextColor(Fx.color(this, R.color.home_muted));
            card.addView(size);

            list.addView(card);
        }

        if (drawn == 0) {
            state(getString(R.string.af_empty));
        }
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

    /** Small tonal icon tile (matches the Files screen's look). */
    private static final class FrameLayoutIcon {
        final android.widget.FrameLayout view;

        FrameLayoutIcon(Activity a, float dp, int iconRes) {
            view = new android.widget.FrameLayout(a);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            (int) (40 * dp), (int) (40 * dp));
            view.setLayoutParams(lp);
            view.setBackground(a.getResources()
                    .getDrawable(R.drawable.home_tile_bg));
            ImageView iv = new ImageView(a);
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            iv.setPadding((int) (9 * dp), (int) (9 * dp),
                    (int) (9 * dp), (int) (9 * dp));
            iv.setImageResource(iconRes);
            iv.setColorFilter(a.getResources().getColor(R.color.home_tile_ink));
            view.addView(iv);
        }
    }
}
