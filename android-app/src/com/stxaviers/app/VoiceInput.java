package com.stxaviers.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice input (v1.2.0) — the mic button in the AI composer.
 *
 * TWO engines, one interface:
 *  1. Devices WITH a system SpeechRecognizer use it on-device (fast,
 *     partial results) — exactly the v1.1.2 flow.
 *  2. Devices WITHOUT one (the owner's, notably — v1.1.2 showed "voice
 *     mode is not supported" forever) record a short AAC clip with
 *     MediaRecorder and send it to the worker's /api/stt (Whisper) for
 *     transcription. Voice input now works on EVERY device.
 *
 * The clip records until the user taps the mic again (or 20 s safety
 * cap), then transcribes and lands in the box for review.
 */
public final class VoiceInput implements RecognitionListener {

    /** Callbacks on the UI thread. */
    public interface Events {
        /** Ready — the device is listening. */
        void onListening();
        /** Partial/final transcript to place in the input. */
        void onText(String text, boolean isFinal);
        /** Listening ended (by result, error, or stop()). */
        void onDone();
    }

    private static final int SERVER_MAX_MS = 20000;

    private final Activity activity;
    private final Events events;
    private SpeechRecognizer recognizer;
    private boolean alive;

    // server (Whisper) fallback state
    private MediaRecorder recorder;
    private File clipFile;
    private boolean serverMode;
    private long serverStart;
    private Thread transcriber;

    public VoiceInput(Activity a, Events events) {
        this.activity = a;
        this.events = events;
    }

    /** True when the device provides speech recognition. */
    public static boolean available(Context c) {
        try {
            return SpeechRecognizer.isRecognitionAvailable(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Start listening (call after the RECORD_AUDIO grant). */
    public void start() {
        if (available(activity)) {
            startOnDevice();
        } else {
            startServer();
        }
    }

    // ── engine 1: the system recognizer ────────────────────────────────

    private void startOnDevice() {
        stop();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(this);
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault());
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            alive = true;
            recognizer.startListening(i);
        } catch (Throwable t) {
            alive = false;
            // the recognizer can die at creation — fall back to the server
            startServer();
        }
    }

    // ── engine 2: record + /api/stt (Whisper) ──────────────────────────

    private void startServer() {
        stop();
        serverMode = true;
        try {
            File dir = new File(activity.getCacheDir(), "stt");
            if (!dir.exists()) dir.mkdirs();
            clipFile = new File(dir, "clip_"
                    + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(16000);
            recorder.setOutputFile(clipFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            serverStart = System.currentTimeMillis();
            alive = true;
            if (events != null) events.onListening();
            // safety cap: stop automatically after 20 s
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        if (alive && serverMode
                                && System.currentTimeMillis() - serverStart
                                        >= SERVER_MAX_MS - 250) {
                            stop();
                        }
                    }, SERVER_MAX_MS);
        } catch (Throwable t) {
            alive = false;
            serverMode = false;
            Ui.toast(activity, activity.getString(
                    R.string.ai_voice_unavailable));
            if (events != null) events.onDone();
        }
    }

    /** Stop whatever engine is running; server mode transcribes on stop. */
    public void stop() {
        alive = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Throwable ignored) {}
            try {
                recorder.release();
            } catch (Throwable ignored) {}
            recorder = null;
            if (serverMode) {
                transcribe();
                return;
            }
        }
        serverMode = false;
        try {
            if (recognizer != null) {
                recognizer.destroy();
            }
        } catch (Throwable ignored) {}
        recognizer = null;
    }

    /** The recorded clip → /api/stt → text. */
    private void transcribe() {
        final File f = clipFile;
        clipFile = null;
        if (transcriber != null && transcriber.isAlive()) return;
        if (f == null || !f.exists() || f.length() < 1200) {
            serverMode = false;
            if (events != null) events.onDone();
            return;
        }
        transcriber = new Thread(() -> {
            String text = null;
            String err = null;
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                FileInputStream fi = new FileInputStream(f);
                byte[] buf = new byte[16384];
                int n;
                while ((n = fi.read(buf)) > 0) bos.write(buf, 0, n);
                fi.close();
                JSONObject body = new JSONObject();
                body.put("audioBase64",
                        android.util.Base64.encodeToString(bos.toByteArray(),
                                android.util.Base64.NO_WRAP));
                body.put("mimeType", "audio/mp4");
                ApiClient.Resp r = ApiClient.requestJson("POST",
                        "/api/stt", body);
                if (r.ok && r.json != null) {
                    text = r.json.optString("text", "");
                } else {
                    err = r.error().isEmpty() ? ("HTTP " + r.code)
                            : r.error();
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? String.valueOf(t)
                        : t.getMessage();
            }
            try { f.delete(); } catch (Throwable ignored) {}
            final String out = text, e = err;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> {
                        serverMode = false;
                        if (events != null) {
                            if (out != null && !out.isEmpty()) {
                                events.onText(out, true);
                            } else if (e != null) {
                                Ui.toast(activity, e);
                            }
                            events.onDone();
                        }
                    });
        }, "xd-stt");
        transcriber.start();
    }

    // ── RecognitionListener (engine 1) ─────────────────────────────────

    @Override public void onReadyForSpeech(Bundle params) {
        if (events != null) events.onListening();
    }

    @Override public void onBeginningOfSpeech() {}

    @Override public void onRmsChanged(float rmsdB) {}

    @Override public void onBufferReceived(byte[] buffer) {}

    @Override public void onEndOfSpeech() {}

    @Override public void onError(int error) {
        // errors 6 (no speech) and 7 (no match) are just quiet taps
        if (alive && (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            if (events != null) events.onDone();
            return;
        }
        if (alive && error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            Ui.toast(activity, activity.getString(
                    R.string.ai_voice_unavailable));
        }
        if (events != null) events.onDone();
    }

    @Override public void onResults(Bundle results) {
        if (!alive) return;
        ArrayList<String> list = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty() && events != null) {
            events.onText(list.get(0), true);
        }
        if (events != null) events.onDone();
    }

    @Override public void onPartialResults(Bundle partialResults) {
        if (!alive || events == null) return;
        ArrayList<String> list = partialResults.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) {
            events.onText(list.get(0), false);
        }
    }

    @Override public void onEvent(int eventType, Bundle params) {}
}
