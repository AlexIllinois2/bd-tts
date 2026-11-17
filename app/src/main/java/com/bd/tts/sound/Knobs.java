package com.bd.tts.sound;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * libspeechengine.so 的发音可调能力总表（来源：docs/PRONUNCIATION_KNOBS.md 的 REA 静态盘点
 * + host2 rig 实测）。UI、Legado HTTP 参数覆盖、预设序列化都以这张表为唯一真相源。
 *
 * 不在这里出现的键：
 *   - rig 钉死的初始化档位（见 fake_jni.c 的 RESERVED_KEYS）：改了会直接破坏授权 / PCM 回调；
 *   - 在线链路（tts_voice_online / tts_uri / tts_address / tts_cluster / tts_conn_timeout）：
 *     本工程只用离线 pre_bind，暴露出来没有意义。
 */
public final class Knobs {
    private Knobs() {}

    public static final String GROUP_BASE      = "韵律";
    public static final String GROUP_RATIO     = "韵律 · 比例档";
    public static final String GROUP_SEGMENT   = "分句与停顿";
    public static final String GROUP_FRONTEND  = "前端与发音覆写";
    public static final String GROUP_EMOTION   = "情感与风格";
    public static final String GROUP_OUTPUT    = "音频输出";
    public static final String GROUP_LOCALOUT  = "音频输出 · 本地后处理";
    public static final String GROUP_CALLBACK  = "回调与性能";
    public static final String GROUP_CLONE     = "声音克隆（整条链未验）";

    public static final String K_SPEED   = "tts_speed";
    public static final String K_PITCH   = "tts_pitch";
    public static final String K_VOLUME  = "tts_volume";

    /**
     * 本地后处理键（bd_ 前缀）：不下发引擎，由 app 在 PCM 出口做后处理。
     * SoundProfile.toOptions() 会跳过这类键，Legado HTTP 仍可按键覆盖。
     */
    public static final String K_STEREO_WIDTH = "bd_stereo_width";

    /** 是否是本地后处理键（不进引擎选项）。 */
    public static boolean isLocalOnly(String key) {
        return key != null && key.startsWith("bd_");
    }

    private static final List<Knob> ALL = new ArrayList<>();
    private static final Map<String, Knob> BY_KEY = new LinkedHashMap<>();

    private static void add(Knob k) { ALL.add(k); BY_KEY.put(k.key, k); }

