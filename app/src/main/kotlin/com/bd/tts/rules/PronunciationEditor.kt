package com.bd.tts.rules

/**
 * 「词语读音（拼音）调整」纯逻辑层。
 *
 * 与上游工具页 midu_unified_rule/src/web 的交互语义对齐：
 *   - ruleForm.js   表单 → 一行规则（匹配值留空或 () 时自动用 key 圈定）
 *   - ruleOutput.js 待应用内容区（行管理）
 *   - planStore.ts  「应用」= 追加到规则文件 + 整行去重 + 重新整理
 *
 * 本工程里落点是 app 内 files/rules/user_rules.txt，
 * 「整理」由 RuleManager.apply() 完成（合并三份规则 → 原子写 → 重建引擎）。
 * 不依赖 Android，可在 JVM 上直接单测。
 */
object PronunciationEditor {

    const val CUSTOM = "__custom__"
    const val NONE = "NONE_RULE"          // 不加条件列：全局生效（回填无条件规则时用）
    val RULE_TYPES = listOf("TEXT", "WORD_INSIDE", "PINYIN", "POS")

    /** 表单状态。rule == CUSTOM 时用 ruleCustom；matchval 默认 "()"。 */
    data class Form(
        val key: String = "",
        val pron: String = "",
        val rule: String = "TEXT",
        val ruleCustom: String = "",
        val matchval: String = "()",
        val comment: String = ""
    ) {
        fun activeRule(): String =
            (if (rule == CUSTOM) ruleCustom.trim().ifEmpty { "TEXT" } else rule)
                .trim().uppercase()

        fun complete(): Boolean = key.trim().isNotEmpty() && pron.trim().isNotEmpty()
    }

    data class AppendResult(
        val text: String,
        val added: List<String>,
        val duplicates: List<String>,
        val ruleCount: Int
    )

    /** 查找命中：source = 用户规则 / 合并结果；exact = 规则首列就是该字词。 */
    data class Hit(val source: String, val line: String, val exact: Boolean)

    private val HEADERS = listOf(
        "移除:", "新增:", "去重:", "分组:", "排序:", "表单定义:", "页面操作:"
    )
    private val PRON = Regex(
        """[a-zA-ZüÜvV]+[0-9]|\*\d+|none\d+|none""",
        RegexOption.IGNORE_CASE
    )
    private val COND = Regex("""^(!?[A-Za-z_]+)\((.*)\)$""", RegexOption.DOT_MATCHES_ALL)

    // ------------------------------------------------------------------ 表单

    /**
     * 生成一行规则：key ⇥ 读音 ⇥ 规则(匹配值) [⇥ # 注释]
     * 与 ruleForm.js 的 buildLine() 完全一致：匹配值为空 / "()" 时取 "(" + key + ")"。
     */
    fun buildLine(f: Form): String {
        val key = f.key.trim()
        val pron = f.pron.trim().replace(Regex("""[\t\r\n]+"""), " ")
        val cm = f.comment.trim().removePrefix("#").trim()
        val rule = f.activeRule()
        if (rule == NONE) {
            return "$key\t$pron" + if (cm.isEmpty()) "" else "\t# $cm"
        }
        val raw = f.matchval.trim()
        val matchval = if (raw.isEmpty() || raw == "()") "($key)" else raw
        return "$key\t$pron\t$rule($matchval)" +
                (if (cm.isEmpty()) "" else "\t# $cm")
    }

    /** 必填校验；返回 null 表示可添加。 */
    fun validate(f: Form): String? = when {
        f.key.trim().isEmpty() -> "请先填写「中文字词」"
        f.pron.trim().isEmpty() -> "请先填写「读音」"
        f.key.any { it == '\t' || it == '\n' || it == '\r' } -> "中文字词不能包含 Tab / 换行"
        f.rule == CUSTOM && f.ruleCustom.trim().isEmpty() -> "请填写自定义规则名"
        else -> null
    }

