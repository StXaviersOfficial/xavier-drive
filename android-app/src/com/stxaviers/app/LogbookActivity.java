package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LOGBOOK (v1.1.0) — the class activity photo diary (website-docs/08).
 * Entries are grouped client-side by class|section|date|caption exactly
 * like the site; thumbnails load downsampled (memory-safe) through the
 * /drive/media proxy. Teachers add entries (class, section, date,
 * caption, multi-photo picker).
 */
public class LogbookActivity extends XdActivity {

    private static final int PICK_PHOTOS = 51;

    private final Handler h = new Handler(Looper.getMainLooper());

    private LinearLayout list;
    private View progress, empty;
    private Spinner classSp, sectionSp;
    private TextView dateBtn;
    private String dateFilter = "";   // "" = all dates
    private boolean loading;
    private XDState st;

    /** One grouped entry. */
    static final class Entry {
        String cls, sec, date, cap;
        long ts;
        final List<JSONObject> photos = new ArrayList<>();
    }

    private final List<Entry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logbook);
        st = XDState.get(this);

        list = findViewById(R.id.lb_list);
        progress = findViewById(R.id.lb_progress);
        empty = findViewById(R.id.lb_empty);
        classSp = findViewById(R.id.lb_class);
        sectionSp = findViewById(R.id.lb_section);
        dateBtn = findViewById(R.id.lb_date_btn);

        findViewById(R.id.lb_back).setOnClickListener(v -> finish());

        List<String> classes = new ArrayList<>();
        classes.add(getString(R.string.all_classes));
        for (String c : XDState.CLASSES) classes.add(c);
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, classes);
        a.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        classSp.setAdapter(a);
        if (st.klass != null && !st.klass.isEmpty()) {
            int ix = classes.indexOf(st.klass);
            if (ix > 0) classSp.setSelection(ix);
        }

        // section filter — All sections · A…F (owner order v1.2.0)
        List<String> sections = new ArrayList<>();
        sections.add(getString(R.string.all_sections));
        for (String s : XDState.SECTIONS) sections.add(s);
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, sections);
        sa.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        sectionSp.setAdapter(sa);

        AdapterView.OnItemSelectedListener reload =
                new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v,
                                                 int pos, long id) {
                if (!entries.isEmpty()) render();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        classSp.setOnItemSelectedListener(reload);
        sectionSp.setOnItemSelectedListener(reload);

        // date filter — the school's own date picker, clearable
        dateBtn.setOnClickListener(v -> pickFilterDate());
        findViewById(R.id.lb_date_clear).setOnClickListener(v -> {
            dateFilter = "";
            dateBtn.setText(R.string.all_dates);
            render();
        });

        // v2.0.0: adding entries lives ONLY in the teacher menu
        if (st.teacherMode()) {
            TextView add = findViewById(R.id.lb_add);
            add.setVisibility(View.VISIBLE);
            add.setOnClickListener(v -> pickPhotos());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ── load ────────────────────────────────────────────────────────────

    private void load() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        new Thread(() -> {
            String err = null;
            List<Entry> found = new ArrayList<>();
            try {
                String root = Drive.sectionRoot("LOGBOOK");
                JSONArray classFolders = Drive.list(
                        "'" + root + "' in parents and mimeType='application/"
                                + "vnd.google-apps.folder' and trashed=false",
                        "files(id,name)", null, 50);
                Map<String, Entry> byKey = new HashMap<>();
                for (int i = 0; i < classFolders.length(); i++) {
                    JSONObject cf = classFolders.optJSONObject(i);
                    if (cf == null) continue;
                    JSONArray secFolders = Drive.list(
                            "'" + cf.optString("id") + "' in parents and "
                            + "mimeType='application/vnd.google-apps.folder' "
                            + "and trashed=false",
                            "files(id,name)", null, 50);
                    for (int j = 0; j < secFolders.length(); j++) {
                        JSONObject sf = secFolders.optJSONObject(j);
                        if (sf == null) continue;
                        JSONArray imgs = Drive.list(
                                "'" + sf.optString("id") + "' in parents and "
                                + "trashed=false and mimeType!='application/"
                                + "vnd.google-apps.folder'",
                                "files(id,name,description,createdTime,"
                                        + "mimeType)",
                                "createdTime desc", 200);
                        for (int k = 0; k < imgs.length(); k++) {
                            JSONObject img = imgs.optJSONObject(k);
                            if (img == null) continue;
                            JSONObject m = Drive.meta(img);
                            String cls = m.optString("cls",
                                    cf.optString("name", ""));
                            String sec = m.optString("sec",
                                    sf.optString("name", "")
                                            .replace("Section-", ""));
                            String date = m.optString("date",
                                    Ui.dateOf(img.optString(
                                            "createdTime", "")));
                            String cap = m.optString("cap", "");
                            long ts = m.optLong("ts",
                                    img.optLong("createdTime", 0));
                            String key = cls + "|" + sec + "|" + date
                                    + "|" + cap;
                            Entry e = byKey.get(key);
                            if (e == null) {
                                e = new Entry();
                                e.cls = cls;
                                e.sec = sec;
                                e.date = date;
                                e.cap = cap;
                                e.ts = ts;
                                byKey.put(key, e);
                            }
                            if (ts > e.ts) e.ts = ts;
                            e.photos.add(img);
                        }
                    }
                }
                found.addAll(byKey.values());
                Collections.sort(found, (x, y) ->
                        Long.compare(y.ts, x.ts));
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final List<Entry> ff = found;
            final String e = err;
            h.post(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                if (e != null) {
                    if (entries.isEmpty()) {
                        empty.setVisibility(View.VISIBLE);
                        Ui.toast(LogbookActivity.this,
                                getString(R.string.error_load) + ": " + e);
                    } else {
                        Ui.toast(LogbookActivity.this,
                                getString(R.string.error_load) + ": " + e);
                    }
                    return;
                }
                entries.clear();
                entries.addAll(ff);
                render();
            });
        }, "xd-lb-load").start();
    }

    private void pickFilterDate() {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        if (!dateFilter.isEmpty()) {
            try {
                String[] p = dateFilter.split("-");
                c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1,
                        Integer.parseInt(p[2]));
            } catch (Throwable ignored) {}
        }
        new android.app.DatePickerDialog(this, (dp, y, m, d) -> {
            dateFilter = String.format(Locale.US, "%04d-%02d-%02d",
                    y, m + 1, d);
            dateBtn.setText(Ui.longDate(dateFilter));
            render();
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                c.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    // ── render ──────────────────────────────────────────────────────────

    private void render() {
        list.removeAllViews();
        String filter = classSp.getSelectedItemPosition() == 0 ? ""
                : XDState.CLASSES[classSp.getSelectedItemPosition() - 1];
        String secFilter = sectionSp.getSelectedItemPosition() == 0 ? ""
                : XDState.SECTIONS[sectionSp.getSelectedItemPosition() - 1];
        float dp = getResources().getDisplayMetrics().density;

        // the separation system: class groups (Class 1 → 12) when
        // browsing everything, and a date header inside each group —
        // newest first within a group. Section + date narrow it further.
        List<Entry> view = new ArrayList<>();
        for (Entry e : entries) {
            if (!filter.isEmpty() && !e.cls.equals(filter)) continue;
            if (!secFilter.isEmpty() && !e.sec.equalsIgnoreCase(secFilter)) {
                continue;
            }
            if (!dateFilter.isEmpty() && !e.date.equals(dateFilter)) continue;
            view.add(e);
        }
        java.util.Collections.sort(view, (a, b) -> {
            if (filter.isEmpty()) {
                int ca = classIndex(a.cls), cb = classIndex(b.cls);
                if (ca != cb) return Integer.compare(ca, cb);
            }
            return Long.compare(b.ts, a.ts);
        });

        String lastCls = "\u0000", lastDate = "\u0000";
        for (final Entry e : view) {
            if (filter.isEmpty() && !e.cls.equals(lastCls)) {
                lastCls = e.cls;
                lastDate = "\u0000";
                addClassHeader(e.cls.isEmpty()
                        ? getString(R.string.all_classes) : e.cls, dp);
            }
            if (!e.date.equals(lastDate)) {
                lastDate = e.date;
                addDateHeader(e.date.isEmpty() ? "—" : e.date, dp);
            }

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(getResources().getDrawable(
                    R.drawable.row_card));
            int pad = (int) (14 * dp);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.setMargins((int) (16 * dp), (int) (5 * dp),
                    (int) (16 * dp), (int) (5 * dp));
            card.setLayoutParams(cp);

            // header line — section + caption (class + date live above)
            TextView head = new TextView(this);
            StringBuilder hb = new StringBuilder();
            if (!e.sec.isEmpty()) hb.append(e.sec);
            if (!e.cap.isEmpty()) {
                if (hb.length() > 0) hb.append(" · ");
                hb.append(e.cap);
            }
            if (hb.length() == 0 && !e.cls.isEmpty()) hb.append(e.cls);
            head.setText(hb.length() == 0 ? "—" : hb.toString());
            head.setTextSize(13);
            head.setTypeface(Typefaces.interMedium(this));
            head.setTextColor(Fx.color(this, R.color.home_ink));
            card.addView(head);

            // photo strip
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            int gap = (int) (6 * dp);
            strip.setPadding(0, (int) (10 * dp), 0, 0);
            hs.addView(strip);
            card.addView(hs);

            for (int i = 0; i < e.photos.size(); i++) {
                final JSONObject img = e.photos.get(i);
                final String id = img.optString("id", "");
                FrameLayoutLike cell = new FrameLayoutLike(this, dp);
                strip.addView(cell.view);
                // async thumbnail (downsampled)
                final ImageView target = cell.image;
                new Thread(() -> {
                    Bitmap bmp = loadThumb(id);
                    if (bmp != null) h.post(() -> {
                        if (!isFinishing()) target.setImageBitmap(bmp);
                    });
                }, "xd-lb-thumb").start();
                cell.view.setOnClickListener(v ->
                        openPhoto(img));
            }
            list.addView(card);
        }
        empty.setVisibility(view.isEmpty() && !loading
                ? View.VISIBLE : View.GONE);
    }

    /** Class group header: brand tick + overline + hairline. */
    private void addClassHeader(String label, float dp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * dp), (int) (12 * dp),
                (int) (16 * dp), (int) (2 * dp));

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);

        View tick = new View(this);
        android.widget.LinearLayout.LayoutParams tp =
                new android.widget.LinearLayout.LayoutParams(
                        (int) (14 * dp), (int) (3 * dp));
        tick.setLayoutParams(tp);
        tick.setBackground(getResources().getDrawable(R.drawable.home_role_chip_bg));
        line.addView(tick);

        TextView t = new TextView(this);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (8 * dp);
        t.setLayoutParams(lp);
        t.setText(label);
        t.setTextSize(11.5f);
        t.setLetterSpacing(0.1f);
        t.setTypeface(Typefaces.interMedium(this));
        t.setTextColor(Fx.color(this, R.color.home_muted));
        line.addView(t);
        box.addView(line);

        View rule = new View(this);
        android.widget.LinearLayout.LayoutParams rp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.topMargin = (int) (7 * dp);
        rule.setLayoutParams(rp);
        rule.setBackgroundColor(Fx.color(this, R.color.home_hairline));
        box.addView(rule);

        list.addView(box);
    }

    /** Date header inside a class group: the date + a thin separator. */
    private void addDateHeader(String isoDate, float dp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int side = (int) (20 * dp);
        box.setPadding(side, (int) (8 * dp), side, 0);

        TextView t = new TextView(this);
        t.setText(Ui.longDate(isoDate));
        t.setTextSize(12.5f);
        t.setTypeface(Typefaces.interMedium(this));
        t.setTextColor(Fx.color(this, R.color.home_ink));
        box.addView(t);

        View rule = new View(this);
        android.widget.LinearLayout.LayoutParams rp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
        rp.topMargin = (int) (6 * dp);
        rule.setLayoutParams(rp);
        rule.setBackgroundColor(Fx.color(this, R.color.home_hairline));
        box.addView(rule);

        list.addView(box);
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

    /** Photo cell — ImageView inside a tonal rounded placeholder. */
    private final class FrameLayoutLike {
        final android.widget.FrameLayout view;
        final ImageView image;

        FrameLayoutLike(Activity a, float dp) {
            view = new android.widget.FrameLayout(a);
            int size = (int) (96 * dp);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(size, size);
            lp.rightMargin = (int) (6 * dp);
            view.setLayoutParams(lp);
            view.setBackground(getResources().getDrawable(
                    R.drawable.photo_cell));
            view.setForeground(getResources().getDrawable(
                    R.drawable.home_ripple_card));
            view.setClipToOutline(true);
            image = new ImageView(a);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            android.widget.FrameLayout.LayoutParams ip =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams
                                    .MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams
                                    .MATCH_PARENT);
            image.setLayoutParams(ip);
            view.addView(image);
        }
    }

    /** Downsampled bitmap via the media proxy (memory-safe). */
    private Bitmap loadThumb(String id) {
        try {
            java.io.InputStream in = ApiClient.openStream(
                    "/drive/media?id=" + ApiClient.enc(id), null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] raw = bos.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int sample = 1;
            while (o.outWidth / sample > 288) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(raw, 0, raw.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private void openPhoto(JSONObject img) {
        final String id = img.optString("id", "");
        final String name = img.optString("name", "photo");
        final String mime = img.optString("mimeType", "image/*");
        Ui.toast(this, getString(R.string.download_started));
        new Thread(() -> Ui.downloadAndOpen(this, id, name, mime))
                .start();
    }

    // ── teacher upload ──────────────────────────────────────────────────

    private void pickPhotos() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(
                    Intent.createChooser(i, getString(R.string.pick_photos)),
                    PICK_PHOTOS);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.pick_photos) + ": " + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_PHOTOS || res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        askMeta(uris);
    }

    private void askMeta(final List<Uri> uris) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);

        final Spinner cls = new Spinner(this);
        final Spinner sec = new Spinner(this);
        final EditText date = new EditText(this);
        date.setText(Ui.now("yyyy-MM-dd"));
        date.setHint(R.string.field_date);
        final EditText cap = new EditText(this);
        cap.setHint(R.string.field_caption);

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
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, XDState.SECTIONS);
        sa.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        sec.setAdapter(sa);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * dp);
        for (View v : new View[]{cls, sec, date, cap}) {
            v.setLayoutParams(lp);
        }
        box.addView(cls);
        box.addView(sec);
        box.addView(date);
        box.addView(cap);
        TextView info = new TextView(this);
        info.setText(getString(R.string.photos_selected, uris.size()));
        info.setTextSize(12);
        info.setTextColor(Fx.color(this, R.color.home_muted));
        info.setPadding(0, (int) (8 * dp), 0, 0);
        box.addView(info);

        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.upload_entry)
                .setView(box)
                .setPositiveButton(R.string.upload_entry, (d, w) -> {
                    String c = XDState.CLASSES[Math.max(0,
                            cls.getSelectedItemPosition())];
                    String s = XDState.SECTIONS[Math.max(0,
                            sec.getSelectedItemPosition())];
                    String dt = date.getText().toString().trim();
                    if (dt.isEmpty()) dt = Ui.now("yyyy-MM-dd");
                    upload(uris, c, s, dt,
                            cap.getText().toString().trim());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void upload(final List<Uri> uris, final String cls,
                        final String sec, final String date,
                        final String cap) {
        final ProgressDialog pd = ProgressDialog.show(this, "",
                getString(R.string.uploading), true);
        new Thread(() -> {
            String err = null;
            int done = 0;
            try {
                String folder = Drive.ensureFolder("Section-" + sec,
                        Drive.ensureFolder(cls,
                                Drive.sectionRoot("LOGBOOK")));
                long ts = System.currentTimeMillis();
                for (Uri uri : uris) {
                    byte[] bytes = readUri(uri);
                    if (bytes == null || bytes.length == 0) continue;
                    if (bytes.length > 25L * 1024 * 1024) continue;
                    String name = date + "_" + uriName(uri);
                    JSONObject meta = new JSONObject();
                    meta.put("cls", cls);
                    meta.put("sec", sec);
                    meta.put("date", date);
                    meta.put("cap", cap);
                    meta.put("ts", ts);
                    meta.put("type", "logbook");
                    if (st.email != null && !st.email.isEmpty()) {
                        meta.put("uploader", st.email);
                    }
                    Drive.upload(name, folder, meta.toString(), bytes,
                            "image/jpeg");
                    done++;
                }
                if (done == 0) err = "no readable photos";
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final String e = err;
            final int okCount = done;
            h.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) {}
                if (e == null) {
                    Ui.toast(LogbookActivity.this,
                            getString(R.string.upload_done) + " · "
                                    + okCount);
                    load();
                } else {
                    Ui.toast(LogbookActivity.this,
                            getString(R.string.upload_failed) + ": " + e);
                }
            });
        }, "xd-lb-upload").start();
    }

    private byte[] readUri(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private String uriName(Uri uri) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri, null, null,
                    null, null);
            if (c != null) {
                int ix = c.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME);
                if (ix >= 0 && c.moveToFirst()) name = c.getString(ix);
                c.close();
            }
        } catch (Throwable ignored) {}
        if (name == null) {
            String p = uri.getLastPathSegment();
            name = p == null ? "photo.jpg" : p;
        }
        return name.replaceAll("[/\\\\:*?\"<>|]", "_");
    }
}