    static {
        // ---- 1) rig 逐条实测 / 本工程已接 ------------------------------------
        add(Knob.builder(K_SPEED, "语速", GROUP_BASE)
                .integer(10, 1, 20).unit("档")
                .desc("引擎整数档，10 为正常。系统 TTS 的 speechRate% 会叠乘在这一档上。")
                .evidence(Knob.VERIFIED).build());
        add(Knob.builder(K_PITCH, "音高", GROUP_BASE)
                .integer(10, 1, 20).unit("档")
                .desc("引擎整数档，10 为正常。系统 TTS 的 pitch% 会叠乘在这一档上。")
                .evidence(Knob.VERIFIED).build());
        add(Knob.builder(K_VOLUME, "音量", GROUP_BASE)
                .integer(100, 0, 100).unit("%")
                .desc("0..100，100 为原始幅度。")
                .evidence(Knob.VERIFIED).build());

        // ---- 2) rig env 里验过键存在，本次接进 UI ----------------------------
        add(Knob.builder("tts_silence_duration", "句尾静音", GROUP_SEGMENT)
                .integer(0, 0, 2000).unit("ms")
                .desc("rig 的 TTS_SIL 对应项；实测 0→2000ms 会在句尾插出约 2.6 秒留白。")
                .evidence(Knob.VERIFIED).build());
        add(Knob.builder("tts_sub_sentence_length", "子句长度", GROUP_SEGMENT)
                .integer(0, 0, 200).unit("字")
                .desc("rig 的 TTS_SUBLEN 对应项；长文本实测 0→8 字会明显改变停顿与总时长；"
                        + "0=交给引擎自己分句（大值超过文本长度时等同不切）。")
                .evidence(Knob.VERIFIED).build());
        add(Knob.builder("tts_speed_ratio", "语速比例", GROUP_RATIO)
                .integer(100, 10, 300).unit("%")
                .desc("rig 的 TTS_SPEED_RATIO 对应项；与整数档并存，关系未实测。")
                .evidence(Knob.RIG_ENV).visible(false).build());

        // ---- 3) .so 字符串枚举，未实测；照常显示与下发，调用方参数优先生效 ----
        add(Knob.builder("tts_volume_ratio", "音量比例", GROUP_RATIO)
                .integer(100, 10, 300).unit("%")
                .desc("在 tts_volume 之外的比例型通道；仅静态字符串证据。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_pitch_ratio", "音高比例", GROUP_RATIO)
                .integer(100, 10, 300).unit("%")
                .desc("在 tts_pitch 之外的比例型通道；仅静态字符串证据。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_split_sentence", "启用引擎分句", GROUP_SEGMENT)
                .bool(false).desc("让引擎自行切分子句；本工程默认在 Java 层切句。")
                .evidence(Knob.STATIC).visible(false).build());

        add(Knob.builder("tts_text_type", "文本类型", GROUP_FRONTEND)
                .choices("plain", "ssml")
                .desc("已实测（2026-11-13 真机）：ssml 启用 <phoneme alphabet=\"py\"> 拼音覆写；"
                        + "每句可切，[[词|pin1 yin1]] 内联注音无需手动改这里。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_frontend_overwrite_label", "前端标签覆写", GROUP_FRONTEND)
                .string("")
                .desc("单次合成的拼音/label 覆写串。.so 有 overwrite label 日志，但格式未知；乱填可能只被忽略。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_pure_english_opt", "纯英文优化", GROUP_FRONTEND)
                .integer(0, 0, 2).desc("纯英文文本的 G2P 优化档，取值含义未验。")
                .evidence(Knob.STATIC).visible(false).build());

        add(Knob.builder("tts_emotion", "情感", GROUP_EMOTION)
                .string("").desc("情感名：sad / angry 等实测能改变韵律（时长/能量有差异）；"
                        + "happy 接近默认值，听感不明显。传空串=引擎默认。")
                .evidence(Knob.VERIFIED).build());
        add(Knob.builder("tts_style_name", "风格名", GROUP_EMOTION)
                .string("").desc("风格名，常与 tts_emotion 成组出现；未验。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_voiceclone_style", "克隆风格", GROUP_EMOTION)
                .string("").desc("voiceclone 链路的风格字段；未验。")
                .evidence(Knob.STATIC).visible(false).build());

        add(Knob.builder("tts_fadein_duration", "淡入时长", GROUP_OUTPUT)
                .integer(0, 0, 2000).unit("ms").desc("音频淡入；未验。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_fadeout_duration", "淡出时长", GROUP_OUTPUT)
                .integer(0, 0, 2000).unit("ms").desc("音频淡出；未验。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_encoding", "输出编码", GROUP_OUTPUT)
                .choices("pcm", "wav", "mp3", "opus")
                .desc("可能影响 PCM 回调的内容；改了之后本工程的 s16le 假设可能不成立，谨慎。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_compression_rate", "压缩率", GROUP_OUTPUT)
                .integer(0, 0, 100).unit("%").desc("压缩率；未验。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_audio_path", "落盘目录", GROUP_OUTPUT)
                .string("").desc("引擎直接写音频文件的目录；未验，且本工程走 PCM 回调。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_audio_file", "落盘文件名", GROUP_OUTPUT)
                .string("").desc("引擎直接写音频文件的文件名；未验。")
                .evidence(Knob.STATIC).visible(false).build());

        add(Knob.builder("tts_with_timestamp", "回调带时间戳", GROUP_CALLBACK)
                .bool(false).desc("onSpeechMessage 附带时间戳；未验。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_with_intent", "回调带意图", GROUP_CALLBACK)
                .bool(false).desc("onSpeechMessage 附带意图信息；未验。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_limit_cpu_usage", "限制 CPU", GROUP_CALLBACK)
                .bool(false).desc("降低占用，可能拉长合成耗时；未验。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("tts_concurrency_mode", "并发模式", GROUP_CALLBACK)
                .integer(0, 0, 4).desc("引擎并发档位；本工程合成本身是串行的。")
                .evidence(Knob.STATIC).visible(false).build());

        add(Knob.builder("tts_use_voiceclone", "启用声音克隆", GROUP_CLONE)
                .bool(false).desc("整条 voiceconv 链路未验；开启后可能需要额外模型文件。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("voiceconv_pitch", "变声 · 音高", GROUP_CLONE)
                .integer(50, 0, 100).desc("voiceconv_* 一族，仅静态字符串证据。")
                .evidence(Knob.STATIC).visible(false).build());
        add(Knob.builder("voiceconv_volume", "变声 · 音量", GROUP_CLONE)
                .integer(50, 0, 100).desc("voiceconv_* 一族，仅静态字符串证据。")
                .evidence(Knob.STATIC).visible(false).build());

        // ---- 4) 本地后处理（不发给引擎，app 在 PCM 出口处理）------------------
        add(Knob.builder(K_STEREO_WIDTH, "立体声展宽", GROUP_LOCALOUT)
                .integer(35, 0, 100).unit("%")
                .desc("0 = 原样单声道。>0 时 app 在本地把引擎单声道 PCM 展宽成假立体声"
                        + "（Haas 反相侧链）：耳机 / 立体声喇叭听感更开阔，手机单声道扬声器"
                        + "几乎不受影响。不发给引擎，改完下次合成即生效。")
                .evidence(Knob.VERIFIED).build());
    }

    public static List<Knob> all() { return Collections.unmodifiableList(ALL); }
    public static Knob byKey(String key) { return BY_KEY.get(key); }

    /** 按声明顺序分组，供 UI 生成折叠分组卡片。 */
    public static Map<String, List<Knob>> grouped() {
        return grouped(true);
    }

    /** includeHidden=false 时剔除实测无效项（声音页用），空分组不返回。 */
    public static Map<String, List<Knob>> grouped(boolean includeHidden) {
        Map<String, List<Knob>> out = new LinkedHashMap<>();
        for (Knob k : ALL) {
            if (!includeHidden && !k.visible) continue;
            List<Knob> l = out.get(k.group);
            if (l == null) { l = new ArrayList<>(); out.put(k.group, l); }
            l.add(k);
        }
        return out;
    }

    public static int hiddenCount() {
        int n = 0;
        for (Knob k : ALL) if (!k.visible) n++;
        return n;
    }

    public static int verifiedCount() {
        int n = 0;
        for (Knob k : ALL) if (k.evidence == Knob.VERIFIED) n++;
        return n;
    }
    public static int staticCount() {
        int n = 0;
        for (Knob k : ALL) if (k.evidence == Knob.STATIC) n++;
        return n;
    }

    /** 请求参数是否是一个允许被 URL / body 覆盖的键（含基础三项）。 */
    public static boolean isOverridable(String key) {
        return BY_KEY.containsKey(key);
    }
}
