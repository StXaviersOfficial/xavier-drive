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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * TIMETABLE (v1.2.0 — the COMPLETE redesign the owner ordered).
 *
 * The grid is now a proper card table: a period chip down the left edge,
 * day columns with today softly highlighted, and each cell a rounded
 * card with room to breathe. Teachers edit by TAPPING a cell (a clean
 * subject dialog) instead of forty cramped EditTexts; the Save button
 * commits the whole grid exactly like before (PATCH when the file
 * exists, upload when new).
 */
public class TimetableActivity extends XdActivity {

    private final Handler h = new Handler(Looper.getMainLooper());

    private LinearLayout grid;
    private View progress, emptyView;
    private Spinner classSp, sectionSp;
    private View saveBtn;

    private JSONObject data = new JSONObject();
    private String fileId;             // existing file, null = create new
    private boolean dirty;             // teacher edits since last save
    private boolean loading;
    private XDState st;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timetable);
        st = XDState.get(this);

        grid = findViewById(R.id.tt_grid);
        progress = findViewById(R.id.tt_progress);
        emptyView = findViewById(R.id.tt_empty);
        classSp = findViewById(R.id.tt_class);
        sectionSp = findViewById(R.id.tt_section);
        saveBtn = findViewById(R.id.tt_save);

        findViewById(R.id.tt_back).setOnClickListener(v -> {
            if (dirty && st.teacherMode()) {
                confirmSave();
            } else {
                finish();
            }
        });

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

        // v2.0.0: editing + saving live ONLY in the teacher menu
        if (st.teacherMode()) {
            saveBtn.setVisibility(View.VISIBLE);
            saveBtn.setOnClickListener(v -> confirmSave());
        }
    }

    private String key() {
        String cls = XDState.CLASSES[Math.max(0,
                classSp.getSelectedItemPosition())];
        String sec = XDState.SECTIONS[Math.max(0,
                sectionSp.getSelectedItemPosition())];
        return cls + "_" + sec;
    }

    // ── load ────────────────────────────────────────────────────────────

    private void load() {
        if (loading) return;
        loading = true;
        dirty = false;
        progress.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        grid.removeAllViews();
        new Thread(() -> {
            String err = null;
            JSONObject d = new JSONObject();
            String found = null;
            try {
                String root = Drive.sectionRoot("TIMETABLE");
                String fname = "tt_" + key().replace(' ', '_') + ".json";
                found = Drive.findId("'" + root + "' in parents and name='"
                        + fname + "' and trashed=false");
                if (found != null) {
                    String raw = Drive.readText(found);
                    if (raw != null && raw.trim().startsWith("{")) {
                        d = new JSONObject(raw);
                    }
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final JSONObject dd = d;
            final String fid = found;
            final String e = err;
            h.post(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                data = dd;
                fileId = fid;
                render();
                if (e != null && st.teacherPower()) {
                    Ui.toast(TimetableActivity.this,
                            getString(R.string.error_load) + ": " + e);
                }
            });
        }, "xd-tt-load").start();
    }

    // ── render (the redesigned card grid) ───────────────────────────────

    private void render() {
        grid.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        final boolean editable = st.teacherMode();

        int today = -1;
        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.MONDAY) today = 0;
        else if (dow == Calendar.TUESDAY) today = 1;
        else if (dow == Calendar.WEDNESDAY) today = 2;
        else if (dow == Calendar.THURSDAY) today = 3;
        else if (dow == Calendar.FRIDAY) today = 4;
        else if (dow == Calendar.SATURDAY) today = 5;

        // header row: empty corner + day names
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding((int) (16 * dp), 0, (int) (16 * dp), 0);
        View corner = new View(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                (int) (44 * dp), (int) (36 * dp));
        corner.setLayoutParams(cp);
        head.addView(corner);
        for (int d = 0; d < XDState.DAYS.length; d++) {
            TextView day = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (62 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
            day.setLayoutParams(lp);
            day.setText(XDState.DAYS[d].substring(0, 3));
            day.setGravity(Gravity.CENTER);
            day.setTextSize(12);
            day.setTypeface(Typefaces.interMedium(this));
            boolean now = d == today;
            day.setTextColor(Fx.color(this, now
                    ? R.color.home_brand : R.color.home_muted));
            day.setBackground(now ? getResources()
                    .getDrawable(R.drawable.tt_cell2_today) : null);
            day.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
            head.addView(day);
        }
        grid.addView(head);

        boolean any = false;
        for (int p : XDState.PERIODS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = (int) (8 * dp);
            rp.leftMargin = (int) (16 * dp);
            rp.rightMargin = (int) (16 * dp);
            row.setLayoutParams(rp);

            // the period chip
            TextView num = new TextView(this);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    (int) (44 * dp), (int) (44 * dp));
            num.setLayoutParams(np);
            num.setText("P" + p);
            num.setGravity(Gravity.CENTER);
            num.setTextSize(12.5f);
            num.setTypeface(Typefaces.interMedium(this));
            num.setTextColor(Fx.color(this, R.color.home_brand));
            num.setBackground(getResources()
                    .getDrawable(R.drawable.tt_period_label));
            row.addView(num);

            for (int d = 0; d < XDState.DAYS.length; d++) {
                final String k = XDState.DAYS[d] + "_" + p;
                final String val = data.optString(k, "");
                if (!val.isEmpty()) any = true;

                TextView cell = new TextView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        (int) (62 * dp), (int) (58 * dp));
                lp.setMargins((int) (3 * dp), 0, (int) (3 * dp), 0);
                cell.setLayoutParams(lp);
                cell.setText(val.isEmpty() ? "—" : val);
                cell.setTextSize(12);
                cell.setTypeface(Typefaces.interMedium(this));
                cell.setGravity(Gravity.CENTER);
                cell.setMaxLines(3);
                cell.setPadding((int) (4 * dp), (int) (6 * dp),
                        (int) (4 * dp), (int) (6 * dp));
                boolean todayCol = d == today;
                cell.setTextColor(Fx.color(this, val.isEmpty()
                        ? R.color.home_muted
                        : todayCol ? R.color.home_brand : R.color.home_ink));
                cell.setBackground(getResources().getDrawable(
                        todayCol ? R.drawable.tt_cell2_today
                                : R.drawable.tt_cell2));

                if (editable) {
                    cell.setOnClickListener(v -> editCell(k, val, cell));
                }
                row.addView(cell);
            }
            grid.addView(row);
        }
        emptyView.setVisibility(any ? View.GONE : View.VISIBLE);
    }

    /** Tap-to-edit: one clean dialog per cell (the redesign's editor). */
    private void editCell(final String key, String current,
                          final TextView cell) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText et = new EditText(this);
        et.setText(current);
        et.setHint(R.string.timetable_cell_hint);
        et.setTextSize(15);
        box.addView(et);

        // quick subject chips for one-tap filling (horizontally scrollable)
        android.widget.HorizontalScrollView chipScroll =
                new android.widget.HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        chp.topMargin = (int) (10 * dp);
        chips.setLayoutParams(chp);
        chipScroll.addView(chips);
        String quick[] = {"Mathematics", "Science", "English", "Hindi",
                "Social Studies", "Computer Science", "Free"};
        for (final String q : quick) {
            TextView chip = new TextView(this);
            chip.setText(q);
            chip.setTextSize(12);
            chip.setTypeface(Typefaces.interMedium(this));
            chip.setPadding((int) (12 * dp), (int) (7 * dp),
                    (int) (12 * dp), (int) (7 * dp));
            chip.setBackground(getResources()
                    .getDrawable(R.drawable.chip_off));
            chip.setTextColor(Fx.color(this, R.color.home_ink));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (6 * dp);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> et.setText(
                    "Free".equals(q) ? "" : q));
            chips.addView(chip);
        }
        box.addView(chipScroll);

        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(getString(R.string.timetable_edit_cell,
                        key.replace("_", " · ")))
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String v = et.getText().toString().trim();
                    try {
                        if (v.isEmpty()) {
                            data.remove(key);
                        } else {
                            data.put(key, v);
                        }
                    } catch (Throwable ignored) {}
                    dirty = true;
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── save (teachers) ─────────────────────────────────────────────────

    private void confirmSave() {
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setMessage(R.string.timetable_save_confirm)
                .setPositiveButton(R.string.save, (d, w) -> save())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void save() {
        final ProgressDialog pd = ProgressDialog.show(this, "",
                getString(R.string.uploading), true);
        new Thread(() -> {
            String err = null;
            try {
                byte[] bytes = data.toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (fileId != null) {
                    Drive.updateContent(fileId, bytes);
                } else {
                    String root = Drive.sectionRoot("TIMETABLE");
                    String cls = XDState.CLASSES[Math.max(0,
                            classSp.getSelectedItemPosition())];
                    String sec = XDState.SECTIONS[Math.max(0,
                            sectionSp.getSelectedItemPosition())];
                    JSONObject meta = new JSONObject();
                    meta.put("cls", cls);
                    meta.put("sec", sec);
                    meta.put("type", "timetable");
                    Drive.upload("tt_" + key().replace(' ', '_') + ".json",
                            root, meta.toString(), bytes,
                            "application/json");
                }
                dirty = false;
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final String e = err;
            h.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) {}
                if (e == null) {
                    Ui.toast(TimetableActivity.this,
                            getString(R.string.timetable_saved));
                } else {
                    Ui.toast(TimetableActivity.this,
                            getString(R.string.upload_failed) + ": " + e);
                }
            });
        }, "xd-tt-save").start();
    }
}
