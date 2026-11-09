package com.midu.tts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.os.Build;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.text.TextUtils;
import android.util.Log;

import com.midu.tts.engine.EngineController;
import com.midu.tts.rules.RuleManager;
import com.midu.tts.rules.RuleUpdateService;
import com.midu.tts.sound.SoundProfile;
import com.midu.tts.sound.SoundProfiles;
import com.midu.tts.util.Prefs;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 系统「文字转语音（TTS）」里可见、可选、可设为默认的引擎。 */
public class MiduTtsService extends TextToSpeechService {

    private static final String TAG = "MiduTtsService";
    private static final int SAMPLE_RATE = 24000;   // BV001 这一代；换 16k 代要同步改 C 层选项

    private BroadcastReceiver rulesReceiver;

    @Override public void onCreate() {
        super.onCreate();
        RuleUpdateService.schedule(this);
        EngineController.get(this).invalidate();
        rulesReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                Log.i(TAG, "规则已更新，重建引擎");
                EngineController.get(c).invalidate();
            }
        };
        IntentFilter f = new IntentFilter(RuleManager.ACTION_RULES_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rulesReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rulesReceiver, f);
    }

    @Override public void onDestroy() {
        if (rulesReceiver != null) { try { unregisterReceiver(rulesReceiver); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    private static boolean isChinese(String lang) {
        return lang != null && (lang.equalsIgnoreCase("zho") || lang.equalsIgnoreCase("zh")
                || lang.equalsIgnoreCase("chi") || lang.equalsIgnoreCase("zh-CN"));
    }

    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (!isChinese(lang)) return TextToSpeech.LANG_NOT_SUPPORTED;
        // 语音包是否已就位由 synthesize 阶段真实判据兜底（冒烟），这里只报语言能力。
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    @Override protected String[] onGetLanguage() {
        return new String[]{"zho", "CHN", ""};
    }

    @Override protected Set<String> onGetFeaturesForLanguage(String lang, String country, String variant) {
        return Collections.emptySet();
    }

    @Override protected void onStop() {
        EngineController.get(this).stop();
    }

    @Override public List<Voice> onGetVoices() {
        List<Voice> out = new ArrayList<>();
        String[] names = EngineController.get(this).voices();
        if (names == null || names.length == 0) names = new String[]{Prefs.get(this).voice()};
        for (String n : names) {
            out.add(new Voice(n, new Locale("zh", "CN"), Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL, false, Collections.<String>emptySet()));
        }
        return out;
    }

    @Override public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return Prefs.get(this).voice();
    }

    @Override public int onIsValidVoiceName(String voiceName) {
        String[] names = EngineController.get(this).voices();
        if (names != null) for (String n : names) if (n.equals(voiceName)) return TextToSpeech.SUCCESS;
        return voiceName.equals(Prefs.get(this).voice()) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    /** rig §7.7：一次一句喂最稳；长文本在这里切开再拼 PCM。 */
    private static List<String> splitText(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            cur.append(ch);
            boolean breakHere = ch == '\n' || ch == '。' || ch == '！' || ch == '？'
                    || ch == '；' || (cur.length() >= 60 && (ch == '，' || ch == ','));
            if (breakHere || cur.length() >= 120) {
                String s = cur.toString().trim();
                if (!s.isEmpty()) out.add(s);
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String text = request.getText();
        if (TextUtils.isEmpty(text)) {
            callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1);
            callback.done();
            return;
        }
        // SynthesisRequest 的 rate/pitch 是百分数（100=正常），叠乘在当前声音预设上。
        int rate = request.getSpeechRate() > 0 ? request.getSpeechRate() : 100;
        int pit = request.getPitch() > 0 ? request.getPitch() : 100;
        SoundProfile profile = SoundProfiles.active(this);

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        try {
            List<String> parts = splitText(text);
            if (parts.isEmpty()) parts = Collections.singletonList(text);
            for (String part : parts) {
                byte[] chunk = EngineController.get(this).synthesize(part, profile, rate, pit);
                pcm.write(chunk);
            }
        } catch (Throwable t) {
            Log.e(TAG, "合成失败", t);
            callback.error(TextToSpeech.ERROR_SYNTHESIS);
            return;
        }
        byte[] all = pcm.toByteArray();
        if (all.length == 0) { callback.error(TextToSpeech.ERROR_SYNTHESIS); return; }

        callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1);
        int max = Math.max(4096, callback.getMaxBufferSize());
        for (int off = 0; off < all.length; off += max) {
            callback.audioAvailable(all, off, Math.min(max, all.length - off));
        }
        callback.done();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