    /** 读音格式只做友好提醒，不阻断添加（引擎还认 NONE / none5 等占位）。 */
    fun pronWarning(pron: String): String? {
        val bad = pron.trim().split(Regex("""\s+"""))
            .filter { it.isNotEmpty() && !PRON.matches(it) }
        return if (bad.isEmpty()) null
        else "提示：${bad.joinToString("、")} 看起来不像拼音（可用 hua4 / *5 / none5）"
    }

    /** 切换规则类型时维护匹配值：TEXT 且为空补回 "()"；其它类型清空（对齐 ruleForm.js）。 */
    fun matchvalAfterRuleChange(rule: String, current: String): String {
        val r = rule.trim().uppercase()
        return if (r == "TEXT" || r == CUSTOM.uppercase()) {
            if (current.trim().isEmpty()) "()" else current
        } else ""
    }

    // ------------------------------------------------------- 规则行反解析（回填）

    /** 括号 / 方括号外的 # 才算注释；返回 body 到注释正文（不含 #）。 */
    private fun splitComment(line: String): Pair<String, String> {
        var depth = 0
        for (i in line.indices) {
            when (line[i]) {
                '(', '[' -> depth++
                ')', ']' -> depth = maxOf(0, depth - 1)
                '#' -> if (depth == 0 && (i == 0 || line[i - 1].isWhitespace()))
                    return line.substring(0, i) to line.substring(i + 1).trim()
            }
        }
        return line to ""
    }

    /** 与 Rules.tokens() 同语义：条件里含空格时按括号平衡合并成完整 token。 */
    private fun tokens(body: String): List<String> {
        val out = ArrayList<String>()
        var pending = StringBuilder()
        var depth = 0
        for (p in body.trim().split(Regex("""[ \t]+""")).filter { it.isNotEmpty() }) {
            if (pending.isNotEmpty()) pending.append(' ')
            pending.append(p)
            for (ch in p) {
                if (ch == '(' || ch == '[') depth++
                else if (ch == ')' || ch == ']') depth--
            }
            if (depth <= 0) {
                out.add(pending.toString())
                pending = StringBuilder()
                depth = 0
            }
        }
        if (pending.isNotEmpty()) out.add(pending.toString())
        return out
    }

    private fun isPron(t: String): Boolean = PRON.matches(t)

    /** 把一条已存在的规则行拆回表单，便于「查到旧读音 → 改 → 应用」的闭环。 */
    fun parseLine(line: String): Form? {
        val (body, comment) = splitComment(line.trimEnd())
        val tk = tokens(body)
        if (tk.size < 2) return null
        val key = tk[0]
        val pron = ArrayList<String>()
        var i = 1
        while (i < tk.size && isPron(tk[i])) { pron.add(tk[i]); i++ }
        if (pron.isEmpty()) return null

        val conds = tk.subList(i, tk.size)
        if (conds.isEmpty()) {
            return Form(key, pron.joinToString(" "), NONE, "", "", comment)
        }
        val m = COND.matchEntire(conds[0])
        val name = m?.groupValues?.get(1)?.uppercase() ?: ""
        val value = m?.groupValues?.get(2) ?: conds[0]
        val rule = when {
            name.isEmpty() || name !in RULE_TYPES -> CUSTOM
            else -> name
        }
        val ruleCustom = if (rule == CUSTOM) (m?.groupValues?.get(1) ?: conds[0]) else ""
        // 额外的条件列拼进注释里，避免回填时静默丢弃语义
        val extra = conds.drop(1).joinToString(" ")
        val cm = listOf(comment, extra).filter { it.isNotEmpty() }.joinToString(" · ")
        return Form(key, pron.joinToString(" "), rule, ruleCustom, value, cm)
    }

    // ------------------------------------------------------------ 追加 / 去重

    /** 规范化整行用于去重：去注释 + 折叠空白（Tab/空格等价）。 */
    fun normalize(line: String): String {
        val body = splitComment(line.trimEnd()).first.trim()
        if (body.isEmpty()) return ""
        return body.split(Regex("""\s+""")).joinToString("\t")
    }

