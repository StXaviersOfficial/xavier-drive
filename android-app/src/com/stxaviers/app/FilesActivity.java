package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * FILES (v1.1.2) — the school's Drive library, sectioned by class.
 *
 * Mirrors the website's Files tab (website-docs/06): one Drive query loads
 * every library file (description JSON carries cls/sub/chp), then the
 * client filters locally by class / subject / chapter / type / name. The
 * list is grouped with class headers and thin separators (the separation
 * system the owner asked for); a student's Profile class is the DEFAULT
 * filter and can still be changed by hand. Students browse + download +
 * open; teachers in the teacher menu additionally upload and delete.
 */
public class FilesActivity extends XdActivity {

    public static final String EXTRA_FOCUS_SEARCH = "focus_search";

    private static final int PICK_FILE = 41;

    private final Handler h = new Handler(Looper.getMainLooper());

    // data
    private final List<JSONObject> all = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    /** Sectioned rows: a class header (String) or a file (JSONObject). */
    private final List<Object> rows = new ArrayList<>();
    private boolean loading;
    private String qClass = "";      // "" = all
    private String qSubject = "";
    private String qKind = "all";
    private String qText = "";

    // views
    private FilesAdapter adapter;
    private ListView list;
    private View progress, empty, errorBox;
    private TextView countLine, errorText;
    private TextView chips[] = new TextView[5];

