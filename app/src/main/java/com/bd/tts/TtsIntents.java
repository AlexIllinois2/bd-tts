package com.bd.tts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Collections;

/** 系统 TTS 设置会发的三个辅助意图：CHECK_TTS_DATA / GET_SAMPLE_TEXT /（INSTALL 交给设置页）。 */
public final class TtsIntents {
    private TtsIntents() {}

    public static class Check extends Activity {
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            // EXTRA_AVAILABLE_VOICES 的契约是 locale 字符串（如 zho-CHN），不是音色名。
            // AOSP 文字转语音设置页用它填语言下拉：entries 数组按【原始列表长度+1】分配、
            // 却只填 parseLocaleString 非空的项——列表里混进音色名（BV003_streaming）
            // 会解析失败，数组尾部留下 null，MIUI 设置页渲染即 NPE 整页崩。
            // （本引擎实际语言能力见 BdTtsService.onIsLanguageAvailable：仅中文。）
            Intent r = new Intent();
            r.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                    new ArrayList<>(Collections.singletonList("zho-CHN")));
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
