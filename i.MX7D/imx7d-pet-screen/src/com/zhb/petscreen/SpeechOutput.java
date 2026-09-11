package com.zhb.petscreen;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import java.util.UUID;

/** Small lifecycle wrapper around the installed Android TTS engine. */
final class SpeechOutput {
    interface Listener { void state(String state, String detail); }
    final Listener listener;
    TextToSpeech tts;
    volatile boolean ready;

    SpeechOutput(Context context, Listener listener) {
        this.listener = listener;
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                listener.state("unavailable", "init_" + status);
                return;
            }
            int language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                listener.state("unavailable", "zh_cn_" + language);
                return;
            }
            tts.setSpeechRate(.95f);
            tts.setPitch(1.08f);
            tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String id) { listener.state("speaking", id); }
                public void onDone(String id) { listener.state("completed", id); }
                public void onError(String id) { listener.state("failed", id); }
                public void onError(String id, int code) { listener.state("failed", id + "_" + code); }
            });
            ready = true;
            listener.state("ready", tts.getDefaultEngine());
        });
    }

    boolean speak(String text) {
        if (!ready || text == null || text.trim().isEmpty()) return false;
        String safe = text.trim();
        if (safe.length() > 500) safe = safe.substring(0, 500);
        Bundle params = new Bundle();
        int result = tts.speak(safe, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString());
        return result == TextToSpeech.SUCCESS;
    }

    void stop() { if (tts != null) tts.stop(); }
    void close() { if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } ready = false; }
}
