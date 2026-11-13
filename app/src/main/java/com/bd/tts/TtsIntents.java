package com.bd.tts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import com.bd.tts.engine.EngineController;
import com.bd.tts.util.Prefs;

import java.util.ArrayList;

/** 系统 TTS 设置会发的三个辅助意图：CHECK_TTS_DATA / GET_SAMPLE_TEXT /（INSTALL 交给设置页）。 */
public final class TtsIntents {
    private TtsIntents() {}

    public static class Check extends Activity {
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            ArrayList<String> voices = new ArrayList<>();
            try {
                String[] v = EngineController.get(this).voices();
                if (v != null && v.length > 0) for (String s : v) voices.add(s);
            } catch (Throwable ignored) {}
            if (voices.isEmpty()) voices.add(Prefs.get(this).voice());
            Intent r = new Intent();
            r.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, voices);
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, r);
            finish();
        }
    }

    public static class Sample extends Activity {
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            Intent r = new Intent();
            r.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, getString(R.string.sample_text));
            setResult(RESULT_OK, r);
            finish();
        }
    }
}
