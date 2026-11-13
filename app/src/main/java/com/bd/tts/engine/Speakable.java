package com.bd.tts.engine;

/**
 * 合成文本的可发音性判定与静音兜底。
 *
 * 阅读 App 会把章节切成句子，其中常夹着只含引号、破折号、空白的片段
 * （Legado 实测会单独传来一个右引号 “`”`）。离线引擎 frontend 对这种文本报
 * `no valid char after filtering` / `pipeline has error, illegal text!`，
 * 既不出 PCM 也不给结束标记；常驻 daemon 因此一直等到超时，且它单线程
 * accept，后续请求全被堵在 backlog —— 表现就是「朗读一句就停，再点没反应」。
 *
 * 这里在最上层拦掉这类片段，补一帧短静音让它正常收尾，不进引擎。
 */
public final class Speakable {
    private static final int SAMPLE_RATE = 24000;
    private static final int BYTES_PER_SAMPLE = 2;

    private Speakable() {}

    /** 是否至少含一个字母、数字或汉字等可发音字符。 */
    public static boolean hasReadable(String text) {
        if (text == null) return false;
        int i = 0, n = text.length();
        while (i < n) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** 24 kHz / 16 bit / 单声道的静音帧。 */
    public static byte[] silence(int ms) {
        int len = SAMPLE_RATE * BYTES_PER_SAMPLE * Math.max(1, ms) / 1000;
        return new byte[len - (len % BYTES_PER_SAMPLE)];
    }

    /** 无内容片段的默认占位：80 ms，听感上等于句间自然停顿。 */
    public static byte[] shortSilence() { return silence(80); }
}
