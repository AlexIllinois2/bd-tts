package com.bd.tts.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用方内联拼音：把文本里的 {@code [[词|pin1 yin1]]} 转成引擎的 SSML phoneme 覆写。
 *
 * 引擎侧事实（2026-11-13 Redmi Note 8 Pro / host2 daemon 实测）：
 *   - {@code tts_text_type=ssml} 可以每句通过 setOption 切换（不在 RESERVED_KEYS /
 *     SVO_RESERVED 名单里），普通句子切回 plain 无残留；
 *   - {@code <phoneme alphabet="py" ph="zha1 dai4">炸袋</phoneme>} 会逐字覆写拼音
 *     （日志 {@code overwrite pinyin to zha1, word_index:4, text:炸}）；
 *   - 一句里可以有任意多个 phoneme 节点；
 *   - ph 里的拼音个数必须与节点文本的汉字个数严格相等，否则整句近乎静音
 *     （{@code py text size: 2 does not match pinyins size:1}，实测只出 3840B）。
 *
 * 因此这里的约定：
 *   - 语法：{@code [[字词|拼音 拼音 …]]}，拼音用空格分隔，逐字对应，声调数字 1-5；
 *   - 任一校验不过（字数≠拼音数、拼音格式错、含非汉字）→ 只摘掉 [[ ]] 标记、
 *     按普通文本朗读，绝不把坏 SSML 喂给引擎；
 *   - 全部合法 → 包一层 {@code <speak>} 并把 ssml 置 true，由 EngineController
 *     给本句追加/覆盖 {@code tts_text_type=ssml}；
 *   - 调用方直接传整段 SSML（以 {@code <speak>} 开头）也原样放行并置 ssml。
 */
public final class PinyinInline {
    /** 转换结果：ssml=true 时 text 已是引擎可用的 SSML，需要 tts_text_type=ssml。 */
    public static final class Result {
        public final String text;
        public final boolean ssml;
        Result(String t, boolean s) { text = t; ssml = s; }
    }

    /** [[词|拼音…]]；词内不许出现 | [ ]，拼音列最长 80。 */
    private static final Pattern TAG =
            Pattern.compile("\\[\\[([^|\\[\\]]{1,24})\\|([^\\[\\]]{1,80})\\]\\]");
    /** 单个音节：小写字母（含 ü/v）+ 声调 1-5。none5 这类占位符只属于规则层，这里不认。 */
    private static final Pattern SYLLABLE = Pattern.compile("[a-züv]+[1-5]");

    private PinyinInline() {}

    public static Result convert(String text) {
        if (text == null) return new Result("", false);
        if (text.trim().startsWith("<speak>")) return new Result(text, true);

        Matcher m = TAG.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 64);
        int last = 0;
        boolean anyValid = false;
        while (m.find()) {
            String word = m.group(1).trim();
            String ph = m.group(2).trim();
            boolean valid = valid(word, ph);
            appendEscaped(sb, text, last, m.start());
            if (valid) {
                sb.append("<phoneme alphabet=\"py\" ph=\"").append(ph).append("\">")
                        .append(word).append("</phoneme>");
                anyValid = true;
            } else {
                // 校验不过：摘掉标记按普通文本读，不给引擎喂坏 SSML。
                appendEscaped(sb, word, 0, word.length());
            }
            last = m.end();
        }
        if (last < text.length()) appendEscaped(sb, text, last, text.length());
        if (!anyValid) return new Result(sb.toString(), false);
        return new Result("<speak>" + sb + "</speak>", true);
    }

    /** 词必须是纯汉字，且拼音音节个数与汉字个数严格相等（引擎硬约束，见类注释）。 */
    private static boolean valid(String word, String ph) {
        if (word.isEmpty() || ph.isEmpty()) return false;
        int chars = 0;
        int i = 0, n = word.length();
        while (i < n) {
            int cp = word.codePointAt(i);
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) return false;
            chars++;
            i += Character.charCount(cp);
        }
        String[] tokens = ph.split("\\s+");
        if (tokens.length != chars) return false;
        for (String t : tokens) if (!SYLLABLE.matcher(t).matches()) return false;
        return true;
    }

    /** 把 text 的 [from,to) 片段做 XML 转义后追加。 */
    private static void appendEscaped(StringBuilder sb, String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else sb.append(c);
        }
    }
}