    private fun headerOf(s: String): String? =
        HEADERS.firstOrNull { s == it || s.startsWith(it) }

    /** 有效规则条数：注释 / 区块头 / 删除语法 / 「移除:」区块内的行都不算。 */
    fun countRules(text: String): Int {
        var state = ""
        var n = 0
        for (l in text.lineSequence()) {
            val s = l.trim()
            val h = headerOf(s)
            if (h != null) {
                state = when (h) { "移除:" -> "remove"; "新增:" -> "add"; else -> "" }
                continue
            }
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("@删除") ||
                s.startsWith("-") || state == "remove") continue
            n++
        }
        return n
    }

    /**
     * 把待应用规则写进用户规则文件的「新增:」区块，按规范化整行去重（含同批次内重复）。
     * 与 planStore.ts 的 compute() 同语义：已有该区块就插到区块末尾，没有就在文件末尾新建；
     * 这样即便文件最后是「移除:」区块，新规则也不会被误判成删除语义。
     * 无新增（全是重复）时不改文本、不刷新文件 mtime。
     */
    fun append(existing: String, lines: List<String>): AppendResult {
        val seen = LinkedHashSet<String>()
        var state = ""     // "" 普通 / "remove" / "add"
        for (l in existing.lineSequence()) {
            val s = l.trim()
            val h = headerOf(s)
            if (h != null) {
                state = when (h) { "移除:" -> "remove"; "新增:" -> "add"; else -> "" }
                continue
            }
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("@删除") ||
                s.startsWith("-") || state == "remove") continue
            val n = normalize(l)
            if (n.isNotEmpty()) seen.add(n)
        }

        val added = ArrayList<String>()
        val duplicates = ArrayList<String>()
        for (raw in lines) {
            val s = raw.trimEnd()
            if (s.isBlank()) continue
            if (!seen.add(normalize(s))) { duplicates.add(s); continue }
            added.add(s)
        }
        if (added.isEmpty()) return AppendResult(existing, added, duplicates, countRules(existing))

        val arr = existing.split("\n").toMutableList()
        val addIdx = arr.indexOfFirst { headerOf(it.trim()) == "新增:" }
        val text: String
        if (addIdx >= 0) {
            var end = arr.size
            for (i in addIdx + 1 until arr.size) {
                if (headerOf(arr[i].trim()) != null) { end = i; break }
            }
            var at = addIdx + 1
            for (i in end - 1 downTo addIdx + 1) {
                if (arr[i].trim().isNotEmpty()) { at = i + 1; break }
            }
            for (line in added) { arr.add(at, line); at++ }
            text = arr.joinToString("\n")
        } else {
            val base = existing.trimEnd()
            text = (if (base.isEmpty()) "" else base + "\n\n") +
                    "新增:\n" + added.joinToString("\n") + "\n"
        }
        return AppendResult(text, added, duplicates, countRules(text))
    }

    /** 在用户规则与合并结果里查某个字词；用户层优先、精确首列匹配优先。 */
    fun findHits(userText: String, mergedText: String, word: String, limit: Int = 24): List<Hit> {
        val w = word.trim()
        if (w.isEmpty()) return emptyList()
        val exact = ArrayList<Hit>()
        val fuzzy = ArrayList<Hit>()
        fun scan(text: String, source: String) {
            var state = ""
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd()
                val s = line.trim()
                val h = headerOf(s)
                if (h != null) {
                    state = when (h) { "移除:" -> "remove"; "新增:" -> "add"; else -> "" }
                    continue
                }
                if (s.isEmpty() || s.startsWith("#") || s.startsWith("@删除") ||
                    s.startsWith("-") || state == "remove") continue
                val first = tokens(splitComment(line).first).firstOrNull() ?: continue
                if (first == w) exact.add(Hit(source, line, true))
                else if (line.contains(w)) fuzzy.add(Hit(source, line, false))
            }
        }
        scan(userText, "用户")
        scan(mergedText, "合并")
        return (exact + fuzzy).take(limit)
    }
}
