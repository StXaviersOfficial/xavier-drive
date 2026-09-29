package com.stxaviers.app;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Listen-to-a-response (v1.2.0) — TWO engines, one button.
 *
 *  1. CLOUD: /api/tts → Groq Orpheus WAV in the website's six voices
 *     (austin…hannah). When the Groq keys are healthy this is the voice
 *     the website plays.
 *  2. ON-DEVICE (the "bundled model" the owner ordered): Android's own
 *     TextToSpeech engine — installed on every phone, works offline.
 *     The automatic fallback the moment the cloud voice fails, so Listen
 *     NEVER dies.
 *
 * One response plays at a time: start() stops whatever is running. All
 * network work stays on a background thread; callbacks land on the UI
 * thread.
 */
public final class TtsPlayer {

    public interface State {
        void onState(boolean playing);
        void onError(String message);
    }

    private static MediaPlayer player;
    private static File lastFile;
    private static String lastKey = "";
    private static State lastCb;
    private static final Handler H = new Handler(Looper.getMainLooper());

    // the on-device engine (lazily created, process-wide)
    private static android.speech.tts.TextToSpeech deviceTts;
    private static boolean deviceTtsReady;

    private TtsPlayer() {}

    /** The six website voices, labels + descriptions. */
    public static final String[] VOICES = {
            "austin", "daniel", "troy", "autumn", "diana", "hannah"
    };
    public static final String[] VOICE_LABELS = {
            "Austin — clear & friendly",
            "Daniel — warm & conversational",
            "Troy — deep & authoritative",
            "Autumn — smooth & natural",
            "Diana — calm & professional",
            "Hannah — bright & expressive",
    };

    public static boolean isPlaying() {
        try {
            if (player != null && player.isPlaying()) return true;
        } catch (Throwable t) {}
        try {
            return deviceTts != null && deviceTts.isSpeaking();
        } catch (Throwable t) {}
        return false;
    }

    public static void stop() {
        try {
            if (player != null) {
                player.stop();
                player.release();
            }
        } catch (Throwable ignored) {}
        player = null;
        try {
            if (deviceTts != null && deviceTtsReady) {
                deviceTts.stop();
            }
        } catch (Throwable ignored) {}
        // let the button that started this playback reset itself
        final State cb = lastCb;
        lastCb = null;
        if (cb != null) {
            H.post(() -> cb.onState(false));
        }
    }

    /**
     * Speak `text` with `voice`. Downloads the WAV once per text+voice
     * (replays reuse the cached file), then plays it; when the cloud
     * voice is unavailable, the phone's own engine reads it instead.
     */
    public static void speak(final Context c, final String text,
                             final String voice, final State cb) {
        if (text == null || text.trim().isEmpty()) return;
        final String trimmed = text.trim().length() > 1400
                ? text.trim().substring(0, 1400) : text.trim();
        final String v = voice == null || voice.isEmpty()
                ? "austin" : voice;
        final String key = v + "::" + Integer.toHexString(trimedHash(trimmed));

        stop();
        lastCb = cb;
        H.post(() -> { if (cb != null) cb.onState(true); });

        new Thread(() -> {
            File wav = null;
            String err = null;
            if (key.equals(lastKey) && lastFile != null
                    && lastFile.exists()) {
                wav = lastFile;                      // replay
            } else {
                try {
                    JSONObject body = ApiClient.obj("text", trimmed,
                            "voice", v);
                    byte[] bytes = ApiClient.requestBytesReturn(
                            "/api/tts", body);
                    if (bytes == null || bytes.length == 0) {
                        throw new Exception("empty audio");
                    }
                    File dir = new File(c.getCacheDir(), "tts");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, key + ".wav");
                    FileOutputStream fo = new FileOutputStream(out);
                    fo.write(bytes);
                    fo.close();
                    wav = out;
                    lastFile = out;
                    lastKey = key;
                } catch (Throwable t) {
                    err = t.getMessage() == null ? String.valueOf(t)
                            : t.getMessage();
                }
            }

            if (wav != null) {
                final File play = wav;
                H.post(() -> {
                    try {
                        player = new MediaPlayer();
                        player.setDataSource(play.getAbsolutePath());
                        player.setOnCompletionListener(mp -> {
                            stop();
                            if (cb != null) cb.onState(false);
                        });
                        player.setOnErrorListener((mp, what, extra) -> {
                            stop();
                            if (cb != null) cb.onError("playback");
                            return true;
                        });
                        player.prepare();
                        player.start();
                    } catch (Throwable t) {
                        // even a downloaded WAV can fail on some devices —
                        // the on-device engine is the last resort
                        speakOnDevice(c, trimmed, cb);
                    }
                });
            } else {
                // cloud voice failed → the phone's own voice, immediately
                speakOnDevice(c, trimmed, cb);
            }
        }, "xd-tts").start();
    }

    /** The on-device engine (Android's bundled TTS — offline, always on). */
    private static void speakOnDevice(final Context c, final String text,
                                      final State cb) {
        H.post(() -> {
            try {
                if (deviceTts == null) {
                    deviceTts = new android.speech.tts.TextToSpeech(
                            c.getApplicationContext(), status -> {
                        deviceTtsReady = status
                                == android.speech.tts.TextToSpeech.SUCCESS;
                        if (deviceTtsReady) {
                            utterOnDevice(text, cb);
                        } else {
                            failed(cb);
                        }
                    });
                } else if (deviceTtsReady) {
                    utterOnDevice(text, cb);
                } else {
                    failed(cb);
                }
            } catch (Throwable t) {
                failed(cb);
            }
        });
    }

    private static void utterOnDevice(String text, State cb) {
        try {
            final State callback = cb;
            int r = deviceTts.speak(text,
                    android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                    null, "xd-tts");
            if (r != android.speech.tts.TextToSpeech.SUCCESS) {
                failed(cb);
            }
            // poll for finish so the button resets
            H.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (deviceTts != null && deviceTts.isSpeaking()) {
                            H.postDelayed(this, 400L);
                        } else {
                            lastCb = null;
                            if (callback != null) callback.onState(false);
                        }
                    } catch (Throwable t) {
                        if (callback != null) callback.onState(false);
                    }
                }
            }, 400L);
        } catch (Throwable t) {
            failed(cb);
        }
    }

    private static void failed(State cb) {
        lastCb = null;
        if (cb != null) cb.onError("tts unavailable");
    }

    private static int trimedHash(String s) {
        return s == null ? 0 : s.hashCode();
    }
}
