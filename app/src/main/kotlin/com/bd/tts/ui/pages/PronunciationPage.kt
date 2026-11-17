package com.bd.tts.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bd.tts.rules.PronunciationEditor
import com.bd.tts.ui.Actions
import com.bd.tts.ui.GroupCard
import com.bd.tts.ui.Pill
import com.bd.tts.ui.SectionLabel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 词语读音（拼音）调整页 —— 交互对齐 midu_unified_rule 工具页：
 *   表单（字词 / 读音 / 规则 / 匹配值 / 注释，任意框回车即添加）
 *   → 待应用内容区（可删、可点回重编辑）
 *   → 「应用」一次完成：追加去重写入用户规则 + 合并 + 重建引擎
 *   → 状态条显示用户规则文件真实状态，外部改动会高亮提醒
 * 额外补上 app 内才有的能力：查找已有读音回填、快捷示例、应用后直接试听。
 */
@Composable
fun PronunciationPage(
    refresh: Int, toast: (String) -> Unit,
    targetId: String? = null, targetName: String? = null,
    onClose: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    // targetId != null = 从规则页「表单编辑」弹出来，应用时写进指定规则文件；
    // 独立入口（老版本）仍写在默认用户规则里。
    val embedded = onClose != null

    // 表单
    var key by rememberSaveable { mutableStateOf("") }
    var pron by rememberSaveable { mutableStateOf("") }
    var ruleType by rememberSaveable { mutableStateOf("TEXT") }
    var ruleCustom by rememberSaveable { mutableStateOf("") }
    var matchval by rememberSaveable { mutableStateOf("()") }
    var comment by rememberSaveable { mutableStateOf("") }
    var ruleMenu by remember { mutableStateOf(false) }

    // 待应用内容区 / 查找 / 试听
    var pending by rememberSaveable { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<PronunciationEditor.Hit>()) }
    var testText by rememberSaveable { mutableStateOf("") }

    // 状态条 / 反馈
    var status by remember { mutableStateOf(Actions.ruleStatus(ctx, targetId)) }
    var baselineMtime by remember { mutableStateOf(0L) }
    var externalNotice by remember { mutableStateOf("") }
    var noticeAt by remember { mutableStateOf(0L) }
    var hint by rememberSaveable { mutableStateOf("") }
    var hintError by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var focusTick by remember { mutableStateOf(0) }

    /** 首次进入 / 从别的 tab 回来：重新读一次状态并同步基线。 */
    LaunchedEffect(refresh) {
        val st = Actions.ruleStatus(ctx, targetId)
        status = st
        baselineMtime = st.mtime
        externalNotice = ""
    }

    /** 2 秒心跳：像工具页一样发现「文件被外部改动」，8 秒后恢复常规显示。 */
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            val st = Actions.ruleStatus(ctx, targetId)
            status = st
            val now = System.currentTimeMillis()
            if (baselineMtime != 0L && st.mtime != baselineMtime) {
                externalNotice = "⚠ 用户规则文件被外部修改（${fmtTime(st.mtime)}）"
                noticeAt = now
                baselineMtime = st.mtime
            } else if (externalNotice.isNotEmpty() && now - noticeAt > 8000) {
                externalNotice = ""
            }
        }
    }

    LaunchedEffect(focusTick) {
        if (focusTick > 0) try { focus.requestFocus() } catch (_: Exception) {}
    }

    fun showHint(msg: String, error: Boolean = false) {
        hint = msg
        hintError = error
    }

    fun currentForm() = PronunciationEditor.Form(
        key, pron, ruleType, ruleCustom, matchval, comment
    )

    fun applyForm(f: PronunciationEditor.Form) {
        key = f.key
        pron = f.pron
        ruleType = f.rule
        ruleCustom = f.ruleCustom
        matchval = f.matchval
        comment = f.comment
    }

    fun resetForm() {
        key = ""
        pron = ""
        ruleType = "TEXT"
        ruleCustom = ""
        matchval = "()"
        comment = ""
    }

    /** 「添加」：校验 → 生成一行 → 进内容区 → 清空表单并聚焦字词（对齐 ruleForm/app.js）。 */
    fun addRule(): Boolean {
        val f = currentForm()
        val err = PronunciationEditor.validate(f)
        if (err != null) { showHint(err, true); return false }
        val line = PronunciationEditor.buildLine(f)
        pending = pending + line
        resetForm()
        focusTick++
        val warn = PronunciationEditor.pronWarning(f.pron)
        showHint(warn ?: "已加入待应用：$line")
        return true
    }

    /** 「应用」：表单填完整但没点添加时自动补一条；然后写入 + 合并 + 重建引擎。 */
    fun applyPending() {
        if (PronunciationEditor.validate(currentForm()) == null) addRule()
        val lines = pending
        busy = true
        showHint(if (targetId == null) "正在写入用户规则并合并生效…"
                 else "正在写入「${targetName ?: "规则文件"}」并合并生效…")
        Actions.appendPendingRules(ctx, lines, { r ->
            busy = false
            status = r.status
            baselineMtime = r.status.mtime
            externalNotice = ""
            if (r.ok) {
                pending = emptyList()
                hits = emptyList()
                showHint(r.message)
                toast("已应用 ${r.added} 条读音规则并重建引擎")
                onClose?.invoke()
            } else {
                showHint(r.message, true)
                toast(r.message)
            }
        }, targetId)
    }

    /** 内容区点一行：取回表单重新编辑。 */
    fun editPending(index: Int) {
        val line = pending[index]
        val f = PronunciationEditor.parseLine(line)
        if (f == null) { showHint("这行无法回填，可在规则文件里直接改", true); return }
        applyForm(f)
        pending = pending.toMutableList().also { it.removeAt(index) }
        focusTick++
        showHint("已取回表单，改完点「添加」再「应用」。同字词 + 同条件会覆盖旧读音")
    }

    fun doSearch() {
        val w = query.trim()
        if (w.isEmpty()) { showHint("请先填写要查找的字词", true); return }
        busy = true
        Actions.findRules(ctx, w, { list ->
            busy = false
            hits = list
            showHint(
                if (list.isEmpty()) "没有找到「$w」相关规则，可直接新增一条"
                else "找到 ${list.size} 条：点任意一条回填表单，改读音后添加并应用即可覆盖"
            )
        }, targetId)
    }

    fun doPlay() {
        val k = key.trim()
        val text = testText.trim().ifEmpty {
            if (k.isNotEmpty()) "请读出这个词语：$k。" else "银行的行长在人行道上行走了很久。"
        }
        busy = true
        showHint("正在合成试听：$text")
        Actions.play(ctx, text) { msg ->
            busy = false
            status = Actions.ruleStatus(ctx, targetId)
            showHint(msg, msg.startsWith("播放失败"))
            toast(msg)
        }
    }

    val ruleOptions = listOf(
        "TEXT（默认）" to "TEXT",
        "WORD_INSIDE" to "WORD_INSIDE",
        "PINYIN" to "PINYIN",
        "POS" to "POS",
        "无（全局生效）" to PronunciationEditor.NONE,
        "自定义…" to PronunciationEditor.CUSTOM
    )
    val ruleLabel = ruleOptions.firstOrNull { it.second == ruleType }?.first
        ?: if (ruleType == PronunciationEditor.CUSTOM) "自定义：${ruleCustom.ifBlank { "未填" }}" else ruleType

    val presets = listOf(
        "吧 → ba1" to PronunciationEditor.Form("吧", "ba1"),
        "巴 → *5" to PronunciationEditor.Form("巴", "*5"),
        "仁儿 → renr4 none5" to PronunciationEditor.Form("仁儿", "renr4 none5"),
        "小满 → xiao6 man3" to PronunciationEditor.Form("小满", "xiao6 man3"),
        "行（雪中悍刀行）" to PronunciationEditor.Form(
            "行", "hang2", "TEXT", "", "雪中悍刀(行)", "书名末字读 hang2")
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            if (embedded) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("编辑规则文件：${targetName ?: "规则文件"}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { onClose?.invoke() }) { Text("返回规则页") }
                }
            }
            SectionLabel(
                if (targetId == null) "词语读音调整" else "表单编辑 · 应用后追加到该文件",
                trailing = { Pill("表单 → 添加 → 应用") }
            )
            StatusBar(status, externalNotice, targetId != null)
        }

        // ------------------------------------------------------------ 规则表单
        item {
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("像网页工具一样填表：任意输入框按回车即添加，应用后立刻生效，无需 adb 推送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))

                    RuleField("中文字词 (key)", key, "如：吧 / 巴 / 仁儿 / 小满",
                        { key = it }, { addRule() }, focus)
                    Spacer(Modifier.height(8.dp))
                    RuleField("读音 (pinyin)", pron, "如：ba1 / *5 / renr4 none5 / xiao6 man3",
                        { pron = it }, { addRule() })
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("匹配规则", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { ruleMenu = true }) {
                                Text(ruleLabel)
                                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = ruleMenu,
                                onDismissRequest = { ruleMenu = false }) {
                                ruleOptions.forEach { (label, value) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                        ruleMenu = false
                                        ruleType = value
                                        matchval = PronunciationEditor
                                            .matchvalAfterRuleChange(value, matchval)
                                        if (value != PronunciationEditor.CUSTOM) ruleCustom = ""
                                    })
                                }
                            }
                        }
                    }
                    if (ruleType == PronunciationEditor.CUSTOM) {
                        Spacer(Modifier.height(8.dp))
                        RuleField("自定义规则名", ruleCustom, "如：!WORD_INSIDE / MY_RULE",
                            { ruleCustom = it }, { addRule() })
                    }
                    Spacer(Modifier.height(8.dp))
                    RuleField("匹配值", matchval,
                        "留空或 () 自动圈定字词；如：雪中悍刀(行) / (吧)台 / .*(区)老师",
                        { matchval = it }, { addRule() })
                    Spacer(Modifier.height(8.dp))
                    RuleField("注释（可选）", comment, "如：关键词出现时，最后一个字轻声",
                        { comment = it }, { addRule() })

                    Spacer(Modifier.height(6.dp))
                    Text(
                        hint.ifEmpty { "生成格式：字词 ⇥ 读音 ⇥ 规则(匹配值) [⇥ # 注释]" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hintError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { addRule() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("添加")
                        }
                        Button(onClick = { applyPending() }, enabled = !busy,
                            modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (busy) "处理中" else "应用")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { doPlay() }, enabled = !busy,
                            modifier = Modifier.weight(1f)) { Text("试听验证") }
                        TextButton(onClick = {
                            resetForm(); showHint("表单已重置")
                        }, modifier = Modifier.weight(1f)) { Text("重置表单") }
                    }
                }
            }
        }

        item {
            SectionLabel("快捷示例（点一下填表）")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(presets) { (label, form) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clickable {
                            applyForm(form)
                            focusTick++
                            showHint("已填入示例，确认后点「添加」")
                        }
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }

        // -------------------------------------------------------- 待应用内容区
        item {
            SectionLabel("待应用规则", trailing = { Pill("${pending.size} 条") })
            GroupCard {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (pending.isEmpty()) {
                        Text("还没有待应用规则。填好表单点「添加」（或直接按回车）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp))
                    } else {
                        pending.forEachIndexed { i, line ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { editPending(i) }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${i + 1}.", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(line.replace("\t", " ⇥ "),
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    pending = pending.toMutableList().also { it.removeAt(i) }
                                }) {
                                    Icon(Icons.Default.Delete, "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                            if (i < pending.size - 1) HorizontalDivider()
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                Actions.copy(ctx, "待应用规则", pending.joinToString("\n"))
                                toast("已复制 ${pending.size} 条")
                            }) { Text("复制全文") }
                            TextButton(onClick = {
                                pending = emptyList(); showHint("已清空待应用区")
                            }) { Text("清空") }
                            TextButton(onClick = { applyPending() }, enabled = !busy) {
                                Text("应用这 ${pending.size} 条")
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------- 查找 / 调整旧读音
        item {
            SectionLabel("查找 / 调整已有读音")
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("输入字词，如：行 / 吧") },
                            singleLine = true, modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { doSearch() })
                        )
                        FilledTonalButton(onClick = { doSearch() }, enabled = !busy) { Text("查找") }
                    }
                    Text("命中行来自「用户规则」和「合并结果」。点一条回填表单 → 改读音 → 添加 → 应用；同字词 + 同条件会自动覆盖低层读音。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (hits.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        hits.take(12).forEach { h ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        PronunciationEditor.parseLine(h.line)?.let {
                                            applyForm(it)
                                            showHint("已回填「${h.source}」层规则，改完读音点添加并应用")
                                        } ?: showHint("这行无法回填", true)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Pill(h.source)
                                Spacer(Modifier.width(8.dp))
                                Text(h.line.replace("\t", " "),
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                            }
                            HorizontalDivider()
                        }
                        if (hits.size > 12) {
                            Text("仅显示前 12 条，共 ${hits.size} 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ---------------------------------------------- 听书校正提示词（给 AI）
        item {
            var expanded by rememberSaveable { mutableStateOf(false) }
            SectionLabel("听书校正提示词", trailing = { Pill("复制给 AI") })
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "把小说 txt 发给任意 AI 前，先粘贴这段提示词：它会按本引擎的" +
                                "「[[字词|pin1 yin1]]」内联注音语法，输出修好多音字的听书专用全文，" +
                                "本引擎可直接朗读（语法错标会被安全忽略照读）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        AGENT_PROMPT,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "收起" else "展开全文")
                        }
                        FilledTonalButton(onClick = {
                            Actions.copy(ctx, "BD TTS 听书校正提示词", AGENT_PROMPT)
                            toast("提示词已复制，连同 txt 发给 AI 即可")
                        }) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制完整提示词")
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ 试听 & 说明
        item {
            SectionLabel("试听与格式速查")
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = testText, onValueChange = { testText = it },
                            placeholder = { Text("留空则自动用当前字词造句") },
                            singleLine = true, modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        FilledTonalButton(onClick = { doPlay() }, enabled = !busy) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("试听")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "读音写法：hua4（完整拼音+声调）/ *5（只改声调，沿用原拼音）/ none5（儿化占位）；多字词逐字对应。\n" +
                                "条件：TEXT(上下文正则，内层 (…) 圈定改音的字) / WORD_INSIDE(n) / PINYIN(regex) / POS(regex)。\n" +
                                "应用后规则立即合并、引擎自动重建；也可用「发音规则」页查看合并结果与冲突报告。\n" +
                                "试听/朗读文本可内联临时注音：[[字词|pin1 yin1]]（拼音数=字数，声调 1-5），" +
                                "如 [[行人|xing2 ren2]]，不改规则即时生效；传整段 <speak> SSML 也直接支持。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 对应工具页的 .planbar：补丁文件状态 + 外部改动高亮。 */
@Composable
private fun StatusBar(status: Actions.RuleStatus, notice: String, targetMode: Boolean = false) {
    val changed = notice.isNotEmpty()
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (changed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape,
                color = if (changed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(8.dp)) {}
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (changed) notice
                    else "${if (targetMode) "目标文件" else "用户规则"} ${status.lines} 行 / ${status.bytes} 字节 · 最近修改 ${fmtTime(status.mtime)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (changed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text("合并结果 ${status.mergedLines} 条 · 规则代数 gen ${status.generation} · 应用后自动重建引擎",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 单行输入框：无「保存」按钮，值即时生效；回车 = 添加一条（对齐工具页 keydown 监听）。 */
@Composable
private fun RuleField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    focus: FocusRequester? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
    )
}

private fun fmtTime(t: Long): String =
    if (t <= 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))

/**
 * 听书校正提示词：整段复制给任意 AI（连同小说 txt），让大模型按本引擎的内联注音
 * 语法 [[字词|pin1 yin1]] 输出可直接朗读的校正版全文。语法硬约束与
 * engine/PinyinInline.java 的校验严格一致：词=纯汉字 1~24 字、音节空格分隔
 * 逐字对应、声调 1~5（5=轻声）、ü 可写 v；校验不过的标注会被引擎安全摘除照读，
 * 所以提示词里强调「宁可少标不要错标」。
 */
private const val AGENT_PROMPT = """你是一名听书文本校对助手。我会发给你一个 txt 文本（小说/文章），请把它改成「听书专用注音版」后完整输出。

## 目标
把朗读引擎（BD 离线 TTS）容易读错的字词，用内联注音语法 [[字词|拼音]] 精确标出；其余内容一字不改。

## 注音语法（硬规则，违反的标注会被引擎静默忽略）
1. 格式：[[字词|拼音 拼音 …]]——竖线右侧是该词每个字的读音，用空格分隔，逐字对应
2. 字词必须是纯汉字（1~24 个字，不含标点/字母/空格）；要标的目标中间有标点时，拆开分别标注
3. 每个音节 = 小写拼音 + 声调数字 1~5（5=轻声），ü 写作 v
4. 音节个数必须与汉字个数严格相等：单字 [[了|le5]]，双字 [[手指|shou2 zhi3]]，三字 [[大阪府|da4 ban3 fu3]]
5. 一段话里可以有任意多个标注；不标注英文、数字、标点

## 标注原则（宁可少标，不要错标）
1. 只标注「引擎可能读错」的字词，不要全文逐词标注。重点排查：
   - 多音字按上下文定音：地（动词后 de5）、了 le5、着 zhe5、得 de5/dei3、还 hai2（还没）/huan2（归还）、都 dou1（都是）/du1（首都）、长 chang2（长江）/zhang3（长大）、重 zhong4（重要）/chong2（重复）、处 chu3（处理）、弹 tan2（弹琴）/dan4（子弹）、载 zai4（载重）/zai3（记载）、行 hang2（银行）/xing2（行人）
   - 专名读法：[[大夫|dai4 fu5]]（医生）、[[银行|yin2 hang2]]、[[行长|hang2 zhang3]]、[[户籍|hu4 ji2]]
   - 常见轻声：[[什么|shen2 me5]]、[[东西|dong1 xi5]]、[[意思|yi4 si5]]、[[打听|da3 ting5]]、[[先生|xian1 sheng5]]
   - 人名/地名/书名按通行读音标注（如 雪中悍刀行 末字 hang2），拿不准的宁可不标
2. 同一个词每次出现都要标注（引擎按出现位置逐个覆写），不要只标第一处
3. 内联注音必须写完整拼音，不使用 *5、none5 这类规则层占位写法

## 输出要求
- 输出校正后的完整全文：保持原有分段、换行、标点，不增删任何字句，不添加任何解释或前后缀
- 如果我发的是文件，直接输出全文（或存为「原文件名_听书.txt」交付）

## 示例
输入：他无奈地用手指点了点她的额头。这家银行的行长还没把借书还回来，大家都说他行。
输出：他无奈[[地|de5]]用[[手指|shou2 zhi3]]点[[了|le5]]点她的额头。这家[[银行|yin2 hang2]]的[[行长|hang2 zhang3]][[还|hai2]]没把借书[[还|huan2]]回来，[[都|dou1]]说他[[行|xing2]]。"""
