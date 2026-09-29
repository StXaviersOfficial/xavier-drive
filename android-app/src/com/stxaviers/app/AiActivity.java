package com.stxaviers.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * XAVIERDRIVE AI — v1.2.0 — the full-screen experience.
 *
 *  • App bar shows THE CHAT'S OWN TITLE — "Untitled" until the AI names
 *    it after the first reply (/api/chat/title, the website's engine).
 *  • The drawer: search bar, compact new-chat + settings buttons,
 *    SQUARE chat rows, and a 3-dot menu per chat — Rename · Pin ·
 *    Summarize · Delete (teachers and above only, owner order).
 *  • Reopening the app lands on the LAST SELECTED chat, never a blank
 *    new one; the drawer refreshes itself the moment Drive answers.
 *  • Composer: send is GREY when the box is empty and BLUE when ready;
 *    the mic is plain by default and turns blue with a waveform while
 *    listening (server Whisper on devices without a recognizer).
 *  • Responses are selectable; actions are icon-only (no pills).
 *  • Attachments up to 15 MB; Generate image is admins + developers.
 */
public class AiActivity extends XdActivity {

    // ── request codes ───────────────────────────────────────────────────
    private static final int PICK_PHOTOS = 51;
    private static final int PICK_FILES = 52;
    private static final int TAKE_PHOTO = 53;
    private static final int REQ_MIC = 60;

    // ── local cache (offline fallback for the Drive store) ─────────────
    private static final String PREFS = "xd_ai";
    private static final String KEY_CACHE = "sessions_v3";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_PINS = "pins";
    private static final int MAX_SESSIONS = 30;

    /** Owner order v1.2.0: attachments may be at most 15 MB. */
    private static final long MAX_ATT_BYTES = 15L * 1024 * 1024;

    private static final String[] THINKING = {
            "Reading your question…", "Researching the web…",
            "Reviewing sources…", "Writing your answer…"
    };

    // ── views ───────────────────────────────────────────────────────────
    private LinearLayout msgs, drawerList, attachRow;
    private ScrollView scroll;
    private EditText input, drawerSearch;
    private TextView titleView;
    private View send, sendIcon, emptyState, typingRow;
    private View drawer, drawerScrim, drawerPanel;
    private View attachStrip, modeStrip, modeOff;
    private TextView modeChip;
    private ImageView micIcon;
    private View micBtn;

    private final Handler h = new Handler(Looper.getMainLooper());
    private XDState st;

    // ── state ───────────────────────────────────────────────────────────
    private final List<ChatSync.Session> sessions = new ArrayList<>();
    private String currentId = "";
    private boolean pendingNew;   // a just-created chat not yet saved
    private boolean busy;
    private boolean deepResearch;
    private boolean imageMode;
    private String voice;
    private VoiceInput voiceInput;
    private boolean listening;
    private final Set<String> pins = new HashSet<>();
    private String lastQuery = "";

    /** True once the AI screen has opened in THIS app process. The FIRST
     *  open after launching the app always starts a NEW chat (owner
     *  order v1.1.5 — "when reopened the app and clicked on the AI icon,
     *  it must start from the new chat"); later visits within the same
     *  app run restore the conversation the user was on. */
    private static boolean openedInProcess;

    /** Chat ids whose workspace (artifacts) listing was already pulled. */
    private final Set<String> wsLoaded = new HashSet<>();

    /** One picked attachment (image bitmap, or extracted text). */
    private static final class Att {
        String name;
        boolean image;
        Bitmap bmp;       // images (already normalized)
        String text;      // documents
    }

    private final List<Att> attach = new ArrayList<>();

    /** One AI-delivered ```file block (name + mime + content). */
    private static final class FileBlock {
        String name;
        String mime;
        String content;
    }

    /** One AI-delivered ```pdffile block (server renders the PDF). */
    private static final class PdfBlock {
        String name;
        String title;
        String prompt;
    }

    private final Runnable thinkCycle = new Runnable() {
        private int stage = 0;
        @Override public void run() {
            if (typingRow == null) return;
            TextView t = typingRow.findViewById(R.id.bubble_text);
            if (t != null && stage < THINKING.length) {
                t.setText(THINKING[stage]);
                stage = Math.min(stage + 1, THINKING.length - 1);
            }
            h.postDelayed(this, 3500L);
        }
    };

    // ═══════════════════════════════════════════════ lifecycle ═══════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai);
        st = XDState.get(this);

        voice = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_VOICE, "austin");
        for (String p : getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_PINS, "").split(",")) {
            if (p != null && !p.trim().isEmpty()) pins.add(p.trim());
        }

        bindViews();
        loadChats(!openedInProcess);
        openedInProcess = true;
    }

    private void bindViews() {
        msgs = findViewById(R.id.ai_msgs);
        scroll = findViewById(R.id.ai_scroll);
        input = findViewById(R.id.ai_input);
        send = findViewById(R.id.ai_send);
        sendIcon = findViewById(R.id.ai_send_icon);
        emptyState = findViewById(R.id.ai_empty);
        titleView = findViewById(R.id.ai_title);

        drawer = findViewById(R.id.ai_drawer);
        drawerScrim = findViewById(R.id.drawer_scrim);
        drawerPanel = findViewById(R.id.drawer_panel);
        drawerList = findViewById(R.id.drawer_list);
        drawerSearch = findViewById(R.id.drawer_search);

        attachStrip = findViewById(R.id.ai_attach_strip);
        attachRow = findViewById(R.id.ai_attach_row);
        modeStrip = findViewById(R.id.ai_research_strip);
        modeChip = findViewById(R.id.ai_research_chip);
        modeOff = findViewById(R.id.ai_research_off);
        micIcon = findViewById(R.id.ai_mic_icon);
        micBtn = findViewById(R.id.ai_mic);

        findViewById(R.id.ai_close).setOnClickListener(v -> exitToHome());
        findViewById(R.id.ai_menu).setOnClickListener(v -> openDrawer());
        drawerScrim.setOnClickListener(v -> closeDrawer());
        findViewById(R.id.drawer_new).setOnClickListener(v -> {
            closeDrawer();
            newChat();
        });
        findViewById(R.id.drawer_settings).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, AiSettingsActivity.class));
        });

        send.setOnClickListener(v -> send());
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || (ev != null && ev.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && ev.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                send();
                return true;
            }
            return false;
        });

        // the send button lights the moment there is something to send
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a,
                                                    int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a,
                                                 int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                refreshSendUi();
            }
        });
        refreshSendUi();

        findViewById(R.id.ai_plus).setOnClickListener(v -> showPlusMenu());
        findViewById(R.id.ai_mic).setOnClickListener(v -> toggleMic());
        modeOff.setOnClickListener(v -> {
            deepResearch = false;
            imageMode = false;
            input.setHint(R.string.ai_hint);
            refreshModeStrip();
            refreshSendUi();
        });

        // chat search — matches titles AND message contents
        drawerSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a,
                                                    int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a,
                                                 int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                lastQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                rebuildDrawer();
            }
        });

        int sugIds[] = {R.id.ai_sug1, R.id.ai_sug2, R.id.ai_sug3};
        for (int id : sugIds) {
            TextView sug = findViewById(id);
            if (sug != null) {
                sug.setTypeface(Typefaces.interMedium(this));
                sug.setOnClickListener(v -> {
                    input.setText(((TextView) v).getText());
                    input.setSelection(input.getText().length());
                    focusInput();
                });
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // settings (font / colour / voice) may have changed
        renderSession();
        refreshSendUi();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(thinkCycle);
        h.removeCallbacksAndMessages(null);
        TtsPlayer.stop();
        if (voiceInput != null) voiceInput.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
            closeDrawer();
            return;
        }
        super.onBackPressed();
        overridePendingTransition(0, R.anim.ai_out);
    }

    /** The cross — leave the AI and land on the Home tab. */
    private void exitToHome() {
        try {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable ignored) {}
        finish();
        overridePendingTransition(0, R.anim.ai_out);
    }

    // ═════════════════════════════════════════ session store ═══════════

    /** Load the synced store: Drive (website truth) merged with the local
     *  offline cache; local-only conversations are pushed up to Drive.
     *  freshOpen=true (first AI open after the APP was launched) starts
     *  a NEW chat instead of restoring the last one (owner order v1.1.5). */
    private void loadChats(final boolean freshOpen) {
        loadCache();
        if (freshOpen) {
            currentId = ChatSync.newId();
            pendingNew = true;
        }
        // v1.1.5: heal the store — merges any doubled chat folders so one
        // chat can never show twice (silent, fire-and-forget)
        ChatSync.dedupSweep(this);
        renderSession();
        ChatSync.load(this, (loaded, err) -> {
            if (isFinishing()) return;
            if (err != null && loaded.isEmpty()) {
                // offline: keep the cached copy, say so once
                if (!sessions.isEmpty()) {
                    Ui.toast(this, getString(R.string.ai_offline_chats));
                }
                ensureCurrent();
                renderSession();
                return;
            }
            mergeSessions(loaded);
            ensureCurrent();
            persistCache();
            renderSession();
            // the drawer refreshes itself even when it is already open —
            // chats must NEVER wait for a manual interaction (owner order)
            if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
                rebuildDrawer();
            }
            // push any conversation that exists only on this device
            for (ChatSync.Session s : sessions) {
                if (s.dirty) {
                    ChatSync.save(this, s, null);
                }
            }
        });
    }

    /** Drive truth wins; local-only ids are kept (and marked dirty). */
    private void mergeSessions(List<ChatSync.Session> drive) {
        List<ChatSync.Session> merged = new ArrayList<>(drive);
        for (ChatSync.Session local : sessions) {
            boolean known = false;
            for (ChatSync.Session d : drive) {
                if (d.id.equals(local.id)) {
                    known = true;
                    break;
                }
            }
            if (!known && local.history.length() > 0) {
                local.dirty = true;
                merged.add(local);
            }
        }
        sessions.clear();
        sessions.addAll(merged);
        sortSessions();
    }

    /** Pinned chats first, then newest. */
    private void sortSessions() {
        java.util.Collections.sort(sessions, (a, b) -> {
            boolean pa = pins.contains(a.id), pb = pins.contains(b.id);
            if (pa != pb) return pa ? -1 : 1;
            return Long.compare(b.created, a.created);
        });
    }

    private void ensureCurrent() {
        // keep the user's context: the last selected chat stays selected,
        // and a brand-new empty chat is never clobbered by a late Drive
        // load (the v1.1.2 "opens a new chat every time" family of bugs)
        if (currentId != null && !currentId.isEmpty()) {
            if (findSession(currentId) != null) return;
            if (pendingNew) return;
        }
        if (!sessions.isEmpty()) {
            currentId = sessions.get(0).id;
        } else {
            currentId = ChatSync.newId();
        }
    }

    private ChatSync.Session findSession(String id) {
        for (ChatSync.Session s : sessions) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    private ChatSync.Session ensureSession(String id) {
        ChatSync.Session s = findSession(id);
        if (s == null) {
            s = new ChatSync.Session();
            s.id = id;
            s.name = "";
            s.created = System.currentTimeMillis();
            sessions.add(s);
            sortSessions();
        }
        return s;
    }

    private JSONArray histOf(String id) {
        ChatSync.Session s = findSession(id);
        return s == null ? new JSONArray() : s.history;
    }

    private void addMessage(String id, String role, String text,
                            String time, String imgPath) {
        ChatSync.Session s = ensureSession(id);
        pendingNew = false;   // the conversation is real now
        try {
            JSONObject m = new JSONObject();
            m.put("role", role);
            m.put("text", text);
            m.put("time", time);
            if (imgPath != null) m.put("img", imgPath);
            s.history.put(m);
            if ((s.name == null || s.name.isEmpty())
                    && "user".equals(role)) {
                s.name = ChatSync.titleOf(s.history);
            }
            s.created = System.currentTimeMillis();
        } catch (Throwable ignored) {}
    }

    private void newChat() {
        currentId = ChatSync.newId();
        pendingNew = true;
        // every mode resets — a stuck Generate-image state made plain
        // prompts generate images (owner-reported glitch, v1.2.0 fix)
        deepResearch = false;
        imageMode = false;
        input.setHint(R.string.ai_hint);
        refreshModeStrip();
        attach.clear();
        refreshAttachStrip();
        persistCache();
        renderSession();
        refreshSendUi();
    }

    // ── local cache (offline fallback) ─────────────────────────────────

    private void persistCache() {
        try {
            trimSessions();
            JSONObject o = new JSONObject();
            o.put("current", currentId);
            JSONArray a = new JSONArray();
            for (ChatSync.Session s : sessions) {
                JSONObject j = new JSONObject();
                j.put("id", s.id);
                j.put("title", s.name == null ? "" : s.name);
                j.put("ts", s.created);
                j.put("hist", s.history);
                a.put(j);
            }
            o.put("sessions", a);
            StringBuilder p = new StringBuilder();
            for (String id : pins) {
                if (p.length() > 0) p.append(',');
                p.append(id);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_CACHE, o.toString())
                    .putString(KEY_PINS, p.toString())
                    .apply();
        } catch (Throwable ignored) {}
    }

    private void loadCache() {
        try {
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_CACHE, null);
            if (raw == null) return;
            JSONObject o = new JSONObject(raw);
            currentId = o.optString("current", "");
            JSONArray a = o.optJSONArray("sessions");
            if (a == null) return;
            sessions.clear();
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
                    sessions.add(s);
                }
            }
            sortSessions();
        } catch (Throwable ignored) {}
    }

    private void trimSessions() {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            ChatSync.Session s = sessions.get(i);
            if (s.history.length() == 0 && !s.id.equals(currentId)) {
                sessions.remove(i);
            }
        }
        while (sessions.size() > MAX_SESSIONS) {
            int oldest = -1;
            long oldestTs = Long.MAX_VALUE;
            for (int i = 0; i < sessions.size(); i++) {
                ChatSync.Session s = sessions.get(i);
                if (s.id.equals(currentId)) continue;
                if (pins.contains(s.id)) continue;
                if (s.created < oldestTs) {
                    oldestTs = s.created;
                    oldest = i;
                }
            }
            if (oldest < 0) break;
            sessions.remove(oldest);
        }
    }

    // ═════════════════════════════════════════════ rendering ═════════════

    /** The chat's own title — "Untitled" until the AI names it. */
    private void updateTitle() {
        ChatSync.Session s = findSession(currentId);
        String name = s == null ? "" : s.name;
        titleView.setText(name == null || name.trim().isEmpty()
                ? getString(R.string.ai_untitled) : name);
    }

    private void renderSession() {
        msgs.removeAllViews();
        JSONArray hist = histOf(currentId);
        for (int i = 0; i < hist.length(); i++) {
            JSONObject m = hist.optJSONObject(i);
            if (m == null) continue;
            appendBubble(m.optString("role", "ai"),
                    m.optString("text", ""),
                    m.optString("time", ""),
                    m.optString("img", null), i, false);
        }
        emptyState.setVisibility(hist.length() == 0
                ? View.VISIBLE : View.GONE);
        updateTitle();
        if (hist.length() > 0) scrollDown();
        // v1.1.5: pull this chat's workspace (artifact names) so the AI
        // knows what it has to work with on the next turn
        loadWorkspace(currentId);
    }

    /** Async-load the artifact names of this chat (once per id). */
    private void loadWorkspace(final String id) {
        if (id == null || id.isEmpty() || wsLoaded.contains(id)) return;
        final ChatSync.Session s = findSession(id);
        if (s == null) return;
        wsLoaded.add(id);
        new Thread(() -> {
            final List<String> names = ChatSync.artifactsOf(this, s);
            h.post(() -> {
                for (String n : names) {
                    if (!s.ws.contains(n)) s.ws.add(n);
                }
            });
        }, "xd-ws-load").start();
    }

    private void appendBubble(String role, CharSequence text, String time,
                              String imgPath, int histIndex, boolean animate) {
        boolean user = "user".equals(role);
        View v = LayoutInflater.from(this).inflate(user ? R.layout.item_msg_user
                        : R.layout.item_msg_ai, msgs, false);
        TextView tv = v.findViewById(R.id.bubble_text);
        // v1.1.5 (owner order): the size + font settings apply to BOTH
        // sides — the AI's replies AND the user's own messages
        tv.setTextSize(composedFontSize());
        tv.setTypeface(AiSettingsActivity.fontOf(this,
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString(AiSettingsActivity.KEY_FONT, "inter")));
        String shown = text == null ? "" : text.toString();
        // v1.1.5: AI file blocks never render as raw code — they become
        // file cards (preview + download) below the reply
        List<Object> blocks = new ArrayList<>();
        if (!user) shown = stripFileBlocks(shown, blocks);
        tv.setText(user ? shown : Ui.formatAi(shown));
        TextView tt = v.findViewById(R.id.bubble_time);
        if (tt != null) tt.setText(time);

        if (!user) {
            // inline image (generated pictures)
            ImageView img = v.findViewById(R.id.bubble_img);
            if (img != null && imgPath != null) {
                File f = new File(imgPath);
                if (f.exists()) {
                    Bitmap bmp = BitmapFactory.decodeFile(imgPath);
                    if (bmp != null) {
                        img.setImageBitmap(bmp);
                        img.setVisibility(View.VISIBLE);
                        img.setOnClickListener(x -> {
                            Ui.openFile(this, f, "image/jpeg");
                        });
                    }
                }
            }
            bindActions(v, histIndex, shown);
            addFileCards(v, blocks);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) ((user ? 18 : 16)
                * getResources().getDisplayMetrics().density);
        v.setLayoutParams(lp);
        msgs.addView(v);
        if (animate) Ui.reveal(v);
        scrollDown();
    }

    /** Copy / Listen / Regenerate under an AI response — icon-only. */
    private void bindActions(View row, final int histIndex, final String text) {
        View copy = row.findViewById(R.id.act_copy);
        if (copy != null) copy.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText(
                        getString(R.string.app_name), text));
                Ui.toast(this, getString(R.string.ai_copied));
            } catch (Throwable ignored) {}
        });

        final View listen = row.findViewById(R.id.act_listen);
        final ImageView listenIcon = row.findViewById(R.id.act_listen_icon);
        if (listen != null) listen.setOnClickListener(v -> {
            if (TtsPlayer.isPlaying()) {
                TtsPlayer.stop();
                setListenUi(listenIcon, false);
                return;
            }
            setListenUi(listenIcon, true);
            TtsPlayer.speak(AiActivity.this, plain(text), voice,
                    new TtsPlayer.State() {
                @Override public void onState(boolean playing) {
                    setListenUi(listenIcon, playing);
                }
                @Override public void onError(String message) {
                    setListenUi(listenIcon, false);
                    Ui.toast(AiActivity.this,
                            getString(R.string.ai_tts_failed));
                }
            });
        });

        View regen = row.findViewById(R.id.act_regen);
        if (regen != null) regen.setOnClickListener(v ->
                regenerate(histIndex));
    }

    private void setListenUi(ImageView icon, boolean on) {
        if (icon != null) icon.setColorFilter(Fx.color(this, on
                ? R.color.home_brand : R.color.home_muted));
    }

    /** Markdown stripped — what the voice reads. */
    private static String plain(String s) {
        return s == null ? "" : s.replace("*", "").replace("#", "")
                .replace("`", "");
    }

    /** Bitmap -> raw base64 (the images[] format /api/chat expects). */
    private static String rawBase64(Bitmap bmp) {
        if (bmp == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 84, bos);
        return android.util.Base64.encodeToString(bos.toByteArray(),
                android.util.Base64.NO_WRAP);
    }

    // ═══════════════════════════════ AI file blocks (v1.1.5) ═══════════

    /** Parse the body of one ```file block: first line = JSON meta
     *  {name, mime}, the rest = the file content. */
    private static FileBlock parseFileBlock(String body) {
        try {
            int nl = body.indexOf('\n');
            if (nl <= 0) return null;
            JSONObject meta = new JSONObject(body.substring(0, nl));
            String name = meta.optString("name", "");
            if (name.isEmpty()) return null;
            FileBlock f = new FileBlock();
            f.name = name;
            f.mime = meta.optString("mime", "text/plain");
            f.content = body.substring(nl + 1).replaceFirst("\n$", "");
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parse one ```pdffile block: a single JSON line
     *  {name, title, prompt} — the server renders the actual PDF. */
    private static PdfBlock parsePdfBlock(String body) {
        try {
            JSONObject meta = new JSONObject(body.trim());
            String name = meta.optString("name", "");
            if (name.isEmpty()) return null;
            PdfBlock p = new PdfBlock();
            p.name = name;
            p.title = meta.optString("title", name);
            p.prompt = meta.optString("prompt", "");
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Pull every ```file / ```pdffile block out of an AI reply; the
     *  blocks land in `out`, the cleaned text (blocks removed) returns.
     *  Mirrors the website's extractMsgFiles(). */
    static String stripFileBlocks(String text, List<Object> out) {
        if (text == null || text.isEmpty()) return "";
        String t = text;
        while (true) {
            int f = t.indexOf("```file\n");
            int p = t.indexOf("```pdffile\n");
            if (f < 0 && p < 0) break;
            boolean isPdf = p >= 0 && (f < 0 || p < f);
            int start = isPdf ? p : f;
            int head = isPdf ? 10 : 7;
            int end = t.indexOf("```", start + head);
            if (end < 0) break;
            String body = t.substring(start + head, end);
            Object blk = isPdf ? parsePdfBlock(body) : parseFileBlock(body);
            if (blk != null) out.add(blk);
            t = t.substring(0, start) + t.substring(Math.min(end + 3, t.length()));
        }
        return t;
    }

    /** File cards under an AI reply — one per delivered file. */
    private void addFileCards(View row, List<Object> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        LinearLayout box = row.findViewById(R.id.ai_files);
        if (box == null) return;
        box.setVisibility(View.VISIBLE);
        for (Object b : blocks) {
            box.addView(buildFileCard(b));
        }
    }

    /** The card itself: icon + name + meta, Preview + download — tap
     *  anywhere on it to preview (owner order v1.1.5). */
    private View buildFileCard(final Object block) {
        float dp = getResources().getDisplayMetrics().density;
        final boolean isPdf = block instanceof PdfBlock;
        final String name, mime;
        String meta;
        if (isPdf) {
            PdfBlock b = (PdfBlock) block;
            name = b.name;
            mime = "application/pdf";
            meta = "PDF document";
        } else {
            FileBlock f = (FileBlock) block;
            name = f.name;
            mime = f.mime == null || f.mime.isEmpty() ? "text/plain" : f.mime;
            meta = mime + " · " + Ui.size(f.content
                    .getBytes(StandardCharsets.UTF_8).length);
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding((int) (12 * dp), (int) (10 * dp),
                (int) (8 * dp), (int) (10 * dp));
        card.setBackground(getResources().getDrawable(R.drawable.row_card));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = (int) (8 * dp);
        card.setLayoutParams(cp);

        String kind = Ui.kind(mime, name);
        int iconRes = "pdf".equals(kind) ? R.drawable.ic_pdf
                : "image".equals(kind) ? R.drawable.ic_image
                : "doc".equals(kind) ? R.drawable.ic_doc : R.drawable.ic_file;
        ImageView ic = new ImageView(this);
        ic.setLayoutParams(new LinearLayout.LayoutParams(
                (int) (30 * dp), (int) (30 * dp)));
        ic.setImageResource(iconRes);
        ic.setColorFilter(Fx.color(this, R.color.home_brand));
        card.addView(ic);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = (int) (10 * dp);
        txt.setLayoutParams(tp);
        TextView nm = new TextView(this);
        nm.setText(name);
        nm.setTextSize(13);
        nm.setTypeface(Typefaces.interMedium(this));
        nm.setTextColor(Fx.color(this, R.color.home_ink));
        nm.setMaxLines(1);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        txt.addView(nm);
        TextView mt = new TextView(this);
        mt.setText(meta);
        mt.setTextSize(10.5f);
        mt.setTypeface(Typefaces.interRegular(this));
        mt.setTextColor(Fx.color(this, R.color.home_muted));
        txt.addView(mt);
        card.addView(txt);

        TextView preview = new TextView(this);
        preview.setText(R.string.ai_preview);
        preview.setTextSize(12);
        preview.setTypeface(Typefaces.interMedium(this));
        preview.setTextColor(Fx.color(this, R.color.home_brand));
        preview.setPadding((int) (10 * dp), (int) (6 * dp),
                (int) (10 * dp), (int) (6 * dp));
        preview.setBackgroundResource(R.drawable.text_btn_bg);
        card.addView(preview);
        preview.setOnClickListener(v -> previewFile(block));

        FrameLayout dl = new FrameLayout(this);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                (int) (36 * dp), (int) (36 * dp));
        dp2.leftMargin = (int) (6 * dp);
        dl.setLayoutParams(dp2);
        dl.setBackgroundResource(R.drawable.text_btn_bg);
        ImageView di = new ImageView(this);
        di.setLayoutParams(new FrameLayout.LayoutParams(
                (int) (16 * dp), (int) (16 * dp), Gravity.CENTER));
        di.setImageResource(R.drawable.ic_download);
        di.setColorFilter(Fx.color(this, R.color.home_ink));
        dl.addView(di);
        dl.setOnClickListener(v -> downloadFile(block));
        card.addView(dl);

        card.setOnClickListener(v -> previewFile(block));
        return card;
    }

    /** What happens when a file card is tapped. */
    private void previewFile(Object block) {
        if (block instanceof PdfBlock) {
            fetchAndShowPdf((PdfBlock) block);
            return;
        }
        FileBlock f = (FileBlock) block;
        String n = f.name.toLowerCase(Locale.ROOT);
        String m = f.mime == null ? "" : f.mime.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm") || m.contains("html")) {
            showHtmlPreview(f.name, f.content);
        } else if (m.startsWith("image/") || n.endsWith(".png")
                || n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            // an AI-emitted image arrives as raw base64 — Claude-style
            try {
                String b64 = f.content.trim()
                        .replaceFirst("^data:[^,]+,", "");
                byte[] bytes = android.util.Base64.decode(b64,
                        android.util.Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0,
                        bytes.length);
                if (bmp != null) {
                    showFullImage(bmp);
                    return;
                }
            } catch (Throwable ignored) {}
            showTextPreview(f.name, f.content);
        } else {
            showTextPreview(f.name, f.content);
        }
    }

    /** Download button — saves into the device's Downloads. */
    private void downloadFile(final Object block) {
        new Thread(() -> {
            String err = null, path = null;
            try {
                String name, mime;
                byte[] bytes;
                if (block instanceof PdfBlock) {
                    PdfBlock b = (PdfBlock) block;
                    name = b.name.endsWith(".pdf") ? b.name : b.name + ".pdf";
                    mime = "application/pdf";
                    bytes = fetchPdfBytes(b);
                    if (bytes == null) throw new Exception("pdf unavailable");
                } else {
                    FileBlock f = (FileBlock) block;
                    name = f.name;
                    mime = f.mime == null || f.mime.isEmpty()
                            ? "text/plain" : f.mime;
                    bytes = f.content.getBytes(StandardCharsets.UTF_8);
                }
                path = Ui.saveToDownloads(AiActivity.this, bytes, name, mime);
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
            }
            final String e = err, p = path;
            h.post(() -> {
                if (isFinishing()) return;
                if (e != null) {
                    Ui.toast(AiActivity.this,
                            getString(R.string.upload_failed) + ": " + e);
                } else {
                    Ui.toast(AiActivity.this,
                            getString(R.string.ai_saved_downloads) + " " + p);
                }
            });
        }, "xd-file-dl").start();
    }

    /** Ask the worker to render the PDF (the website's engine). */
    private static byte[] fetchPdfBytes(PdfBlock b) {
        try {
            JSONObject body = new JSONObject();
            body.put("prompt", b.prompt == null || b.prompt.isEmpty()
                    ? b.title : b.prompt);
            body.put("title", b.title == null ? b.name : b.title);
            return ApiClient.requestBytesReturn("/api/pdf/file", body);
        } catch (Throwable t) {
            return null;
        }
    }

    private void fetchAndShowPdf(final PdfBlock b) {
        Ui.toast(this, R.string.ai_prep);
        new Thread(() -> {
            byte[] pdf = fetchPdfBytes(b);
            final List<Bitmap> pages = new ArrayList<>();
            if (pdf != null) {
                try {
                    File tmp = new File(getCacheDir(),
                            "preview_" + System.currentTimeMillis() + ".pdf");
                    FileOutputStream fo = new FileOutputStream(tmp);
                    fo.write(pdf);
                    fo.close();
                    RandomAccessFile raf = new RandomAccessFile(tmp, "r");
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(
                            raf.getFD());
                    PdfRenderer pr = new PdfRenderer(pfd);
                    int n = Math.min(pr.getPageCount(), 30);
                    for (int i = 0; i < n; i++) {
                        PdfRenderer.Page pg = pr.openPage(i);
                        int w = Math.min(1080, pg.getWidth() * 2);
                        int hgt = Math.max(1, (int) ((long) pg.getHeight()
                                * w / Math.max(1, pg.getWidth())));
                        Bitmap bmp = Bitmap.createBitmap(w, hgt,
                                Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(android.graphics.Color.WHITE);
                        pg.render(bmp, null, null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        pg.close();
                        pages.add(bmp);
                    }
                    pr.close();
                    pfd.close();
                    raf.close();
                    tmp.delete();
                } catch (Throwable ignored) {}
            }
            h.post(() -> {
                if (isFinishing()) return;
                if (pages.isEmpty()) {
                    Ui.toast(AiActivity.this, R.string.ai_pdf_failed);
                    return;
                }
                showPagesPreview(b.name, pages);
            });
        }, "xd-pdf-view").start();
    }

    // ── fullscreen preview dialogs ───────────────────────────────

    /** A fullscreen black dialog — previews live here. */
    private Dialog fullDialog() {
        Dialog d = new Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(
                    new ColorDrawable(android.graphics.Color.BLACK));
        }
        return d;
    }

    /** The little round ✕ at the top-right that closes a fullscreen
     *  preview (owner order: cross in the fullscreen preview too). */
    private View cross(final Runnable action) {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout b = new FrameLayout(this);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                (int) (36 * dp), (int) (36 * dp), Gravity.TOP | Gravity.END);
        bp.topMargin = bp.rightMargin = (int) (14 * dp);
        b.setLayoutParams(bp);
        b.setBackgroundResource(R.drawable.att_remove_bg);
        ImageView ic = new ImageView(this);
        ic.setLayoutParams(new FrameLayout.LayoutParams(
                (int) (15 * dp), (int) (15 * dp), Gravity.CENTER));
        ic.setImageResource(R.drawable.ic_close);
        ic.setColorFilter(Color.WHITE);
        b.addView(ic);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** A slim title strip so previews say which file they are. */
    private TextView previewTitle(String name) {
        float dp = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        t.setTypeface(Typefaces.interMedium(this));
        t.setTextColor(Color.WHITE);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setPadding((int) (16 * dp), (int) (16 * dp),
                (int) (60 * dp), (int) (8 * dp));
        return t;
    }

    /** Fullscreen image — tap the picture or the ✕ to exit. */
    private void showFullImage(Bitmap bmp) {
        if (bmp == null || isFinishing()) return;
        final Dialog d = fullDialog();
        FrameLayout root = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setImageBitmap(bmp);
        img.setOnClickListener(v -> d.dismiss());
        root.addView(img);
        root.addView(cross(d::dismiss));
        d.setContentView(root);
        d.show();
    }

    /** Fullscreen HTML — rendered in a WebView, like the website. */
    private void showHtmlPreview(String name, String html) {
        if (isFinishing()) return;
        final Dialog d = fullDialog();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(previewTitle(name));
        WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(false);
        wv.setBackgroundColor(android.graphics.Color.WHITE);
        wv.loadDataWithBaseURL(null, html == null ? "" : html,
                "text/html", "UTF-8", null);
        root.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(root);
        // the cross needs to float above the WebView — attach to the
        // dialog's decor once shown
        d.setOnShowListener(x -> {
            android.view.Window w = d.getWindow();
            if (w != null) {
                android.view.ViewGroup decor =
                        (android.view.ViewGroup) w.getDecorView();
                decor.addView(cross(d::dismiss));
            }
        });
        d.show();
    }

    /** Fullscreen plain-text/notes/code viewer. */
    private void showTextPreview(String name, String content) {
        if (isFinishing()) return;
        final Dialog d = fullDialog();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(previewTitle(name));
        ScrollView sc = new ScrollView(this);
        TextView t = new TextView(this);
        float dp = getResources().getDisplayMetrics().density;
        t.setText(content == null ? "" : content);
        t.setTextSize(12.5f);
        t.setTypeface(Typefaces.interRegular(this));
        t.setTextColor(android.graphics.Color.WHITE);
        t.setPadding((int) (16 * dp), (int) (10 * dp),
                (int) (16 * dp), (int) (24 * dp));
        sc.addView(t);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(root);
        d.setOnShowListener(x -> {
            android.view.Window w = d.getWindow();
            if (w != null) {
                android.view.ViewGroup decor =
                        (android.view.ViewGroup) w.getDecorView();
                decor.addView(cross(d::dismiss));
            }
        });
        d.show();
    }

    /** Fullscreen PDF — server-rendered pages scroll top to bottom. */
    private void showPagesPreview(String name, List<Bitmap> pages) {
        if (isFinishing()) return;
        final Dialog d = fullDialog();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(previewTitle(name));
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF101014);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        for (Bitmap bmp : pages) {
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            iv.setAdjustViewBounds(true);
            iv.setImageBitmap(bmp);
            LinearLayout.LayoutParams ip = (LinearLayout.LayoutParams)
                    iv.getLayoutParams();
            ip.leftMargin = ip.rightMargin = (int) (10 * dp);
            ip.topMargin = (int) (10 * dp);
            iv.setLayoutParams(ip);
            list.addView(iv);
        }
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(root);
        d.setOnShowListener(x -> {
            android.view.Window w = d.getWindow();
            if (w != null) {
                android.view.ViewGroup decor =
                        (android.view.ViewGroup) w.getDecorView();
                decor.addView(cross(d::dismiss));
            }
        });
        d.show();
    }

    // ═════════════════════════════════════ sending ═══════════════

    private void focusInput() {
        input.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, 0);
    }

    /** GREY when there is nothing to send, BLUE the moment there is. */
    private void refreshSendUi() {
        boolean ready = input.getText().toString().trim().length() > 0
                || !attach.isEmpty();
        send.setBackgroundResource(ready
                ? R.drawable.ai_send_ready : R.drawable.ai_send_idle);
        ((ImageView) sendIcon).setColorFilter(ready
                ? Color.WHITE
                : Fx.color(this, R.color.home_tile_ink));
    }

    private float composedFontSize() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getFloat(AiSettingsActivity.KEY_FONT_SIZE,
                        AiSettingsActivity.DEFAULT_FONT_SIZE);
    }

    private void send() {
        final String text = input.getText().toString().trim();
        if (busy) return;
        if (imageMode) {
            if (text.isEmpty()) {
                Ui.toast(this, getString(R.string.ai_menu_image));
                return;
            }
            input.setText("");
            generateImage(text);
            return;
        }
        if (text.isEmpty() && attach.isEmpty()) return;

        busy = true;
        input.setText("");
        input.setEnabled(false);
        send.setAlpha(0.4f);
        emptyState.setVisibility(View.GONE);

        // everything below belongs to THIS conversation — even if the
        // user switches sessions while the answer is in flight
        final String sid = currentId;
        final boolean firstExchange = histOf(sid).length() == 0;

        // visible text mirrors the website: attachments are announced
        StringBuilder userText = new StringBuilder(text);
        StringBuilder fileContents = new StringBuilder();
        JSONArray images = new JSONArray();
        final List<Att> sent = new ArrayList<>();
        if (!attach.isEmpty()) {
            sent.addAll(attach);
            StringBuilder names = new StringBuilder();
            for (Att a : attach) names.append(a.name).append(", ");
            String clean = names.toString().replaceAll(", $", "");
            if (text.isEmpty()) {
                userText = new StringBuilder("Summarise the attached file(s)");
            }
            userText.append("\n[Attached: ").append(clean).append("]");
            for (Att a : attach) {
                if (a.image) {
                    try {
                        JSONObject im = new JSONObject();
                        im.put("mimeType", "image/jpeg");
                        im.put("base64", rawBase64(a.bmp));
                        images.put(im);
                    } catch (Throwable ignored) {}
                } else if (a.text != null) {
                    fileContents.append("\n\n--- FILE: ").append(a.name)
                            .append(" ---\n")
                            .append(a.text.substring(0, Math.min(24000,
                                    a.text.length())))
                            .append("\n--- END FILE ---");
                }
            }
            attach.clear();
            refreshAttachStrip();
        }

        String modePrefix = deepResearch
                ? "[Research mode: research this thoroughly with multiple "
                        + "web searches, then answer with sources.]\n\n"
                : "";

        String time = Ui.now("h:mm a");
        String shown = userText.toString();
        addMessage(sid, "user", shown, time, null);
        appendBubble("user", shown, time, null, -1, true);

        // v1.1.5 (owner spec): every UPLOADED file lands in this chat's
        // artifacts folder — the AI's workspace — so the AI can work
        // with it in later turns and on other devices
        if (!sent.isEmpty()) {
            final ChatSync.Session up = findSession(sid);
            if (up != null) {
                new Thread(() -> {
                    for (Att a : sent) {
                        try {
                            byte[] bytes;
                            String mime;
                            if (a.image && a.bmp != null) {
                                ByteArrayOutputStream bo =
                                        new ByteArrayOutputStream();
                                a.bmp.compress(Bitmap.CompressFormat.JPEG,
                                        88, bo);
                                bytes = bo.toByteArray();
                                mime = "image/jpeg";
                            } else if (a.text != null) {
                                bytes = a.text.getBytes(StandardCharsets.UTF_8);
                                mime = "text/plain";
                            } else {
                                continue;
                            }
                            ChatSync.saveArtifact(this, up, a.name,
                                    bytes, mime);
                        } catch (Throwable ignored) {}
                    }
                }, "xd-ws-upload").start();
            }
        }

        JSONArray hist = histOf(sid);
        final JSONObject body = new JSONObject();
        try {
            body.put("message", modePrefix + shown
                    + (fileContents.length() > 0 ? "\n\n"
                        + fileContents.substring(0, Math.min(60000,
                            fileContents.length())) : ""));
            body.put("role", st.role == null || st.role.isEmpty()
                    ? "student" : st.role);
            body.put("email", st.email);
            if (st.klass != null && !st.klass.isEmpty()) {
                body.put("class", st.klass);
            }
            if (images.length() > 0) body.put("images", images);
            // v1.1.5: the chat's workspace files — the AI knows what it
            // has to work with (its own earlier files + user uploads)
            ChatSync.Session curS = findSession(sid);
            if (curS != null && !curS.ws.isEmpty()) {
                JSONArray ws = new JSONArray();
                for (String wn : curS.ws) ws.put(wn);
                body.put("workspace", ws);
            }
            // last 30 turns BEFORE the new message — the worker appends
            // `message` itself, so including it here would double it
            JSONArray histOut = new JSONArray();
            int from = Math.max(0, hist.length() - 1 - 30);
            for (int i = from; i < hist.length() - 1; i++) {
                JSONObject m = hist.optJSONObject(i);
                if (m == null) continue;
                JSONObject o = new JSONObject();
                o.put("role", m.optString("role", "user"));
                o.put("text", m.optString("text", ""));
                histOut.put(o);
            }
            body.put("history", histOut);
        } catch (Throwable ignored) {}

        showTyping();
        requestAnswer(sid, body, true, firstExchange);
    }

    /**
     * POST /api/chat and place the answer. `saveAfter` persists the
     * session to the Drive store (the website sees it immediately);
     * `firstExchange` asks the AI to name the chat (the website's flow).
     */
    private void requestAnswer(final String sid, final JSONObject body,
                               final boolean saveAfter,
                               final boolean firstExchange) {
        new Thread(() -> {
            ApiClient.Resp r = ApiClient.requestJson("POST", "/api/chat",
                    body);
            String answer = null, err = null;
            if (r.ok && r.json != null) {
                answer = r.json.optString("response", "");
                if (answer.isEmpty()) err = getString(R.string.ai_error);
                if (r.json.optBoolean("quotaExhausted", false)) {
                    answer = getString(R.string.ai_quota_warn) + "\n\n"
                            + answer;
                }
            } else if (r.code == 429) {
                err = r.error();
            } else {
                err = r.error().isEmpty()
                        ? getString(R.string.ai_error) : r.error();
            }
            final String ans = answer, e = err;
            h.post(() -> {
                if (isFinishing()) return;
                hideTyping();
                busy = false;
                input.setEnabled(true);
                send.setAlpha(1f);
                String t = Ui.now("h:mm a");
                boolean showing = sid.equals(currentId);
                if (ans != null) {
                    addMessage(sid, "ai", ans, t, null);
                    if (showing) {
                        appendBubble("ai", ans, t, null,
                                histOf(sid).length() - 1, true);
                    }
                    // v1.1.5: AI-created files (```file blocks) are stored
                    // into the chat's artifacts folder — its workspace
                    final List<Object> made = new ArrayList<>();
                    stripFileBlocks(ans, made);
                    final ChatSync.Session wsSession = findSession(sid);
                    if (!made.isEmpty() && wsSession != null) {
                        new Thread(() -> {
                            for (Object mb : made) {
                                if (mb instanceof FileBlock) {
                                    FileBlock fb = (FileBlock) mb;
                                    if (wsSession.ws.contains(fb.name)) {
                                        continue;   // already in the workspace
                                    }
                                    ChatSync.saveArtifact(this, wsSession,
                                            fb.name, fb.content.getBytes(
                                                    StandardCharsets.UTF_8),
                                            fb.mime);
                                }
                            }
                        }, "xd-ws-ai").start();
                    }
                    if (saveAfter) {
                        ChatSync.Session s = findSession(sid);
                        if (s != null) {
                            persistCache();
                            ChatSync.save(this, s, null);
                        }
                    }
                    // the AI names the chat after the first reply
                    if (firstExchange) {
                        fetchAiTitle(sid, body.optString("message", ""), ans);
                    }
                } else if (showing) {
                    appendBubble("ai", e, t, null, -1, true);
                }
                if (showing) scrollDown();
            });
        }, "xd-ai-send").start();
    }

    /** POST /api/chat/title — the website's AI chat-naming engine. */
    private void fetchAiTitle(final String sid, final String userMsg,
                              final String reply) {
        ChatSync.Session s = findSession(sid);
        if (s == null) return;
        final String before = s.name;
        new Thread(() -> {
            String title = null;
            try {
                JSONObject body = new JSONObject();
                body.put("message",
                        userMsg.substring(0, Math.min(1500, userMsg.length())));
                body.put("reply",
                        reply.substring(0, Math.min(1500, reply.length())));
                ApiClient.Resp r = ApiClient.requestJson("POST",
                        "/api/chat/title", body);
                if (r.ok && r.json != null) {
                    title = r.json.optString("title", "");
                }
            } catch (Throwable ignored) {}
            final String t = title;
            h.post(() -> {
                if (isFinishing() || t == null || t.trim().isEmpty()) return;
                ChatSync.Session cur = findSession(sid);
                if (cur == null) return;
                cur.name = t.trim();
                persistCache();
                ChatSync.save(this, cur, null);
                sortSessions();
                if (sid.equals(currentId)) updateTitle();
                if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
                    rebuildDrawer();
                }
                if (before == null || before.isEmpty()) {
                    // nothing — the title simply replaces "Untitled"
                }
            });
        }, "xd-ai-title").start();
    }

    /** Regenerate: drop this answer (and everything after), re-ask. */
    private void regenerate(int histIndex) {
        if (busy || histIndex < 0) return;
        ChatSync.Session s = findSession(currentId);
        if (s == null) return;
        JSONArray hist = s.history;
        if (histIndex >= hist.length()) return;

        // find the user message this answer belongs to
        int userIdx = histIndex;
        while (userIdx >= 0
                && !"user".equals(hist.optJSONObject(userIdx)
                        .optString("role", ""))) {
            userIdx--;
        }
        if (userIdx < 0) return;

        // truncate: keep through the user message, drop the rest
        JSONArray kept = new JSONArray();
        for (int i = 0; i <= userIdx; i++) kept.put(hist.optJSONObject(i));
        s.history = kept;
        persistCache();

        busy = true;
        input.setEnabled(false);
        send.setAlpha(0.4f);
        renderSession();
        showTyping();

        String lastUser = hist.optJSONObject(userIdx)
                .optString("text", "");
        final JSONObject body = new JSONObject();
        try {
            body.put("message", lastUser);
            body.put("role", st.role == null || st.role.isEmpty()
                    ? "student" : st.role);
            body.put("email", st.email);
            if (st.klass != null && !st.klass.isEmpty()) {
                body.put("class", st.klass);
            }
            JSONArray histOut = new JSONArray();
            // everything before the user message being re-asked
            int from = Math.max(0, kept.length() - 1 - 30);
            for (int i = from; i < kept.length() - 1; i++) {
                JSONObject m = kept.optJSONObject(i);
                if (m == null) continue;
                JSONObject o = new JSONObject();
                o.put("role", m.optString("role", "user"));
                o.put("text", m.optString("text", ""));
                histOut.put(o);
            }
            body.put("history", histOut);
        } catch (Throwable ignored) {}

        requestAnswer(currentId, body, true, false);
    }

    // ═════════════════════════════════════ image generation ════════════

    /** Pollinations picture, exactly like the website's generator.
     *  Admins + developers only (owner order v1.2.0). */
    private void generateImage(final String prompt) {
        busy = true;
        input.setEnabled(false);
        send.setAlpha(0.4f);
        imageMode = false;
        input.setHint(R.string.ai_hint);
        refreshModeStrip();

        final String sid = currentId;
        String time = Ui.now("h:mm a");
        String caption = getString(R.string.ai_menu_image) + " · " + prompt;
        addMessage(sid, "user", prompt, time, null);
        appendBubble("user", prompt, time, null, -1, true);
        showTyping(getString(R.string.ai_image_working));

        new Thread(() -> {
            String err = null;
            File out = null;
            byte[] bytes = null;
            try {
                String u = "https://image.pollinations.ai/prompt/"
                        + URLEncoder.encode(prompt, "UTF-8")
                        + "?width=768&height=768&nologo=true&seed="
                        + (int) (Math.random() * 999999);
                HttpURLConnection c = (HttpURLConnection)
                        new URL(u).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(90000);
                if (c.getResponseCode() != 200) {
                    throw new Exception("HTTP " + c.getResponseCode());
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                bytes = bos.toByteArray();
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0,
                        bytes.length);
                if (bmp == null) throw new Exception("bad image");

                File dir = new File(getCacheDir(), "aiimg");
                if (!dir.exists()) dir.mkdirs();
                out = new File(dir, "ai_" + System.currentTimeMillis()
                        + ".jpg");
                FileOutputStream fo = new FileOutputStream(out);
                fo.write(bytes);
                fo.close();
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }

            final File file = out;
            final byte[] imgBytes = bytes;
            final String error = err;
            final String t2 = Ui.now("h:mm a");
            h.post(() -> {
                if (isFinishing()) return;
                hideTyping();
                busy = false;
                input.setEnabled(true);
                send.setAlpha(1f);
                refreshSendUi();
                boolean showing = sid.equals(currentId);
                if (error != null) {
                    String msg = getString(R.string.ai_image_failed);
                    addMessage(sid, "ai", msg, t2, null);
                    if (showing) appendBubble("ai", msg, t2, null, -1, true);
                } else {
                    addMessage(sid, "ai", caption, t2,
                            file == null ? null : file.getAbsolutePath());
                    if (showing) {
                        appendBubble("ai", caption, t2,
                                file == null ? null : file.getAbsolutePath(),
                                histOf(sid).length() - 1, true);
                    }
                }
                ChatSync.Session s = findSession(sid);
                if (s != null) {
                    persistCache();
                    ChatSync.save(this, s, null);
                    // artifact parity: keep the picture in the Drive
                    // session folder, like the website's saveArtifact()
                    if (error == null && imgBytes != null) {
                        new Thread(() -> {
                            try {
                                if (s.folderId == null
                                        || s.folderId.isEmpty()) {
                                    ChatSync.saveBlocking(this, s);
                                }
                                if (s.folderId != null
                                        && !s.folderId.isEmpty()) {
                                    String arts = Drive.ensureFolder(
                                            "artifacts", s.folderId);
                                    String imgs = Drive.ensureFolder(
                                            "image", arts);
                                    Drive.upload("ai-image_"
                                            + System.currentTimeMillis()
                                            + ".jpg", imgs, null, imgBytes,
                                            "image/jpeg");
                                }
                            } catch (Throwable ignored) {}
                        }, "xd-ai-artifact").start();
                    }
                }
                if (showing) scrollDown();
            });
        }, "xd-ai-image").start();
    }

    // ═════════════════════════════════════ typing indicator ════════════

    private void showTyping() {
        showTyping(THINKING[0]);
    }

    private void showTyping(String label) {
        hideTyping();
        typingRow = LayoutInflater.from(this)
                .inflate(R.layout.item_msg_ai, msgs, false);
        TextView tv = typingRow.findViewById(R.id.bubble_text);
        tv.setText(label);
        TextView tt = typingRow.findViewById(R.id.bubble_time);
        if (tt != null) tt.setText("");
        View acts = typingRow.findViewById(R.id.ai_actions);
        if (acts != null) acts.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        typingRow.setLayoutParams(lp);
        msgs.addView(typingRow);
        scrollDown();
        h.post(thinkCycle);
    }

    private void hideTyping() {
        h.removeCallbacks(thinkCycle);
        if (typingRow != null) {
            try { msgs.removeView(typingRow); } catch (Throwable ignored) {}
            typingRow = null;
        }
    }

    private void scrollDown() {
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ═════════════════════════════════════ the plus menu ═══════════════

    /** The ChatGPT-style small menu: Camera · Photos · Files · Image
     *  (admins + developers only) · Deep research. NOT focusable — the
     *  keyboard stays open (the v1.1.2 popup stole focus and the
     *  keyboard visibly closed and reopened; owner-reported glitch). */
    private void showPlusMenu() {
        if (plusMenu != null) {
            try { plusMenu.dismiss(); } catch (Throwable ignored) {}
            plusMenu = null;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        float dp = getResources().getDisplayMetrics().density;
        box.setBackground(getResources().getDrawable(R.drawable.dialog_bg));
        box.setElevation(10 * dp);

        boolean power = st.adminPower();
        int icons[] = {R.drawable.ic_camera, R.drawable.ic_image,
                R.drawable.ic_file, R.drawable.ic_sparkle,
                R.drawable.ic_search};
        int labels[] = {R.string.ai_menu_camera, R.string.ai_menu_photos,
                R.string.ai_menu_files, R.string.ai_menu_image,
                R.string.ai_menu_research};
        final Runnable actions[] = {
                () -> pickFromCamera(),
                () -> pickPhotos(),
                () -> pickFiles(),
                () -> {
                    imageMode = true;
                    input.setHint(R.string.ai_menu_image);
                    refreshModeStrip();
                    refreshSendUi();
                    focusInput();
                },
                () -> {
                    deepResearch = !deepResearch;
                    refreshModeStrip();
                }
        };

        for (int i = 0; i < labels.length; i++) {
            if (i == 3 && !power) continue;   // Generate image: admins/devs
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (16 * dp), (int) (11 * dp),
                    (int) (16 * dp), (int) (11 * dp));
            ImageView ic = new ImageView(this);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    (int) (19 * dp), (int) (19 * dp));
            ic.setLayoutParams(ip);
            ic.setImageResource(icons[i]);
            ic.setColorFilter(Fx.color(this, idx == 3 && imageMode
                    ? R.color.home_brand : R.color.home_muted));
            row.addView(ic);
            TextView label = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = (int) (12 * dp);
            label.setLayoutParams(lp);
            label.setText(labels[i]);
            label.setTextSize(14);
            label.setTypeface(Typefaces.interMedium(this));
            label.setTextColor(Fx.color(this, R.color.home_ink));
            row.addView(label);
            row.setOnClickListener(v -> {
                if (plusMenu != null) {
                    try { plusMenu.dismiss(); } catch (Throwable ignored) {}
                    plusMenu = null;
                }
                actions[idx].run();
            });
            box.addView(row);
        }

        PopupWindow pop = new PopupWindow(box,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // focusable=false: the keyboard that is already open STAYS open
        pop.setOutsideTouchable(true);
        pop.setElevation(10 * dp);
        pop.setBackgroundDrawable(getResources()
                .getDrawable(R.drawable.dialog_bg));
        // the composer sits at the BOTTOM of the screen — the menu must
        // open ABOVE the plus button (measure first, then offset up)
        box.measure(View.MeasureSpec.UNSPECIFIED,
                View.MeasureSpec.UNSPECIFIED);
        int up = box.getMeasuredHeight() + (int) (12 * dp);
        pop.showAsDropDown(findViewById(R.id.ai_plus), 0, -up,
                Gravity.TOP | Gravity.START);
        plusMenu = pop;
    }

    private PopupWindow plusMenu;

    private void refreshModeStrip() {
        boolean any = deepResearch || imageMode;
        modeStrip.setVisibility(any ? View.VISIBLE : View.GONE);
        if (deepResearch) {
            modeChip.setText(R.string.ai_research_on);
        } else if (imageMode) {
            modeChip.setText(R.string.ai_menu_image);
        }
    }

    // ═════════════════════════════════════ attachments ═════════════════

    private void pickPhotos() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, PICK_PHOTOS);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.pick_file) + ": " + t);
        }
    }

    private void pickFiles() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(
                    Intent.createChooser(i, getString(R.string.pick_file)),
                    PICK_FILES);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.pick_file) + ": " + t);
        }
    }

    private File cameraFile;

    private void pickFromCamera() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            cameraFile = new File(dir, "shot_"
                    + System.currentTimeMillis() + ".jpg");
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".files", cameraFile);
            Intent i = new Intent(android.provider.MediaStore
                    .ACTION_IMAGE_CAPTURE);
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, TAKE_PHOTO);
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.ai_menu_camera) + ": " + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) return;

        if (req == TAKE_PHOTO) {
            if (cameraFile != null && cameraFile.exists()) {
                Bitmap in = BitmapFactory.decodeFile(
                        cameraFile.getAbsolutePath());
                if (in != null && attach.size() < 4) {
                    Att a = new Att();
                    a.name = "camera.jpg";
                    a.image = true;
                    a.bmp = ProfileSync.normalize(in);
                    attach.add(a);
                    refreshAttachStrip();
                } else if (attach.size() >= 4) {
                    Ui.toast(this, getString(R.string.ai_att_max));
                }
            }
            return;
        }
        if (data == null) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                uris.add(cd.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        final boolean asImage = (req == PICK_PHOTOS);
        for (Uri u : uris) {
            if (attach.size() >= 4) {
                Ui.toast(this, getString(R.string.ai_att_max));
                break;
            }
            resolveAttachment(u, asImage);
        }
    }

    private void resolveAttachment(Uri u, boolean forceImage) {
        String name = uriName(u);
        String mime = null;
        try {
            mime = getContentResolver().getType(u);
        } catch (Throwable ignored) {}
        if (mime == null) mime = "";
        boolean image = forceImage || mime.startsWith("image/")
                || name.toLowerCase(Locale.ROOT).endsWith(".jpg")
                || name.toLowerCase(Locale.ROOT).endsWith(".jpeg")
                || name.toLowerCase(Locale.ROOT).endsWith(".png")
                || name.toLowerCase(Locale.ROOT).endsWith(".webp");

        // the 15 MB ceiling (owner order v1.2.0)
        long size = -1;
        try {
            android.database.Cursor c = getContentResolver()
                    .query(u, null, null, null, null);
            if (c != null) {
                int ix = c.getColumnIndex(
                        android.provider.OpenableColumns.SIZE);
                if (ix >= 0 && c.moveToFirst()) {
                    size = c.getLong(ix);
                }
                c.close();
            }
        } catch (Throwable ignored) {}
        if (size > MAX_ATT_BYTES) {
            Ui.toast(this, getString(R.string.ai_att_too_big,
                    Ui.size(MAX_ATT_BYTES)));
            return;
        }

        if (image) {
            // images: shrink to ≤ 1024px JPEG (server caps at 1 MB each)
            try {
                Bitmap in = BitmapFactory.decodeStream(
                        getContentResolver().openInputStream(u));
                if (in != null) {
                    Att a = new Att();
                    a.name = name;
                    a.image = true;
                    a.bmp = ProfileSync.normalize(in);
                    attach.add(a);
                    refreshAttachStrip();
                    return;
                }
            } catch (Throwable ignored) {}
            Ui.toast(this, getString(R.string.error_load));
        } else {
            // documents: read as text (the website's behaviour)
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                getContentResolver().openInputStream(u),
                                "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                r.close();
                Att a = new Att();
                a.name = name;
                a.image = false;
                a.text = sb.toString();
                attach.add(a);
                refreshAttachStrip();
            } catch (Throwable t) {
                Ui.toast(this, getString(R.string.error_load));
            }
        }
    }

    private String uriName(Uri u) {
        String name = null;
        try {
            android.database.Cursor c = getContentResolver()
                    .query(u, null, null, null, null);
            if (c != null) {
                int ix = c.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME);
                if (ix >= 0 && c.moveToFirst()) name = c.getString(ix);
                c.close();
            }
        } catch (Throwable ignored) {}
        if (name == null) {
            String p = u.getLastPathSegment();
            name = p == null ? "file" : p;
        }
        return name;
    }

    private void refreshAttachStrip() {
        attachRow.removeAllViews();
        if (attach.isEmpty()) {
            attachStrip.setVisibility(View.GONE);
            refreshSendUi();
            return;
        }
        attachStrip.setVisibility(View.VISIBLE);
        float dp = getResources().getDisplayMetrics().density;
        for (final Att a : attach) {
            if (a.image && a.bmp != null) {
                // v1.1.5 (owner order, Claude-style): uploaded PHOTOS show
                // as small SQUARE PREVIEWS instead of a file-name chip —
                // the next photo sits to the right of the previous one.
                // Tap the square → fullscreen; the little ✕ at its top
                // corner removes it from the upload.
                FrameLayout sq = new FrameLayout(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        (int) (62 * dp), (int) (62 * dp));
                lp.rightMargin = (int) (9 * dp);
                lp.gravity = Gravity.CENTER_VERTICAL;
                sq.setLayoutParams(lp);

                ImageView img = new ImageView(this);
                img.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setImageBitmap(a.bmp);
                android.graphics.drawable.GradientDrawable thumb =
                        new android.graphics.drawable.GradientDrawable();
                thumb.setCornerRadius(12 * dp);
                thumb.setColor(Fx.color(this, R.color.home_tile_ink));
                img.setBackground(thumb);
                img.setClipToOutline(true);
                sq.addView(img);

                // the ✕ — round scrim so it reads on any photo
                FrameLayout x = new FrameLayout(this);
                FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(
                        (int) (19 * dp), (int) (19 * dp),
                        Gravity.TOP | Gravity.END);
                x.setLayoutParams(xp);
                x.setBackgroundResource(R.drawable.att_remove_bg);
                ImageView xi = new ImageView(this);
                xi.setLayoutParams(new FrameLayout.LayoutParams(
                        (int) (9 * dp), (int) (9 * dp), Gravity.CENTER));
                xi.setImageResource(R.drawable.ic_close);
                xi.setColorFilter(Color.WHITE);
                x.addView(xi);
                sq.addView(x);

                // tap the photo → fullscreen preview (owner order);
                // tap the ✕ → drop it from the upload
                img.setOnClickListener(v -> showFullImage(a.bmp));
                x.setOnClickListener(v -> {
                    attach.remove(a);
                    refreshAttachStrip();
                });
                attachRow.addView(sq);
            } else {
                // documents keep the compact chip (nothing to preview)
                LinearLayout chip = new LinearLayout(this);
                chip.setOrientation(LinearLayout.HORIZONTAL);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                chip.setPadding((int) (10 * dp), (int) (6 * dp),
                        (int) (8 * dp), (int) (6 * dp));
                chip.setBackground(getResources()
                        .getDrawable(R.drawable.chip_off));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = (int) (8 * dp);
                lp.gravity = Gravity.CENTER_VERTICAL;
                chip.setLayoutParams(lp);

                ImageView ic = new ImageView(this);
                ic.setLayoutParams(new LinearLayout.LayoutParams(
                        (int) (15 * dp), (int) (15 * dp)));
                ic.setImageResource(R.drawable.ic_file);
                ic.setColorFilter(Fx.color(this, R.color.home_muted));
                chip.addView(ic);

                TextView t = new TextView(this);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tp.leftMargin = (int) (6 * dp);
                t.setLayoutParams(tp);
                t.setText(a.name);
                t.setTextSize(12);
                t.setTypeface(Typefaces.interMedium(this));
                t.setTextColor(Fx.color(this, R.color.home_ink));
                t.setMaxLines(1);
                t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                chip.addView(t);

                chip.setOnClickListener(v -> {
                    attach.remove(a);
                    refreshAttachStrip();
                });
                attachRow.addView(chip);
            }
        }
        refreshSendUi();
    }

    // ═════════════════════════════════════ voice input ═════════════════

    private void toggleMic() {
        if (listening) {
            if (voiceInput != null) voiceInput.stop();
            setListeningUi(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms,
                                           int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_MIC) {
            for (int i = 0; i < perms.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(perms[i])
                        && grants[i] == PackageManager.PERMISSION_GRANTED) {
                    startListening();
                    return;
                }
            }
        }
    }

    private void startListening() {
        if (voiceInput == null) {
            voiceInput = new VoiceInput(this, new VoiceInput.Events() {
                @Override public void onListening() {
                    setListeningUi(true);
                }
                @Override public void onText(String text, boolean isFinal) {
                    if (text != null && !text.isEmpty()) {
                        input.setText(text);
                        input.setSelection(input.getText().length());
                    }
                }
                @Override public void onDone() {
                    setListeningUi(false);
                }
            });
        }
        voiceInput.start();
    }

    private Runnable micPulse;

    /** Plain mic by default; BLUE with a waveform + gentle pulse while
     *  the voice is being captured (owner order v1.2.0). */
    private void setListeningUi(boolean on) {
        listening = on;
        if (micPulse != null) {
            h.removeCallbacks(micPulse);
            micPulse = null;
        }
        if (on) {
            micIcon.setImageResource(R.drawable.ic_audio);
            micIcon.setColorFilter(Color.WHITE);
            micBtn.setBackgroundResource(R.drawable.mic_listening_bg);
            input.setHint(R.string.ai_listening);
            micBtn.setScaleX(1f);
            micBtn.setScaleY(1f);
            micPulse = new Runnable() {
                @Override public void run() {
                    if (!listening) {
                        micBtn.setScaleX(1f);
                        micBtn.setScaleY(1f);
                        return;
                    }
                    micBtn.animate().scaleX(1.09f).scaleY(1.09f)
                            .setDuration(400L)
                            .withEndAction(() -> micBtn.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .setDuration(400L)
                                    .withEndAction(this).start())
                            .start();
                }
            };
            h.post(micPulse);
        } else {
            micIcon.setImageResource(R.drawable.ic_mic);
            micIcon.setColorFilter(Fx.color(this, R.color.home_tile_ink));
            micBtn.setBackgroundResource(0);
            micBtn.setScaleX(1f);
            micBtn.setScaleY(1f);
            input.setHint(imageMode
                    ? R.string.ai_menu_image : R.string.ai_hint);
        }
    }

    // ═════════════════════════════════════ drawer ═════════════════════

    private void openDrawer() {
        rebuildDrawer();
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.animate().alpha(1f).setDuration(180L).start();
        int panelW = drawerPanel.getWidth();
        drawerPanel.setTranslationX(panelW > 0 ? -panelW : -320f);
        drawerPanel.animate().translationX(0f)
                .setDuration(220L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void closeDrawer() {
        drawer.animate().alpha(0f).setDuration(160L)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private boolean matches(ChatSync.Session s, String q) {
        if (q.isEmpty()) return true;
        if ((s.name == null ? "" : s.name).toLowerCase(Locale.ROOT)
                .contains(q)) return true;
        for (int i = 0; i < s.history.length(); i++) {
            JSONObject m = s.history.optJSONObject(i);
            if (m != null && m.optString("text", "")
                    .toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private void rebuildDrawer() {
        if (drawerList == null) return;
        drawerList.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;

        boolean any = false;
        for (final ChatSync.Session s : sessions) {
            if (s.history.length() == 0) continue;
            if (!matches(s, lastQuery)) continue;
            any = true;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (12 * dp), (int) (10 * dp),
                    (int) (6 * dp), (int) (10 * dp));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = (int) (4 * dp);
            row.setLayoutParams(rp);

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            text.setLayoutParams(tp);

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            if (pins.contains(s.id)) {
                ImageView pin = new ImageView(this);
                pin.setLayoutParams(new LinearLayout.LayoutParams(
                        (int) (13 * dp), (int) (13 * dp)));
                pin.setImageResource(R.drawable.ic_pin);
                pin.setColorFilter(Fx.color(this, R.color.home_brand));
                LinearLayout.LayoutParams pp = (LinearLayout.LayoutParams)
                        pin.getLayoutParams();
                pp.rightMargin = (int) (5 * dp);
                pin.setLayoutParams(pp);
                titleRow.addView(pin);
            }

            TextView title = new TextView(this);
            String name = s.name == null || s.name.isEmpty()
                    ? getString(R.string.ai_untitled) : s.name;
            title.setText(name);
            title.setTextSize(13.5f);
            title.setTypeface(Typefaces.interMedium(this));
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            final boolean active = s.id.equals(currentId);
            title.setTextColor(Fx.color(this, active
                    ? R.color.home_brand : R.color.home_ink));
            titleRow.addView(title);
            text.addView(titleRow);

            TextView meta = new TextView(this);
            meta.setText(whenText(s.created) + "  ·  "
                    + s.history.length() + " "
                    + getString(R.string.ai_history_messages));
            meta.setTextSize(11);
            meta.setTypeface(Typefaces.interRegular(this));
            meta.setTextColor(Fx.color(this, R.color.home_muted));
            text.addView(meta);
            row.addView(text);

            // the 3-dot — every chat gets one (owner order)
            View dots = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    (int) (18 * dp), (int) (18 * dp));
            dlp.leftMargin = (int) (4 * dp);
            dots.setLayoutParams(dlp);
            dots.setBackground(getResources().getDrawable(R.drawable.ic_more));
            dots.setBackgroundTintList(android.content.res.ColorStateList
                    .valueOf(Fx.color(this, R.color.home_muted)));
            dots.setOnClickListener(v -> showChatMenu(s, v));
            row.addView(dots);

            // SQUARE corners (owner order: "square instead of rounded
            // oval"), hairline edge, tonal accent when active
            row.setBackground(getResources().getDrawable(
                    active ? R.drawable.chat_row_on : R.drawable.chat_row_bg));
            row.setOnClickListener(v -> {
                closeDrawer();
                currentId = s.id;
                persistCache();
                renderSession();
            });
            row.setOnLongClickListener(v -> {
                showChatMenu(s, v);
                return true;
            });
            drawerList.addView(row);
        }

        if (!any) {
            TextView none = new TextView(this);
            none.setText(lastQuery.isEmpty()
                    ? R.string.ai_history_none : R.string.ai_search_none);
            none.setTextSize(12.5f);
            none.setTypeface(Typefaces.interRegular(this));
            none.setTextColor(Fx.color(this, R.color.home_muted));
            none.setPadding((int) (14 * dp), (int) (12 * dp),
                    (int) (14 * dp), (int) (12 * dp));
            drawerList.addView(none);
        }
    }

    /** Rename · Pin · Summarize · Delete — the per-chat menu. */
    private void showChatMenu(final ChatSync.Session s, View anchor) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(getResources().getDrawable(R.drawable.dialog_bg));
        box.setElevation(10 * dp);

        final boolean pinned = pins.contains(s.id);
        int icons[] = {R.drawable.ic_edit, R.drawable.ic_pin,
                R.drawable.ic_sparkle, R.drawable.ic_trash};
        int labels[] = {R.string.ai_menu_rename, pinned
                ? R.string.ai_menu_unpin : R.string.ai_menu_pin,
                R.string.ai_menu_summarize, R.string.ai_menu_delete};
        final Runnable actions[] = {
                () -> askRename(s),
                () -> {
                    if (pinned) {
                        pins.remove(s.id);
                    } else {
                        pins.add(s.id);
                    }
                    persistCache();
                    sortSessions();
                    rebuildDrawer();
                },
                () -> {
                    closeDrawer();
                    currentId = s.id;
                    persistCache();
                    renderSession();
                    input.setText(getString(
                            R.string.ai_summarize_prompt));
                    input.setSelection(input.getText().length());
                    send();
                },
                () -> confirmDeleteSession(s)
        };

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (16 * dp), (int) (11 * dp),
                    (int) (16 * dp), (int) (11 * dp));
            ImageView ic = new ImageView(this);
            ic.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (17 * dp), (int) (17 * dp)));
            ic.setImageResource(icons[i]);
            ic.setColorFilter(Fx.color(this, idx == 3
                    ? R.color.danger : R.color.home_muted));
            row.addView(ic);
            TextView label = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = (int) (12 * dp);
            label.setLayoutParams(lp);
            label.setText(labels[i]);
            label.setTextSize(14);
            label.setTypeface(Typefaces.interMedium(this));
            label.setTextColor(Fx.color(this, idx == 3
                    ? R.color.danger : R.color.home_ink));
            row.addView(label);
            row.setOnClickListener(v -> {
                if (chatMenu != null) {
                    try { chatMenu.dismiss(); } catch (Throwable ignored) {}
                    chatMenu = null;
                }
                actions[idx].run();
            });
            box.addView(row);
        }

        PopupWindow pop = new PopupWindow(box,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setOutsideTouchable(true);
        pop.setElevation(10 * dp);
        pop.setBackgroundDrawable(getResources()
                .getDrawable(R.drawable.dialog_bg));
        pop.showAsDropDown(anchor, -(int) (150 * dp), 0);
        chatMenu = pop;
    }

    private PopupWindow chatMenu;

    private void askRename(final ChatSync.Session s) {
        final EditText name = new EditText(this);
        name.setText(s.name == null ? "" : s.name);
        name.setHint(R.string.ai_menu_rename);
        float dp = getResources().getDisplayMetrics().density;
        name.setPadding((int) (14 * dp), (int) (10 * dp),
                (int) (14 * dp), (int) (10 * dp));
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setTitle(R.string.ai_menu_rename)
                .setView(name)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String t = name.getText().toString().trim();
                    if (t.isEmpty()) return;
                    s.name = t;
                    persistCache();
                    ChatSync.save(this, s, null);
                    sortSessions();
                    rebuildDrawer();
                    if (s.id.equals(currentId)) updateTitle();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteSession(final ChatSync.Session target) {
        // students never delete chats (owner order v1.2.0)
        if (!st.teacherPower()) {
            Ui.toast(this, getString(R.string.ai_delete_staff_only));
            return;
        }
        new AlertDialog.Builder(this, R.style.Theme_XavierDrive_Dialog)
                .setMessage(R.string.ai_delete_chat_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    sessions.remove(target);
                    pins.remove(target.id);
                    ChatSync.delete(this, target, null);
                    if (target.id.equals(currentId)) {
                        currentId = sessions.isEmpty()
                                ? ChatSync.newId() : sessions.get(0).id;
                    }
                    persistCache();
                    rebuildDrawer();
                    renderSession();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Compact relative time for the drawer ("2 h ago"). */
    private String whenText(long ts) {
        if (ts <= 0) return "";
        long mins = (System.currentTimeMillis() - ts) / 60000L;
        if (mins < 1) return getString(R.string.ai_time_now);
        if (mins < 60) return mins + " " + getString(R.string.ai_time_min);
        long hours = mins / 60;
        if (hours < 24) return hours + " " + getString(R.string.ai_time_hour);
        long days = hours / 24;
        if (days < 7) return days + " " + getString(R.string.ai_time_day);
        return Ui.longDate(new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(new java.util.Date(ts)).substring(0, 10));
    }
}