    private XDState st;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);
        st = XDState.get(this);

        list = findViewById(R.id.files_list);
        progress = findViewById(R.id.files_progress);
        empty = findViewById(R.id.files_empty);
        errorBox = findViewById(R.id.files_error);
        countLine = findViewById(R.id.files_count);
        errorText = findViewById(R.id.files_error_text);
        adapter = new FilesAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object row = rows.get(pos);
            if (row instanceof JSONObject) openItem((JSONObject) row);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            // uploads + deletes live ONLY in the teacher menu
            if (st.teacherMode() && rows.get(pos) instanceof JSONObject) {
                askDelete((JSONObject) rows.get(pos));
                return true;
            }
            return false;
        });

        bindBack();
        bindSpinners();
        bindChips();
        findViewById(R.id.files_retry).setOnClickListener(v -> load());

        // v2.0.0: the upload FAB exists ONLY inside the teacher menu —
        // admins and developers must switch there to upload (owner order)
        if (st.teacherMode()) {
            View fab = findViewById(R.id.files_fab);
            fab.setVisibility(View.VISIBLE);
            fab.setOnClickListener(v -> pickFile());
        }

        if (getIntent() != null && getIntent().getBooleanExtra(
                EXTRA_FOCUS_SEARCH, false)) {
            EditText s = findViewById(R.id.files_search);
            s.post(() -> {
                s.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(s, 0);
            });
        }
    }

    /** A school tool screen — the back chevron returns to Home. */
    private void bindBack() {
        View back = findViewById(R.id.files_back);
        if (back != null) back.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();   // fresh data on every entry; in-flight guarded
    }

    // ── data ────────────────────────────────────────────────────────────

    private void load() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        errorBox.setVisibility(View.GONE);
        new Thread(() -> {
            String query = "trashed=false and mimeType!='application/"
                    + "vnd.google-apps.folder' and fullText contains 'cls'";
            String err = null;
            JSONArray files = null;
            try {
                files = Drive.list(query,
                        "files(id,name,mimeType,size,modifiedTime,description)",
                        "modifiedTime desc", 500);
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            }
            final JSONArray f = files;
            final String e = err;
            h.post(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                if (e != null) {
                    errorText.setText(getString(R.string.error_load)
                            + ": " + e);
                    errorBox.setVisibility(all.isEmpty()
                            ? View.VISIBLE : View.GONE);
                    if (!all.isEmpty()) Ui.toast(FilesActivity.this,
                            getString(R.string.error_load) + ": " + e);
                    return;
                }
                all.clear();
                if (f != null) {
                    for (int i = 0; i < f.length(); i++) {
                        JSONObject o = f.optJSONObject(i);
                        if (o == null) continue;
                        // v1.1.4: announcements, timetables, notices and
                        // logbook entries are NOT library files — their
                        // description JSON carries cls too, so they used
                        // to leak into this tab as raw .json rows.
                        String nm = o.optString("name", "").toLowerCase();
                        if (nm.startsWith("announcement_")
                                || nm.startsWith("tt_")
                                || nm.endsWith(".chatmeta.json")) continue;
                        JSONObject m = Drive.meta(o);
                        if (m.has("cls") && !m.has("type")) all.add(o);
                    }
                }
                applyFilters();
            });
        }, "xd-files-load").start();
    }

    private void applyFilters() {
        shown.clear();
        String myClass = st.klass;
        for (JSONObject o : all) {
            JSONObject m = Drive.meta(o);
            String cls = m.optString("cls", "");
            if (!qClass.isEmpty() && !cls.isEmpty() && !cls.equals(qClass)) continue;
            // students default to their own class when set (site parity)
            if (qClass.isEmpty() && !st.teacherPower()
                    && !myClass.isEmpty() && !cls.isEmpty()
                    && !cls.equals(myClass)) continue;
            if (!qSubject.isEmpty()
                    && !m.optString("sub", "").equals(qSubject)) continue;
            String name = o.optString("name", "").toLowerCase();
            String sub = m.optString("sub", "").toLowerCase();
            String chp = m.optString("chp", "").toLowerCase();
            if (!qText.isEmpty()) {
                String t = qText.toLowerCase();
                if (!name.contains(t) && !sub.contains(t) && !chp.contains(t)) continue;
            }
            if (!qKind.equals("all")) {
                String k = Ui.kind(o.optString("mimeType", ""),
                        o.optString("name", ""));
                if (!k.equals(qKind)
                        && !(qKind.equals("docs") && k.equals("doc"))) continue;
            }
            shown.add(o);
        }
        countLine.setText(getString(R.string.screen_files)
                + "  ·  " + shown.size());
        buildRows();
        adapter.notifyDataSetChanged();
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * The separation system: when browsing all classes, files group under
     * a header per class (Class 1 → Class 12 order); inside a group they
     * keep the newest-first order, with thin separators between rows.
     */
    private void buildRows() {
        rows.clear();
        if (shown.isEmpty()) return;

        if (!qClass.isEmpty()) {
            rows.addAll(shown);   // single class selected — one plain group
            return;
        }

        java.util.LinkedHashMap<String, List<JSONObject>> byClass =
                new java.util.LinkedHashMap<>();
        for (JSONObject o : shown) {
            String c = Drive.meta(o).optString("cls", "");
            if (!byClass.containsKey(c)) byClass.put(c, new ArrayList<>());
            byClass.get(c).add(o);
        }
        List<String> classes = new ArrayList<>(byClass.keySet());
        java.util.Collections.sort(classes, (a, b) -> {
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

    // ── row actions ─────────────────────────────────────────────────────

    private void openItem(final JSONObject o) {
        final String id = o.optString("id", "");
        final String name = o.optString("name", "file");
        final String mime = o.optString("mimeType", "");
        Ui.toast(this, getString(R.string.download_started));
        new Thread(() -> {
            final String res = Ui.downloadAndOpen(this, id, name, mime);
            h.post(() -> {
                if (!"ok".equals(res)) Ui.toast(FilesActivity.this, res);
            });
        }, "xd-file-open").start();
    }

    private void askDelete(final JSONObject o) {
        final String id = o.optString("id", "");
        final String name = o.optString("name", "");
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setMessage(getString(R.string.delete_confirm, name))
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
                                Ui.toast(FilesActivity.this,
                                        getString(R.string.deleted));
                                load();
                            } else {
                                Ui.toast(FilesActivity.this,
                                        getString(R.string.delete_failed)
                                                + ": " + e);
                            }
                        });
                    }, "xd-file-del").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── upload (teachers) ───────────────────────────────────────────────

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(
                    Intent.createChooser(i, getString(R.string.pick_file)),
                    PICK_FILE);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.pick_file) + ": " + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_FILE || res != RESULT_OK || data == null
                || data.getData() == null) return;
        final Uri uri = data.getData();
        askUploadMeta(uri);
    }

    private void askUploadMeta(final Uri uri) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);

        // ── v1.1.4: the SELECTED FILE, visible and editable ──────────
        // Before this the sheet never showed what had been picked, and
        // the name couldn't be corrected before upload (owner order).
        final String rawName = uriName(uri);
        long size = 0;
        try {
            android.database.Cursor c = getContentResolver().query(uri,
                    null, null, null, null);
            if (c != null) {
                int ix = c.getColumnIndex(
                        android.provider.OpenableColumns.SIZE);
                if (ix >= 0 && c.moveToFirst()) size = c.getLong(ix);
                c.close();
            }
        } catch (Throwable ignored) {}

        LinearLayout fileCard = new LinearLayout(this);
        fileCard.setOrientation(LinearLayout.HORIZONTAL);
        fileCard.setGravity(Gravity.CENTER_VERTICAL);
        fileCard.setPadding((int) (12 * dp), (int) (10 * dp),
                (int) (12 * dp), (int) (10 * dp));
        fileCard.setBackground(getResources()
                .getDrawable(R.drawable.row_card));
        ImageView fIcon = new ImageView(this);
        fIcon.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (22 * dp), (int) (22 * dp)));
        fIcon.setImageResource(R.drawable.ic_file);
        fIcon.setColorFilter(Fx.color(this, R.color.home_brand));
        fileCard.addView(fIcon);
        LinearLayout fText = new LinearLayout(this);
        fText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ftp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ftp.leftMargin = (int) (10 * dp);
        fText.setLayoutParams(ftp);
        TextView fName = new TextView(this);
        fName.setText(rawName);
        fName.setTextSize(13);
        fName.setTypeface(Typefaces.interMedium(this));
        fName.setTextColor(Fx.color(this, R.color.home_ink));
        fName.setMaxLines(1);
        fName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        fText.addView(fName);
        TextView fSize = new TextView(this);
        fSize.setText(Ui.size(size));
        fSize.setTextSize(11);
        fSize.setTextColor(Fx.color(this, R.color.home_muted));
        fText.addView(fSize);
        fileCard.addView(fText);
        box.addView(fileCard);

        final EditText nameEdit = new EditText(this);
        nameEdit.setText(rawName);
        nameEdit.setHint(R.string.file_name_hint);
        LinearLayout.LayoutParams nep = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nep.topMargin = (int) (10 * dp);
        nameEdit.setLayoutParams(nep);
        box.addView(nameEdit);

        final Spinner cls = new Spinner(this);
        final Spinner subj = new Spinner(this);
        final EditText chp = new EditText(this);
        chp.setHint(R.string.topic_hint);
        for (Spinner s : new Spinner[]{cls, subj}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (44 * dp));
            lp.topMargin = (int) (10 * dp);
            s.setLayoutParams(lp);
            s.setBackground(getResources()
                    .getDrawable(R.drawable.chip_off));
        }
        List<String> classes = new ArrayList<>();
        classes.add(getString(R.string.all_classes));
        for (String c : XDState.CLASSES) classes.add(c);
        if (st.klass != null && !st.klass.isEmpty()
                && !classes.contains(st.klass)) classes.add(st.klass);
        List<String> subjects = new ArrayList<>();
        subjects.add(getString(R.string.all_subjects));
        for (String s : XDState.SUBJECTS) subjects.add(s);
        cls.setAdapter(makeAdapter(classes));
        subj.setAdapter(makeAdapter(subjects));
        if (st.klass != null && !st.klass.isEmpty()) {
            int ix = classes.indexOf(st.klass);
            if (ix > 0) cls.setSelection(ix);
        }

        TextView l1 = new TextView(this);
        l1.setText(R.string.field_class);
        l1.setTextSize(12);
        l1.setTextColor(Fx.color(this, R.color.home_muted));
        LinearLayout.LayoutParams l1p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        l1p.topMargin = (int) (10 * dp);
        l1.setLayoutParams(l1p);
        TextView l2 = new TextView(this);
        l2.setText(R.string.field_subject);
        l2.setTextSize(12);
        l2.setTextColor(Fx.color(this, R.color.home_muted));
        l2.setLayoutParams(l1p);
        TextView l3 = new TextView(this);
        l3.setText(R.string.field_topic);
        l3.setTextSize(12);
        l3.setTextColor(Fx.color(this, R.color.home_muted));

        box.addView(l1); box.addView(cls);
        box.addView(l2); box.addView(subj);
        box.addView(l3); box.addView(chp);

        // live progress bar + percentage (owner order v1.1.4)
        final ProgressBar bar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams barp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (6 * dp));
        barp.topMargin = (int) (12 * dp);
        bar.setLayoutParams(barp);
        bar.setVisibility(View.GONE);
        box.addView(bar);
        final TextView pct = new TextView(this);
        pct.setTextSize(11);
        pct.setTextColor(Fx.color(this, R.color.home_muted));
        pct.setVisibility(View.GONE);
        box.addView(pct);

        final AlertDialog[] holder = new AlertDialog[1];
        final String[] uploading = {null};
        holder[0] = new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.upload_file)
                .setView(box)
                .setPositiveButton(R.string.upload_file, (d, w) -> {
                    if (uploading[0] != null) return;   // already going
                    String c = cls.getSelectedItemPosition() == 0
                            ? "" : classes.get(cls.getSelectedItemPosition());
                    String s = subj.getSelectedItemPosition() == 0
                            ? "" : subjects.get(subj.getSelectedItemPosition());
                    String edited = nameEdit.getText().toString().trim();
                    String fileName = edited.isEmpty() ? rawName : edited;
                    String topic = chp.getText().toString().trim();
                    uploading[0] = fileName;
                    if (holder[0] != null) {
                        try {
                            holder[0].getButton(AlertDialog.BUTTON_POSITIVE)
                                    .setEnabled(false);
                        } catch (Throwable ignored) {}
                    }
                    upload(uri, c, s, topic, fileName, bar, pct, holder);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private ArrayAdapter<String> makeAdapter(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    /**
     * v1.1.4 upload — the owner's full spec:
     *  - All Classes AND All Subjects are both valid targets (the old
     *    code threw a bare "Class" error — the mystery "upload didn't
     *    finish class" bug).
     *  - The Drive file name = the (editable) name field, falling back
     *    to the picked file's own name.
     *  - Students never see that raw name: when a Topic is entered, the
     *    student-facing display name becomes "<topic>.<extension>".
     *  - A live progress bar runs inside the sheet; the button stays
     *    disabled until the upload has finished.
     */
    private void upload(final Uri uri, final String cls,
                        final String sub, final String topic,
                        final String fileName, final ProgressBar bar,
                        final TextView pct, final AlertDialog[] holder) {
        if (bar != null) {
            bar.post(() -> bar.setVisibility(View.VISIBLE));
        }
        if (pct != null) {
            pct.post(() -> {
                pct.setVisibility(View.VISIBLE);
                pct.setText(R.string.uploading);
            });
        }
        final java.util.function.IntConsumer progress = p -> {
            if (bar != null) bar.post(() -> bar.setProgress(p));
            if (pct != null) pct.post(() -> pct.setText(
                    getString(R.string.uploading) + "  " + p + "%"));
        };
        new Thread(() -> {
            String err = null;
            try {
                byte[] bytes = readUri(uri);
                if (bytes == null || bytes.length == 0) {
                    throw new Drive.DriveException("empty file");
                }
                if (bytes.length > 100L * 1024 * 1024) {
                    throw new Drive.DriveException(
                            "file larger than 100 MB — use the website "
                            + "for very large uploads");
                }
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";
                // All-classes files live in a shared folder every
                // student can see; per-class files in their class folder.
                String parentId = Drive.ensureFolder(
                        cls.isEmpty() ? "School-wide" : cls,
                        Drive.sectionRoot("FILES"));
                JSONObject meta = new JSONObject();
                meta.put("cls", cls);          // "" = every class
                if (!sub.isEmpty()) meta.put("sub", sub);
                if (!topic.isEmpty()) meta.put("chp", topic);
                meta.put("lib", 1);            // library-file marker
                if (st.email != null && !st.email.isEmpty()) {
                    meta.put("uploader", st.email);
                }
                // the student-facing name: topic + extension when a topic
                // was entered, otherwise the edited file name
                String disp = fileName;
                if (!topic.isEmpty()) {
                    int dot = fileName.lastIndexOf('.');
                    String ext = dot > 0 ? fileName.substring(dot) : "";
                    disp = topic + ext;
                }
                meta.put("disp", disp);
                Drive.upload(fileName, parentId, meta.toString(), bytes,
                        mime, progress);
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final String e = err;
            h.post(() -> {
                if (isFinishing()) return;
                if (e == null) {
                    if (bar != null) bar.setProgress(100);
                    if (pct != null) pct.setText(R.string.upload_done);
                    Ui.toast(FilesActivity.this,
                            getString(R.string.upload_done));
                    if (holder[0] != null) {
                        try { holder[0].dismiss(); } catch (Throwable ignored) {}
                    }
                    load();
                } else {
                    if (pct != null) pct.setText(
                            getString(R.string.upload_failed) + ": " + e);
                    if (holder[0] != null) {
                        try {
                            holder[0].getButton(AlertDialog.BUTTON_POSITIVE)
                                    .setEnabled(true);
                        } catch (Throwable ignored) {}
                    }
                    Ui.toast(FilesActivity.this,
                            getString(R.string.upload_failed) + ": " + e);
                }
            });
        }, "xd-file-up").start();
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
            name = p == null ? "upload" : p;
        }
        return name;
    }

    // ── filters UI ──────────────────────────────────────────────────────

    private void bindSpinners() {
        List<String> classes = new ArrayList<>();
        classes.add(getString(R.string.all_classes));
        for (String c : XDState.CLASSES) classes.add(c);
        Spinner cs = findViewById(R.id.files_class);
        cs.setAdapter(makeAdapter(classes));
        // the student's Profile class is the DEFAULT (still changeable)
        if (st.klass != null && !st.klass.isEmpty()) {
            int ix = classes.indexOf(st.klass);
            if (ix > 0) cs.setSelection(ix);
        }
        cs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v,
                                                 int pos, long id) {
                qClass = pos == 0 ? "" : classes.get(pos);
                if (!all.isEmpty()) applyFilters();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        List<String> subjects = new ArrayList<>();
        subjects.add(getString(R.string.all_subjects));
        for (String s : XDState.SUBJECTS) subjects.add(s);
        Spinner ss = findViewById(R.id.files_subject);
        ss.setAdapter(makeAdapter(subjects));
        ss.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v,
                                                 int pos, long id) {
                qSubject = pos == 0 ? "" : subjects.get(pos);
                if (!all.isEmpty()) applyFilters();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        EditText search = findViewById(R.id.files_search);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a,
                                                    int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a,
                                                int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                qText = s.toString().trim();
                if (!all.isEmpty()) applyFilters();
            }
        });
    }

    private void bindChips() {
        int ids[] = {R.id.chip_all, R.id.chip_pdf, R.id.chip_docs,
                R.id.chip_images, R.id.chip_video};
        String kinds[] = {"all", "pdf", "docs", "image", "video"};
        for (int i = 0; i < ids.length; i++) {
            chips[i] = findViewById(ids[i]);
            final String k = kinds[i];
            chips[i].setOnClickListener(v -> setKind(k));
        }
        setKind("all");
    }

    private void setKind(String k) {
        qKind = k;
        for (TextView t : chips) {
            boolean on = t == chipFor(k);
            t.setBackground(getResources().getDrawable(
                    on ? R.drawable.chip_on : R.drawable.chip_off));
            t.setTextColor(Fx.color(this, on
                    ? R.color.home_role_ink : R.color.home_muted));
        }
        if (!all.isEmpty()) applyFilters();
    }

    private TextView chipFor(String k) {
        int ids[] = {R.id.chip_all, R.id.chip_pdf, R.id.chip_docs,
                R.id.chip_images, R.id.chip_video};
        String kinds[] = {"all", "pdf", "docs", "image", "video"};
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i].equals(k)) return findViewById(ids[i]);
        }
        return chips[0];
    }

    // ── adapter ─────────────────────────────────────────────────────────

    private class FilesAdapter extends BaseAdapter {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_FILE = 1;

        private final LayoutInflater inf;

        FilesAdapter() {
            inf = LayoutInflater.from(FilesActivity.this);
        }

        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int pos) { return rows.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override public int getViewTypeCount() { return 2; }

        @Override
        public int getItemViewType(int pos) {
            return rows.get(pos) instanceof JSONObject
                    ? TYPE_FILE : TYPE_HEADER;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            if (getItemViewType(pos) == TYPE_HEADER) {
                if (convert == null) {
                    convert = inf.inflate(R.layout.item_file_header,
                            parent, false);
                }
                TextView label = convert.findViewById(R.id.section_label);
                label.setText(String.valueOf(rows.get(pos)));
                return convert;
            }

            if (convert == null) {
                convert = inf.inflate(R.layout.item_file, parent, false);
            }
            JSONObject o = (JSONObject) rows.get(pos);
            JSONObject m = Drive.meta(o);

            String mime = o.optString("mimeType", "");
            String rawName = o.optString("name", "");
            // v1.1.4: students see the friendly topic-based display name
            // (owner order — the raw file name is a teacher concern).
            String disp = m.optString("disp", "");
            String name = !st.teacherPower() && !disp.isEmpty()
                    ? disp : rawName;
            String kind = Ui.kind(mime, rawName);

            ImageView icon = convert.findViewById(R.id.file_icon);
            icon.setImageResource(Ui.iconFor(kind));
            icon.setColorFilter(Ui.tintFor(FilesActivity.this, kind));

            TextView n = convert.findViewById(R.id.file_name);
            n.setText(name);

            TextView meta = convert.findViewById(R.id.file_meta);
            StringBuilder mb = new StringBuilder();
            String cls = m.optString("cls", "");
            String sub = m.optString("sub", "");
            String chp = m.optString("chp", "");
            if (!cls.isEmpty()) mb.append(cls);
            if (!sub.isEmpty()) {
                if (mb.length() > 0) mb.append("  ·  ");
                mb.append(sub);
            }
            meta.setText(mb.toString());
            meta.setVisibility(mb.length() > 0 ? View.VISIBLE : View.GONE);

            TextView sub2 = convert.findViewById(R.id.file_sub);
            StringBuilder sb = new StringBuilder();
            if (!chp.isEmpty()) {
                sb.append(chp).append("  ·  ");
            }
            sb.append(Ui.size(o.optLong("size", 0))).append("  ·  ")
              .append(Ui.dateOf(o.optString("modifiedTime", "")));
            sub2.setText(sb.toString());

            // thin separator between files of the same group
            View sep = convert.findViewById(R.id.file_sep);
            boolean last = pos == rows.size() - 1
                    || !(rows.get(pos + 1) instanceof JSONObject);
            if (sep != null) sep.setVisibility(last ? View.GONE
                    : View.VISIBLE);
            return convert;
        }
    }
}
