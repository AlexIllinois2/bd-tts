package com.bd.tts;

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

import com.bd.tts.engine.EngineController;
import com.bd.tts.engine.PcmSink;
import com.bd.tts.engine.Speakable;
import com.bd.tts.rules.RuleManager;
import com.bd.tts.rules.RuleUpdateService;
import com.bd.tts.sound.SoundProfile;
import com.bd.tts.sound.SoundProfiles;
import com.bd.tts.util.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 系统「文字转语音（TTS）」里可见、可选、可设为默认的引擎。 */
public class BdTtsService extends TextToSpeechService {

    private static final String TAG = "BdTtsService";
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
        if (names == null || names.length == 0) {
            String fallback = Prefs.get(this).voice();
            names = (fallback == null || fallback.isEmpty())
                    ? new String[]{"default"} : new String[]{fallback};
        }
        for (String n : names) {
            if (n == null) continue;
            out.add(new Voice(safeVoiceName(n), new Locale("zh", "CN"), Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL, false, Collections.<String>emptySet()));
        }
        return out;
    }

    /**
     * Voice 名转成合法 BCP-47 标签。MIUI 文字转语音设置页会把默认引擎的 Voice 名
     * 当语言标签解析（TtsEngines.getLocaleFromLanguageTag），解析失败得到 null 直接
     * 塞进 ListPreference，渲染时 NPE 整页崩（卸载旧 midu-tts、本引擎设为默认后
     * 实测触发）。子标签限制 [a-z0-9]{1,8} 保证 Locale.forLanguageTag 全程有效。
     * 合成路径从不匹配 voice 名（synthesis 只读 Prefs.voice()），这里改名无副作用。
     */
    private static String safeVoiceName(String raw) {
        StringBuilder sb = new StringBuilder("zh-cn");
        for (String part : raw.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part.isEmpty()) continue;
            sb.append('-').append(part, 0, Math.min(part.length(), 8));
        }
        return sb.toString();
    }

    @Override public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        String v = Prefs.get(this).voice();
        return (v == null || v.isEmpty()) ? "zh-cn-default" : safeVoiceName(v);
    }

    @Override public int onIsValidVoiceName(String voiceName) {
        // 合成用的音色始终以 app 设置为准（EngineController 读 Prefs.voice()），
        // SynthesisRequest 里的 voiceName 不参与选音色。调用方常缓存旧引擎/旧设置的
        // 音色名（例如 BV001_24k_streaming）并 setVoice，若这里判 ERROR 会让它误以为
        // 引擎不可用；放宽校验，避免换了默认引擎或改了音色后朗读直接失效。
        if (TextUtils.isEmpty(voiceName)) return TextToSpeech.ERROR;
        return TextToSpeech.SUCCESS;
    }

    /** rig §7.7：一次一句喂最稳；长文本在这里切开再拼 PCM。纯标点片段直接丢掉。 */
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
                if (Speakable.hasReadable(s)) out.add(s);
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (Speakable.hasReadable(tail)) out.add(tail);
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

        // 流式：第一块 PCM 到达就 start() 并开始喂 audioAvailable，不用等整章合成完。
        // host2 常驻后端逐帧回传；进程内 JNI / 一次性后端退化为「整句一块」。
        final boolean[] started = {false};
        final int max = Math.max(4096, callback.getMaxBufferSize());
        PcmSink sink = (buf, len) -> {
            if (!started[0]) {
                callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1);
                started[0] = true;
            }
            for (int off = 0; off < len; off += max) {
                callback.audioAvailable(buf, off, Math.min(max, len - off));
            }
        };

        List<String> parts = splitText(text);
        if (parts.isEmpty()) {
            // 整段只有标点 / 空白：Legado 会把这种片段单独作为一个 utterance 发来。
            // 必须给一帧静音 + done()，不能 error()，否则 onError 会停掉整章朗读。
            emitSilence(sink);
            callback.done();
            return;
        }

        int failed = 0;
        EngineController ec = EngineController.get(this);
        for (String part : parts) {
            try {
                ec.synthesizeStream(part, profile, rate, pit, sink);
            } catch (Throwable t) {
                // 单句失败只跳过这一句：Legado 靠 onDone 驱动下一句，
                // 一旦 error() 整章朗读就停了。已吐出的 PCM 不重播。
                failed++;
                Log.w(TAG, "跳过一句合成失败（" + part.length() + " 字）：" + t.getMessage());
            }
        }
        if (!started[0]) {
            // 全部句子都失败也补静音正常收尾，别让调用方卡在等 onDone。
            emitSilence(sink);
        }
        if (failed > 0) Log.w(TAG, "本次 " + parts.size() + " 句中 " + failed + " 句失败，已跳过继续");
        callback.done();
    }

    /** PcmSink.onPcm 声明了 throws Exception，这里吞掉静音帧的写出异常。 */
    private static void emitSilence(PcmSink sink) {
        try {
            byte[] s = Speakable.shortSilence();
            sink.onPcm(s, s.length);
        } catch (Exception e) {
            Log.w(TAG, "静音帧写出失败", e);
        }
    }
}
