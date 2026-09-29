package com.stxaviers.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * AI SETTINGS (v1.2.0) — a REAL screen, not a popup (owner order).
 *
 * Voice: the website's six Orpheus voices, each with its own PLAY
 * preview button (exactly like the website's AI settings).
 * Appearance: message font size + family, and the chat accent colour.
 * Memory: what the AI remembers about this account (Claude/ChatGPT-style,
 * server-side at /api/ai/memory) + custom instructions.
 * Chats: export every conversation as a ZIP into Downloads, and delete
 * all chats — teachers, admins and developers only, never students.
 * The cross at the top right exits.
 */
public class AiSettingsActivity extends XdActivity {

    public static final String PREFS = "xd_ai";
    public static final String KEY_FONT_SIZE = "ai_font_size";   // sp
    public static final String KEY_FONT = "ai_font";            // key below
    public static final String KEY_COLOR = "ai_color";          // #hex

    public static final float DEFAULT_FONT_SIZE = 14.5f;
    public static final String DEFAULT_COLOR = "#2f6bff";

    /** Message font families the app carries. */
    public static final String[] FONTS = {"inter", "outfit", "serif", "mono"};
    public static final String[] FONT_LABELS = {"Inter", "Outfit",
            "Serif", "Monospace"};

    /** Chat accents (the AI screen's send button + active states). */
    public static final String[] COLORS = {
            "#2f6bff", "#0ba360", "#8a46f8", "#f07a1f",
            "#e0457b", "#00a3bf"
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private XDState st;

    private String voice;
    private LinearLayout memList;
    private TextView memState;
    private EditText instructions;
    private final List<JSONObject> memories = new ArrayList<>();
    private boolean instructionsDirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_settings);
        st = XDState.get(this);
        voice = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("voice", "austin");

        findViewById(R.id.ais_back).setOnClickListener(v -> finish());
        findViewById(R.id.ais_cross).setOnClickListener(v -> finish());

        buildVoiceSection();
        buildFontSection();
        buildColorSection();
        buildMemorySection();
        buildChatsSection();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout scrollBody() {
        return (LinearLayout) findViewById(R.id.ais_body);
    }

    /** Section header (small overline). */
    private TextView header(int label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTypeface(Typefaces.interMedium(this));
        t.setLetterSpacing(0.08f);
        t.setTextColor(Fx.color(this, R.color.home_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(26);
        lp.bottomMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private View card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getResources().getDrawable(R.drawable.row_card));
        c.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        c.setLayoutParams(lp);
        return c;
    }

    // ── voice (the website's six, with previews) ────────────────────────

    private void buildVoiceSection() {
        scrollBody().addView(header(R.string.ai_voice_title));

        LinearLayout box = (LinearLayout) card();
        for (int i = 0; i < TtsPlayer.VOICES.length; i++) {
            final String v = TtsPlayer.VOICES[i];
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));

            // gender-tinted avatar dot, like the website's list
            boolean male = i < 3;
            FrameLayout av = new FrameLayout(this);
            av.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
            av.setBackground(getResources().getDrawable(R.drawable.ai_icon_circle));
            TextView avT = new TextView(this);
            avT.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            avT.setGravity(Gravity.CENTER);
            avT.setText(TtsPlayer.VOICE_LABELS[i].substring(0, 1));
            avT.setTextSize(14);
            avT.setTypeface(Typefaces.outfitSemi(this));
            avT.setTextColor(Fx.color(this, male
                    ? R.color.home_brand : R.color.home_ai_end));
            av.addView(avT);
            row.addView(av);

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = dp(12);
            text.setLayoutParams(tp);
            String[] parts = TtsPlayer.VOICE_LABELS[i].split(" — ", 2);
            TextView name = new TextView(this);
            name.setText(parts[0]);
            name.setTextSize(14.5f);
            name.setTypeface(Typefaces.interMedium(this));
            name.setTextColor(Fx.color(this, v.equals(voice)
                    ? R.color.home_brand : R.color.home_ink));
            text.addView(name);
            TextView desc = new TextView(this);
            desc.setText(parts.length > 1 ? parts[1] : "");
            desc.setTextSize(11.5f);
            desc.setTypeface(Typefaces.interRegular(this));
            desc.setTextColor(Fx.color(this, R.color.home_muted));
            text.addView(desc);
            row.addView(text);

            if (v.equals(voice)) {
                ImageView check = new ImageView(this);
                check.setImageResource(R.drawable.ic_check);
                check.setColorFilter(Fx.color(this, R.color.home_brand));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        dp(18), dp(18));
                cp.rightMargin = dp(8);
                check.setLayoutParams(cp);
                row.addView(check);
            }

            // the PLAY preview — hear the voice before choosing it
            TextView play = new TextView(this);
            play.setText(R.string.play);
            play.setTextSize(12);
            play.setTypeface(Typefaces.interMedium(this));
            play.setGravity(Gravity.CENTER);
            play.setTextColor(Fx.color(this, R.color.home_brand));
            play.setBackground(getResources().getDrawable(R.drawable.chip_off));
            play.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            pp.rightMargin = dp(4);
            play.setLayoutParams(pp);
            play.setOnClickListener(x -> {
                play.setText(R.string.loading);
                TtsPlayer.speak(AiSettingsActivity.this,
                        "Hello! This is " + parts[0]
                                + ". Ready to help you learn.",
                        v, new TtsPlayer.State() {
                    @Override public void onState(boolean playing) {
                        if (!playing) play.setText(R.string.play);
                    }
                    @Override public void onError(String message) {
                        play.setText(R.string.play);
                        Ui.toast(AiSettingsActivity.this,
                                getString(R.string.ai_tts_failed));
                    }
                });
            });
            row.addView(play);

            row.setOnClickListener(x -> {
                voice = v;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("voice", v).apply();
                // re-render the section (checks + colours)
                scrollBody().removeAllViews();
                buildVoiceSection();
                buildFontSection();
                buildColorSection();
                buildMemorySection();
                buildChatsSection();
                Ui.toast(AiSettingsActivity.this, parts[0]);
            });

            box.addView(row);
            if (i < TtsPlayer.VOICES.length - 1) {
                View line = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                line.setLayoutParams(lp);
                line.setBackgroundColor(Fx.color(this, R.color.home_hairline));
                box.addView(line);
            }
        }
        scrollBody().addView(box);
    }

    // ── message font (size + family) ────────────────────────────────────

    private void buildFontSection() {
        scrollBody().addView(header(R.string.ai_font_title));

        LinearLayout box = (LinearLayout) card();
        TextView sizeLabel = new TextView(this);
        sizeLabel.setText(R.string.ai_font_size);
        sizeLabel.setTextSize(11.5f);
        sizeLabel.setTypeface(Typefaces.interMedium(this));
        sizeLabel.setTextColor(Fx.color(this, R.color.home_muted));
        sizeLabel.setPadding(0, dp(10), 0, dp(8));
        box.addView(sizeLabel);

        float cur = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE);
        float sizes[] = {13f, 14.5f, 16f, 18f};
        String labels[] = {"S", "M", "L", "XL"};
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setPadding(0, 0, 0, dp(12));
        for (int i = 0; i < sizes.length; i++) {
            final float s = sizes[i];
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(13);
            chip.setTypeface(Typefaces.interMedium(this));
            chip.setGravity(Gravity.CENTER);
            boolean on = Math.abs(cur - s) < 0.1f;
            chip.setBackground(getResources().getDrawable(
                    on ? R.drawable.chip_on : R.drawable.chip_off));
            chip.setTextColor(Fx.color(this, on
                    ? R.color.home_role_ink : R.color.home_muted));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(36), 1f);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(x -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putFloat(KEY_FONT_SIZE, s).apply();
                rebuild();
            });
            sizeRow.addView(chip);
        }
        box.addView(sizeRow);

        TextView famLabel = new TextView(this);
        famLabel.setText(R.string.ai_font_family);
        famLabel.setTextSize(11.5f);
        famLabel.setTypeface(Typefaces.interMedium(this));
        famLabel.setTextColor(Fx.color(this, R.color.home_muted));
        famLabel.setPadding(0, 0, 0, dp(8));
        box.addView(famLabel);

        String curF = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_FONT, "inter");
        LinearLayout famRow = new LinearLayout(this);
        famRow.setOrientation(LinearLayout.HORIZONTAL);
        famRow.setPadding(0, 0, 0, dp(12));
        for (int i = 0; i < FONTS.length; i++) {
            final String f = FONTS[i];
            TextView chip = new TextView(this);
            chip.setText(FONT_LABELS[i]);
            chip.setTextSize(13);
            chip.setTypeface(fontOf(this, f));
            chip.setGravity(Gravity.CENTER);
            boolean on = f.equals(curF);
            chip.setBackground(getResources().getDrawable(
                    on ? R.drawable.chip_on : R.drawable.chip_off));
            chip.setTextColor(Fx.color(this, on
                    ? R.color.home_role_ink : R.color.home_muted));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(36), 1f);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(x -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_FONT, f).apply();
                rebuild();
            });
            famRow.addView(chip);
        }
        box.addView(famRow);
        scrollBody().addView(box);
    }

    public static android.graphics.Typeface fontOf(Context ctx, String key) {
        if ("outfit".equals(key)) return Typefaces.outfitMedium(ctx);
        if ("serif".equals(key)) return android.graphics.Typeface.SERIF;
        if ("mono".equals(key)) return android.graphics.Typeface.MONOSPACE;
        return Typefaces.interRegular(ctx);
    }

    // ── chat colour ─────────────────────────────────────────────────────

    private void buildColorSection() {
        scrollBody().addView(header(R.string.ai_color_title));

        String cur = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_COLOR, DEFAULT_COLOR);
        LinearLayout box = (LinearLayout) card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(14));
        for (final String c : COLORS) {
            FrameLayout sw = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(34), dp(34));
            lp.rightMargin = dp(12);
            sw.setLayoutParams(lp);
            sw.setBackground(getResources()
                    .getDrawable(R.drawable.home_tile_bg));
            View dot = new View(this);
            FrameLayout.LayoutParams dp2 = new FrameLayout.LayoutParams(
                    dp(22), dp(22), Gravity.CENTER);
            dot.setLayoutParams(dp2);
            dot.setBackground(getResources()
                    .getDrawable(R.drawable.home_badge_dot));
            dot.getBackground().setTint(Color.parseColor(c));
            sw.addView(dot);
            if (c.equalsIgnoreCase(cur)) {
                ImageView check = new ImageView(this);
                check.setImageResource(R.drawable.ic_check);
                check.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                check.setPadding(dp(7), dp(7), dp(7), dp(7));
                check.setColorFilter(Color.WHITE);
                sw.addView(check);
            }
            sw.setOnClickListener(x -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_COLOR, c).apply();
                rebuild();
            });
            row.addView(sw);
        }
        box.addView(row);
        scrollBody().addView(box);
    }

    // ── memory (what the AI remembers + custom instructions) ────────────

    private void buildMemorySection() {
        scrollBody().addView(header(R.string.ai_memory_title));

        LinearLayout box = (LinearLayout) card();

        TextView sub = new TextView(this);
        sub.setText(R.string.ai_memory_sub);
        sub.setTextSize(11.5f);
        sub.setTypeface(Typefaces.interRegular(this));
        sub.setTextColor(Fx.color(this, R.color.home_muted));
        sub.setPadding(0, dp(10), 0, dp(4));
        box.addView(sub);

        instructions = new EditText(this);
        instructions.setHint(R.string.ai_custom_instructions);
        instructions.setTextSize(13.5f);
        instructions.setTypeface(Typefaces.interRegular(this));
        instructions.setTextColor(Fx.color(this, R.color.home_ink));
        instructions.setHintTextColor(Fx.color(this, R.color.home_muted));
        instructions.setBackground(getResources()
                .getDrawable(R.drawable.chip_off));
        instructions.setPadding(dp(12), dp(10), dp(12), dp(10));
        instructions.setMinLines(2);
        instructions.setMaxLines(4);
        instructions.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a,
                                                    int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a,
                                                 int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                instructionsDirty = true;
            }
        });
        box.addView(instructions);

        TextView save = new TextView(this);
        save.setText(R.string.save);
        save.setTextSize(13);
        save.setTypeface(Typefaces.interMedium(this));
        save.setGravity(Gravity.CENTER);
        save.setTextColor(Fx.color(this, R.color.home_hero_ink));
        save.setBackground(getResources().getDrawable(R.drawable.btn_primary));
        save.setPadding(dp(16), dp(9), dp(16), dp(9));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(10);
        sp.bottomMargin = dp(12);
        save.setLayoutParams(sp);
        save.setOnClickListener(v -> saveInstructions());
        box.addView(save);

        // saved memories
        memList = new LinearLayout(this);
        memList.setOrientation(LinearLayout.VERTICAL);
        box.addView(memList);
        memState = new TextView(this);
        memState.setText(R.string.ai_memory_loading);
        memState.setTextSize(12);
        memState.setTypeface(Typefaces.interRegular(this));
        memState.setTextColor(Fx.color(this, R.color.home_muted));
        memState.setPadding(0, dp(6), 0, dp(10));
        box.addView(memState);

        scrollBody().addView(box);
        loadMemory();
    }

    private void loadMemory() {
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.request("GET", "/api/ai/memory");
            final List<JSONObject> found = new ArrayList<>();
            final String instr;
            if (r.ok && r.json != null) {
                JSONArray a = r.json.optJSONArray("memories");
                if (a != null) {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject m = a.optJSONObject(i);
                        if (m != null) found.add(m);
                    }
                }
                instr = r.json.optString("instructions", "");
            } else {
                instr = null;
            }
            h.post(() -> {
                if (isFinishing()) return;
                if (instr != null && instructions != null
                        && !instructionsDirty) {
                    instructions.setText(instr);
                }
                memories.clear();
                memories.addAll(found);
                renderMemories(instr == null);
            });
        }, "xd-ai-mem-load").start();
    }

    private void renderMemories(boolean offline) {
        if (memList == null || memState == null) return;
        memList.removeAllViews();
        if (offline) {
            memState.setText(R.string.error_load);
            return;
        }
        if (memories.isEmpty()) {
            memState.setText(R.string.ai_memory_empty);
            return;
        }
        memState.setText(getString(R.string.ai_memory_count,
                memories.size()));
        for (int i = 0; i < memories.size(); i++) {
            final JSONObject m = memories.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(6), dp(8));
            row.setBackground(getResources().getDrawable(R.drawable.chip_off));

            TextView t = new TextView(this);
            t.setText(m.optString("text", ""));
            t.setTextSize(12.5f);
            t.setTypeface(Typefaces.interRegular(this));
            t.setTextColor(Fx.color(this, R.color.home_ink));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            t.setLayoutParams(tp);
            row.addView(t);

            View x = new View(this);
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(
                    dp(16), dp(16));
            xp.leftMargin = dp(8);
            x.setLayoutParams(xp);
            x.setBackground(getResources().getDrawable(R.drawable.ic_close));
            x.setBackgroundTintList(android.content.res.ColorStateList
                    .valueOf(Fx.color(this, R.color.home_muted)));
            x.setOnClickListener(v -> {
                memories.remove(m);
                renderMemories(false);
                pushMemories();
            });
            row.addView(x);

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(6);
            row.setLayoutParams(rp);
            memList.addView(row);
        }

        TextView clear = new TextView(this);
        clear.setText(R.string.ai_memory_clear);
        clear.setTextSize(12.5f);
        clear.setTypeface(Typefaces.interMedium(this));
        clear.setTextColor(Fx.color(this, R.color.danger));
        clear.setPadding(0, dp(10), 0, dp(4));
        clear.setOnClickListener(v -> new AlertDialog.Builder(this,
                R.style.Theme_XavierDrive_Dialog)
                .setMessage(R.string.ai_memory_clear_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    memories.clear();
                    renderMemories(false);
                    pushMemories();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        memList.addView(clear);
    }

    private void saveInstructions() {
        final String text = instructions.getText().toString().trim();
        new Thread(() -> {
            JSONObject body = new JSONObject();
            try {
                body.put("instructions",
                        text.substring(0, Math.min(1500, text.length())));
                body.put("memories", new JSONArray(memories));
            } catch (Throwable ignored) {}
            ApiClient.Resp r = ApiClient.requestJson("POST",
                    "/api/ai/memory", body);
            h.post(() -> Ui.toast(AiSettingsActivity.this, r.ok
                    ? getString(R.string.ai_instructions_saved)
                    : getString(R.string.error_load)));
        }, "xd-ai-mem-save").start();
    }

    private void pushMemories() {
        new Thread(() -> {
            JSONObject body = new JSONObject();
            try {
                body.put("memories", new JSONArray(memories));
                if (instructions != null) {
                    String t = instructions.getText().toString().trim();
                    body.put("instructions",
                            t.substring(0, Math.min(1500, t.length())));
                }
            } catch (Throwable ignored) {}
            ApiClient.requestJson("POST", "/api/ai/memory", body);
        }, "xd-ai-mem-push").start();
    }

    // ── chats (export + delete all) ─────────────────────────────────────

    private void buildChatsSection() {
        scrollBody().addView(header(R.string.ai_chat_manager));

        LinearLayout box = (LinearLayout) card();

        // export — "a cool name", per the owner's order
        LinearLayout export = new LinearLayout(this);
        export.setOrientation(LinearLayout.HORIZONTAL);
        export.setGravity(Gravity.CENTER_VERTICAL);
        export.setPadding(0, dp(12), 0, dp(12));
        ImageView exIc = new ImageView(this);
        exIc.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
        exIc.setImageResource(R.drawable.ic_download);
        exIc.setColorFilter(Fx.color(this, R.color.home_brand));
        export.addView(exIc);
        LinearLayout exText = new LinearLayout(this);
        exText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        exText.setLayoutParams(tp);
        TextView exT = new TextView(this);
        exT.setText(R.string.ai_export_all);
        exT.setTextSize(14);
        exT.setTypeface(Typefaces.interMedium(this));
        exT.setTextColor(Fx.color(this, R.color.home_ink));
        exText.addView(exT);
        TextView exS = new TextView(this);
        exS.setText(R.string.ai_export_all_sub);
        exS.setTextSize(11.5f);
        exS.setTypeface(Typefaces.interRegular(this));
        exS.setTextColor(Fx.color(this, R.color.home_muted));
        exText.addView(exS);
        export.addView(exText);
        export.setOnClickListener(v -> exportAllChats());
        box.addView(export);

        // delete all — teachers, admins and developers only (owner order)
        if (st.teacherPower()) {
            View line = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            line.setLayoutParams(lp);
            line.setBackgroundColor(Fx.color(this, R.color.home_hairline));
            box.addView(line);

            LinearLayout del = new LinearLayout(this);
            del.setOrientation(LinearLayout.HORIZONTAL);
            del.setGravity(Gravity.CENTER_VERTICAL);
            del.setPadding(0, dp(12), 0, dp(12));
            ImageView dIc = new ImageView(this);
            dIc.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(18)));
            dIc.setImageResource(R.drawable.ic_trash);
            dIc.setColorFilter(Fx.color(this, R.color.danger));
            del.addView(dIc);
            TextView dT = new TextView(this);
            LinearLayout.LayoutParams dp3 = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            dp3.leftMargin = dp(12);
            dT.setLayoutParams(dp3);
            dT.setText(R.string.ai_delete_all);
            dT.setTextSize(14);
            dT.setTypeface(Typefaces.interMedium(this));
            dT.setTextColor(Fx.color(this, R.color.danger));
            del.addView(dT);
            del.setOnClickListener(v -> confirmDeleteAll());
            box.addView(del);
        } else {
            TextView note = new TextView(this);
            note.setText(R.string.ai_delete_all_staff);
            note.setTextSize(11.5f);
            note.setTypeface(Typefaces.interRegular(this));
            note.setTextColor(Fx.color(this, R.color.home_muted));
            note.setPadding(0, dp(10), 0, dp(12));
            box.addView(note);
        }
        scrollBody().addView(box);
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setMessage(R.string.ai_delete_all_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    new Thread(() -> {
                        // the Drive truth, exactly like AiActivity's store
                        for (ChatSync.Session s : ChatSync.loadBlocking(
                                AiSettingsActivity.this)) {
                            ChatSync.delete(AiSettingsActivity.this, s, null);
                        }
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .remove("sessions_v3").apply();
                        h.post(() -> Ui.toast(AiSettingsActivity.this,
                                getString(R.string.deleted)));
                    }, "xd-ai-delall").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Zip every conversation and drop it in the device's Downloads.
     *  Merges the Drive truth with the offline cache — an offline-only
     *  chat is still the user's chat and belongs in the export. */
    private void exportAllChats() {
        Ui.toast(this, R.string.exporting);
        new Thread(() -> {
            String err = null;
            String path = null;
            try {
                List<ChatSync.Session> all = ChatSync.loadBlocking(this);
                for (ChatSync.Session c : cachedSessions()) {
                    boolean known = false;
                    for (ChatSync.Session d : all) {
                        if (d.id.equals(c.id)) {
                            known = true;
                            break;
                        }
                    }
                    if (!known && c.history.length() > 0) all.add(c);
                }
                if (all.isEmpty()) {
                    err = getString(R.string.ai_history_none);
                } else {
                    File tmp = new File(getCacheDir(),
                            "xavierdrive_chats_"
                                    + System.currentTimeMillis() + ".zip");
                    ZipOutputStream zos = new ZipOutputStream(
                            new FileOutputStream(tmp));
                    int n = 1;
                    for (ChatSync.Session s : all) {
                        String base = (s.name == null || s.name.isEmpty()
                                ? "chat_" + n : s.name)
                                .replaceAll("[^a-zA-Z0-9_\\- ]", "")
                                .trim();
                        if (base.isEmpty()) base = "chat_" + n;
                        if (base.length() > 40) base = base.substring(0, 40);
                        JSONObject j = new JSONObject();
                        j.put("name", s.name);
                        j.put("created", s.created);
                        j.put("history", s.history);
                        ZipEntry e = new ZipEntry(n + "_" + base + ".json");
                        zos.putNextEntry(e);
                        zos.write(j.toString().getBytes(
                                StandardCharsets.UTF_8));
                        zos.closeEntry();
                        n++;
                    }
                    zos.close();

                    String fileName = "XavierDrive_Chats_"
                            + Ui.now("yyyyMMdd_HHmm") + ".zip";
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                        cv.put(MediaStore.Downloads.MIME_TYPE,
                                "application/zip");
                        cv.put(MediaStore.Downloads.IS_PENDING, 1);
                        Uri uri = getContentResolver().insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (uri != null) {
                            OutputStream os = getContentResolver()
                                    .openOutputStream(uri);
                            copy(tmp, os);
                            cv.clear();
                            cv.put(MediaStore.Downloads.IS_PENDING, 0);
                            getContentResolver().update(uri, cv, null, null);
                            path = "Downloads/" + fileName;
                        }
                    }
                    if (path == null) {
                        // older devices / fallback: the app's own folder
                        File dir = getExternalFilesDir(null);
                        if (dir == null) dir = getFilesDir();
                        File out = new File(dir, fileName);
                        copy(tmp, new FileOutputStream(out));
                        path = out.getAbsolutePath();
                    }
                    tmp.delete();
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            final String e = err, p = path;
            h.post(() -> {
                if (isFinishing()) return;
                if (e != null) {
                    Ui.toast(AiSettingsActivity.this,
                            getString(R.string.upload_failed) + ": " + e);
                } else {
                    Ui.toast(AiSettingsActivity.this,
                            getString(R.string.ai_export_done) + " " + p);
                }
            });
        }, "xd-ai-export").start();
    }

    /** The offline cache AiActivity keeps (sessions_v3 in xd_ai prefs). */
    private List<ChatSync.Session> cachedSessions() {
        List<ChatSync.Session> out = new ArrayList<>();
        try {
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("sessions_v3", null);
            if (raw == null) return out;
            JSONObject o = new JSONObject(raw);
            JSONArray a = o.optJSONArray("sessions");
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null) continue;
                ChatSync.Session s = new ChatSync.Session();
                s.id = j.optString("id", "");
                s.name = j.optString("title", "");
                s.created = j.optLong("ts", 0);
                s.history = j.optJSONArray("hist");
                if (s.history == null) s.history = new JSONArray();
                if (!s.id.isEmpty() && s.history.length() > 0) {
                    out.add(s);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void copy(File in, OutputStream os) throws Exception {
        java.io.FileInputStream fi = new java.io.FileInputStream(in);
        byte[] buf = new byte[16384];
        int n;
        while ((n = fi.read(buf)) > 0) os.write(buf, 0, n);
        fi.close();
        os.close();
    }

    private void rebuild() {
        scrollBody().removeAllViews();
        buildVoiceSection();
        buildFontSection();
        buildColorSection();
        buildMemorySection();
        buildChatsSection();
    }
}
