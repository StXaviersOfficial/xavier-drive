package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PROFILE tab (v1.1.2) — the account screen, synced with the website.
 *
 * The identity header shows exactly what the website shows: display
 * name, email and rank on the left, the account photo on the right with
 * its own edit button (name + photo edits go to the same Firebase
 * profile the website edits — both clients always agree). Below:
 * "My class" (students only — it becomes the default class in
 * Timetable, Logbook, Live, Notices and Files), the School section
 * (school screen, the official website, the office email), sharing,
 * settings, sign-out and legal. No version line (owner order).
 */
public class ProfileActivity extends XdActivity {

    private static final int PICK_PHOTO = 45;

    private static final String SCHOOL_SITE = "https://www.stxaviers.org";
    private static final String SCHOOL_EMAIL = "helpdesk@stxaviers.org";

    private final Handler h = new Handler(Looper.getMainLooper());
    private XDState st;
    private LinearLayout classRow;
    private TextView initial;
    private ImageView photo;
    private Bitmap pendingPhoto;     // picked in the edit dialog
    private ImageView dialogPreview; // live preview inside the edit dialog
    private TextView dialogPreviewInitial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);
        st = XDState.get(this);
        classRow = findViewById(R.id.profile_classes);
        initial = findViewById(R.id.profile_initial);
        photo = findViewById(R.id.profile_photo);

        bindIdentity();
        bindClassChips();
        bindActions();

        TabNav.wire(this, TabNav.TAB_PROFILE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindIdentity();
        // live sync: whatever the website shows (name or photo edits),
        // the app reflects within a moment of opening this tab
        ProfileSync.fetch(this, changed -> {
            if (isFinishing() || !changed) return;
            bindIdentity();
        });
    }

    private void bindIdentity() {
        TextView name = findViewById(R.id.profile_name);
        TextView email = findViewById(R.id.profile_email);
        TextView role = findViewById(R.id.profile_role);

        name.setText(st.firstName());
        email.setText(st.email == null ? "" : st.email);

        // the DERIVED rank — isDeveloper > isAdmin > raw role
        role.setText(st.rankLabel());
        role.setVisibility(View.VISIBLE);

        // TRUE circular crop (owner order v1.2.0)
        Ui.circle(findViewById(R.id.profile_photo_ring));

        Bitmap bmp = ProfileSync.photo();
        if (bmp != null) {
            photo.setImageBitmap(bmp);
            photo.setVisibility(View.VISIBLE);
            initial.setVisibility(View.GONE);
        } else {
            photo.setVisibility(View.GONE);
            initial.setVisibility(View.VISIBLE);
            initial.setText(st.firstName().substring(0, 1)
                    .toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** Class selector: horizontal chips, selected = tonal accent.
     *  v1.2.0: shown for EVERY rank (owner order — staff pick the class
     *  they work with, and it drives every tool's default). */
    private void bindClassChips() {
        classRow.removeAllViews();
        addChip(null);
        for (String c : XDState.CLASSES) addChip(c);
        highlight();
    }

    private void addChip(final String cls) {
        TextView chip = new TextView(this);
        chip.setText(cls == null ? getString(R.string.all_classes) : cls);
        chip.setTextSize(13);
        chip.setTypeface(Typefaces.interMedium(this));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            st.setClass(this, cls == null ? "" : cls);
            highlight();
            Ui.toast(this, cls == null
                    ? getString(R.string.all_classes)
                    : cls);
        });
        chip.setTag(cls == null ? "" : cls);
        classRow.addView(chip);
    }

    private void highlight() {
        String sel = st.klass == null ? "" : st.klass;
        for (int i = 0; i < classRow.getChildCount(); i++) {
            TextView chip = (TextView) classRow.getChildAt(i);
            boolean on = sel.equals(chip.getTag());
            chip.setBackground(getResources().getDrawable(
                    on ? R.drawable.chip_on : R.drawable.chip_off));
            chip.setTextColor(Fx.color(this, on
                    ? R.color.home_role_ink : R.color.home_muted));
        }
    }

    private void bindActions() {
        // edit identity — the Edit profile BUTTON (v1.2.0: the pencil on
        // the photo is gone; this row is the one door to the editor)
        findViewById(R.id.profile_edit_btn).setOnClickListener(v ->
                showEditDialog());

        findViewById(R.id.profile_school).setOnClickListener(v ->
                startActivitySafe(SchoolActivity.class));

        findViewById(R.id.profile_website).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(SCHOOL_SITE)));
            } catch (Throwable t) {
                Ui.toast(this, getString(R.string.no_viewer));
            }
        });

        findViewById(R.id.profile_school_email).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_SENDTO,
                        Uri.parse("mailto:" + SCHOOL_EMAIL));
                i.putExtra(Intent.EXTRA_SUBJECT,
                        "XavierDrive enquiry");
                startActivity(i);
            } catch (Throwable t) {
                Ui.toast(this, getString(R.string.no_viewer));
            }
        });

        findViewById(R.id.profile_share).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT,
                        "XavierDrive — St. Xavier's Jr./Sr. School's app: "
                                + GoogleAuth.SITE_URL);
                startActivity(Intent.createChooser(i,
                        getString(R.string.share_app)));
            } catch (Throwable ignored) {}
        });

        findViewById(R.id.profile_signout).setOnClickListener(v ->
                new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                        .setMessage(R.string.sign_out_confirm)
                        .setPositiveButton(R.string.sign_out, (d, w) ->
                                signOut())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());

        findViewById(R.id.profile_terms).setOnClickListener(v ->
                openLegal(GoogleAuth.SITE_URL + "/terms.html",
                        getString(R.string.legal_terms)));

        findViewById(R.id.profile_privacy).setOnClickListener(v ->
                openLegal(GoogleAuth.SITE_URL + "/privacy.html",
                        getString(R.string.legal_privacy)));

        findViewById(R.id.profile_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    // ── edit profile (name + photo → the website's Firebase profile) ────

    private void showEditDialog() {
        pendingPhoto = null;
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);

        final FrameLayout previewWrap = new FrameLayout(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                (int) (84 * dp), (int) (84 * dp));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = (int) (6 * dp);
        previewWrap.setLayoutParams(pp);

        final FrameLayout ring = new FrameLayout(this);
        ring.setLayoutParams(new FrameLayout.LayoutParams(
                (int) (78 * dp), (int) (78 * dp), Gravity.CENTER));
        ring.setBackground(getResources()
                .getDrawable(R.drawable.home_avatar_bg));
        Ui.circle(ring);
        final ImageView preview = new ImageView(this);
        preview.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final TextView previewInitial = new TextView(this);
        previewInitial.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        previewInitial.setGravity(Gravity.CENTER);
        previewInitial.setTextSize(30);
        previewInitial.setTypeface(Typefaces.outfitSemi(this));
        previewInitial.setTextColor(Fx.color(this, R.color.home_hero_ink));
        previewInitial.setText(st.firstName().substring(0, 1)
                .toUpperCase(java.util.Locale.ROOT));
        Bitmap cur = ProfileSync.photo();
        if (cur != null) {
            preview.setImageBitmap(cur);
            previewInitial.setVisibility(View.GONE);
        } else {
            preview.setVisibility(View.GONE);
        }
        ring.addView(preview);
        ring.addView(previewInitial);
        previewWrap.addView(ring);
        box.addView(previewWrap);
        dialogPreview = preview;
        dialogPreviewInitial = previewInitial;

        final EditText name = new EditText(this);
        name.setText(st.name == null ? "" : st.name);
        name.setHint(R.string.profile_edit_name);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = (int) (12 * dp);
        name.setLayoutParams(np);
        name.setTextSize(15);
        box.addView(name);

        TextView pick = new TextView(this);
        pick.setText(R.string.profile_change_photo);
        pick.setTextSize(13.5f);
        pick.setTypeface(Typefaces.interMedium(this));
        pick.setTextColor(Fx.color(this, R.color.home_brand));
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(0, (int) (12 * dp), 0, (int) (6 * dp));
        pick.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, PICK_PHOTO);
            } catch (Throwable t) {
                Ui.toast(this, getString(R.string.error_load));
            }
        });
        box.addView(pick);

        currentDialog = new AlertDialog.Builder(this,
                R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.profile_edit)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (pendingPhoto == null
                            && (n.isEmpty() || n.equals(st.name))) {
                        return;   // nothing changed
                    }
                    saveProfile(n, pendingPhoto);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private android.app.Dialog currentDialog;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_PHOTO || res != RESULT_OK || data == null
                || data.getData() == null) return;
        try {
            Bitmap in = BitmapFactory.decodeStream(
                    getContentResolver().openInputStream(data.getData()));
            if (in == null) throw new Exception("no image");
            pendingPhoto = ProfileSync.normalize(in);
            // show the chosen photo inside the open edit dialog
            if (dialogPreview != null) {
                dialogPreview.setImageBitmap(pendingPhoto);
                dialogPreview.setVisibility(View.VISIBLE);
            }
            if (dialogPreviewInitial != null) {
                dialogPreviewInitial.setVisibility(View.GONE);
            }
            Ui.toast(this, getString(R.string.profile_photo_picked));
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.error_load));
        }
    }

    private void saveProfile(final String name, final Bitmap bmp) {
        final ProgressDialog pd = ProgressDialog.show(this, "",
                getString(R.string.uploading), true);
        new Thread(() -> {
            boolean ok = ProfileSync.push(this, name, bmp);
            h.post(() -> {
                try { pd.dismiss(); } catch (Throwable ignored) {}
                if (ok) {
                    Ui.toast(ProfileActivity.this,
                            getString(R.string.profile_saved));
                    bindIdentity();
                } else {
                    Ui.toast(ProfileActivity.this,
                            getString(R.string.profile_save_failed));
                }
            });
        }, "xd-profile-save").start();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void startActivitySafe(Class<?> cls) {
        try {
            startActivity(new Intent(this, cls));
        } catch (Throwable t) {
            Ui.toast(this, String.valueOf(t));
        }
    }

    private void openLegal(String url, String title) {
        try {
            Intent i = new Intent(this, LegalActivity.class);
            i.putExtra(LegalActivity.EXTRA_URL, url);
            i.putExtra(LegalActivity.EXTRA_TITLE, title);
            startActivity(i);
        } catch (Throwable t) {
            Ui.toast(this, String.valueOf(t));
        }
    }

    /** Server-side sign-out + full local wipe + back to the login page. */
    private void signOut() {
        new Thread(() -> {
            try { ApiClient.request("GET", "/logout"); }
            catch (Throwable ignored) {}
            try {
                android.webkit.CookieManager cm =
                        android.webkit.CookieManager.getInstance();
                cm.setCookie(GoogleAuth.WORKER_URL,
                        GoogleAuth.SESSION_COOKIE + "=; Max-Age=0");
                cm.removeSessionCookies(null);
                cm.flush();
            } catch (Throwable ignored) {}
            h.post(() -> {
                XDState.clear(this);
                ProfileSync.clear();
                // per-user local data: AI chat cache + notices seen-marker
                try {
                    getSharedPreferences("xd_ai", MODE_PRIVATE)
                            .edit().clear().apply();
                } catch (Throwable ignored) {}
                try {
                    getSharedPreferences("xd_notices", MODE_PRIVATE)
                            .edit().clear().apply();
                } catch (Throwable ignored) {}
                Intent i = new Intent(this, LoginActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            });
        }, "xd-signout").start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
