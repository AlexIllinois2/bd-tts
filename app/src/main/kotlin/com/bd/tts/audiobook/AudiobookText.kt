package com.bd.tts.audiobook

/**
 * 朗读文本的「显示版」清洗，只给界面用。
 *
 * .tts 里的 `[[词|pin1 yin1]]` 是给 `PinyinInline` 生成 SSML 用的源码，
 * 直接摆在播放器上像代码、不易读。这里把它还原成纯文本：
 *   `他停下脚步，想起[[重|chong2]]逢的那个下午。` → `他停下脚步，想起重逢的那个下午。`
 *
 * 合成路径始终读原文，**不经过本对象**；因此显示得再干净也不影响发音。
 */
object AudiobookText {
    // 与 PinyinInline.TAG 同形但更宽松：显示层不管拼音是否合法，只取竖线左侧的词。
    private val INLINE = Regex("\\[\\[([^\\[\\]|]*)\\|([^\\[\\]]*)\\]\\]")
    // 偶尔有人写 [[词]]；只处理成对的，避免误伤正文里正常的 [ ]。
    private val BARE = Regex("\\[\\[([^\\[\\]|]{1,32})\\]\\]")
    private val XML_TAG = Regex("<[^<>]*>")
    private val RUNNING_SPACE = Regex("[ \\t\\u00A0]{2,}")

    // 句末标点：中英文句号/问号/叹号/分号 + 中文省略号。
    private val END_CHARS = "。！？；!?;…"
    // 句末可连吞的收尾字符（连续标点、右引号/括号），避免拆出孤零零的 ") 。"
    // 必须包含中文弯引号 ” ’ ：对话密集的书几乎每段都以 。” 结尾，
    // 漏掉会把 ” 单独切成纯标点碎片——引擎只会为它合成几秒静音（表现为段尾停顿），
    // 还白耗一次合成往返。
    private val TRAIL_CHARS = "。！？；!?;…\"'”）】》」』’"
    // 句内次级停顿标点：长句在这些位置切成更小的合成单元，缩短单次合成耗时。
    // 不含句末标点（那些已由 sentences 切开），也不含 | [ ]（注音标记内部字符）。
    private val SUB_CHARS = "，、：,:"

    /**
     * 把一段文本按句末标点切成朗读单元，保留标点，过滤空白段。
     * 小说里的一行常有多句；按句合成能让首句更快出声，也便于预合成下一句。
     * 英文句点只有在前后都不是数字时才当句末（避免 3.14 被拆开）。
     */
    fun sentences(raw: String): List<String> {
        val s = raw.trim()
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            var isEnd = c in END_CHARS
            if (c == '.') {
                val prev = if (i > 0) s[i - 1] else ' '
                val next = if (i + 1 < s.length) s[i + 1] else ' '
                isEnd = !prev.isDigit() && !next.isDigit()
            }
            if (isEnd) {
                var end = i + 1
                while (end < s.length && s[end] in TRAIL_CHARS) end++
                val piece = s.substring(start, end).trim()
                if (piece.isNotEmpty()) out.add(piece)
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start < s.length) {
            val tail = s.substring(start).trim()
            if (tail.isNotEmpty()) out.add(tail)
        }
        // 孤立的收尾标点/引号碎片不值得单独成为一句（引擎只会给几秒静音或杂音），
        // 并进前一句；落在最前面时保留原样。与 chunks() 的 tidy 规则一致。
        val tidy = ArrayList<String>(out.size)
        for (p in out) {
            if (p.length <= 2 && p.none { it.isLetterOrDigit() } && tidy.isNotEmpty()) {
                tidy[tidy.size - 1] = tidy.last() + p
            } else {
                tidy.add(p)
            }
        }
        return tidy
    }

    /**
     * 把一个句子进一步切成「合成片段」，让单次合成耗时可控。
     *
     * host2 合成耗时随字数增长，一句几百字时预合成来不及在当前句播放期间完成，
     * 句间就会卡顿。这里在次级标点处把长句拆成多片（每片尽量接近 targetChars），
     * 由服务的预合成队列提前备好；播放器按片段推进，界面仍按整句高亮。
     *
     * - 短句（<= targetChars）原样返回，不增加额外停顿。
     * - 尽量在标点后切；找不到标点时按字数硬切，保证单片不会过长。
     * - 切点只落在标点 / 普通字符后，不会劈开 `[[词|pin1 yin1]]` 注音标记
     *   （标记内部只有汉字、字母、数字、| 和空格，不含这里用到的标点）。
     */
    fun chunks(sentence: String, targetChars: Int = 90): List<String> {
        val s = sentence.trim()
        if (s.isEmpty()) return emptyList()
        if (s.length <= targetChars) return listOf(s)

        val out = ArrayList<String>()
        var start = 0
        var lastBreak = -1       // 最近一个可用切点（标点之后）
        var i = 0
        var inTag = false        // 正处于 [[…]] 注音标记内部：绝不在此切分
        while (i < s.length) {
            val c = s[i]
            if (!inTag && c == '[' && i + 1 < s.length && s[i + 1] == '[') {
                inTag = true; i += 2; continue
            }
            if (inTag && c == ']' && i + 1 < s.length && s[i + 1] == ']') {
                inTag = false; i += 2; continue
            }
            if (!inTag && c in SUB_CHARS) lastBreak = i + 1
            val run = i - start + 1
            if (run >= targetChars && !inTag) {
                var cut = if (lastBreak > start) lastBreak else i + 1
                // 别把生僻字的代理对劈成孤立高代理
                if (cut > 0 && Character.isHighSurrogate(s[cut - 1])) cut -= 1
                if (cut <= start) { i++; continue }   // 极端回退重叠，防死循环
                val piece = s.substring(start, cut).trim()
                if (piece.isNotEmpty()) out.add(piece)
                start = cut
                lastBreak = -1
                i = cut
            } else {
                i++
            }
        }
        if (start < s.length) {
            val tail = s.substring(start).trim()
            if (tail.isNotEmpty()) out.add(tail)
        }
        // 纯标点的超短碎片不值得单独合成一次（引擎遇到不可读片段只会给短静音），
        // 并进前一片；落在最前面时保留原样。
        val tidy = ArrayList<String>(out.size)
        for (p in out) {
            if (p.length <= 2 && p.none { it.isLetterOrDigit() } && tidy.isNotEmpty()) {
                tidy[tidy.size - 1] = tidy.last() + p
            } else {
                tidy.add(p)
            }
        }
        return tidy
    }

    /** 转成可读纯文本；limit 按字符数截断，避免在界面上铺太长。 */
    fun display(raw: String, limit: Int = Int.MAX_VALUE): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""

        // 整段 SSML 也是注音文本的一种写法：去掉标签、还原 XML 实体。
        if (s.startsWith("<speak", ignoreCase = true)) {
            s = unescapeXml(XML_TAG.replace(s, "")).trim()
        }
        s = INLINE.replace(s) { it.groupValues[1] }
        s = BARE.replace(s) { it.groupValues[1] }
        s = RUNNING_SPACE.replace(s, " ")

        return if (limit > 0 && s.length > limit) s.substring(0, limit) else s
    }

    // &amp; 必须最后还原，否则 &amp;lt; 会被二次解码成 '<'。
    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
