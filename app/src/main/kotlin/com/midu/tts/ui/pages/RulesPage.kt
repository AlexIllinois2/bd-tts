package com.midu.tts.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.midu.tts.rules.RuleManager
import com.midu.tts.ui.ActionButton
import com.midu.tts.ui.Actions
import com.midu.tts.ui.DropdownRow
import com.midu.tts.ui.EditRow
import com.midu.tts.ui.GroupCard
import com.midu.tts.ui.Pill
import com.midu.tts.ui.SectionLabel
import com.midu.tts.ui.SettingRow
import com.midu.tts.ui.SwitchRow
import com.midu.tts.util.Prefs
import java.io.File

@Composable
fun RulesPage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    var userText by remember(refresh) { mutableStateOf(Actions.userRules(ctx)) }
    var report by remember(refresh) { mutableStateOf(Actions.conflictReport(ctx)) }
    var autoUpdate by remember(refresh) { mutableStateOf(prefs.autoUpdate()) }
    var interval by remember(refresh) { mutableStateOf(prefs.intervalHours().toString()) }
    var ruleUrl by remember(refresh) { mutableStateOf(prefs.ruleUrl()) }
    var patchUrl by remember(refresh) { mutableStateOf(prefs.patchUrl()) }
    var viewTitle by remember { mutableStateOf<String?>(null) }
    var viewBody by remember { mutableStateOf("") }

    // 快速生成一行
    var qWord by remember { mutableStateOf("") }
    var qPron by remember { mutableStateOf("") }
    var qValue by remember { mutableStateOf("") }
    var qType by remember { mutableStateOf("TEXT") }
    var qComment by remember { mutableStateOf("") }

    fun refreshAll() {
        report = Actions.conflictReport(ctx)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionLabel("三份规则 · 优先级")
            GroupCard {
                SettingRow(Icons.Default.List, "① 用户自定义（最高）",
                    "${userText.lines().count { it.isNotBlank() && !it.trim().startsWith("#") }} 条 · 可在本页直接编辑")
                SettingRow(Icons.Default.Share, "② midu_unified_rule 产物（定时更新）",
                    "${countLines(RuleManager.upstreamFile(ctx))} 条 · 含 patch 移除语义")
                SettingRow(Icons.Default.Info, "③ vendor 原件（最低）",
                    "${countLines(RuleManager.vendorFile(ctx))} 条 · 与 MANIFEST.md5 对账")
                SettingRow(Icons.Default.Settings, "合并结果",
                    "${countLines(RuleManager.mergedFile(ctx))} 条 · 引擎 init 时读取，改动会重建引擎",
                    trailing = { Pill("gen ${prefs.generation()}") })
            }
        }

        item {
            SectionLabel("合并与更新")
            GroupCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("检查上游更新", Icons.Default.Refresh, {
                        Actions.checkUpdate(ctx) { toast(it); refreshAll() }
                    }, Modifier.weight(1f))
                    ActionButton("重新合并", Icons.Default.Settings, {
                        toast(Actions.applyRules(ctx)); refreshAll()
                    }, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        viewTitle = "合并后的 unified_rule.txt"; viewBody = Actions.mergedText(ctx)
                    }) { Text("查看合并结果") }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        viewTitle = "冲突 / 计数报告"; viewBody = report
                    }) { Text("查看冲突报告") }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        toast(Actions.rollback(ctx)); refreshAll()
                    }) { Text("回滚上游") }
                }
                SwitchRow(
                    icon = Icons.Default.Refresh,
                    title = "自动定时检查更新",
                    subtitle = "ETag / 304 免流量，合法性校验通过才落盘，旧版自动留 .prev",
                    checked = autoUpdate,
                    onCheckedChange = { autoUpdate = it; prefs.autoUpdate(it) }
                )
                EditRow(Icons.Default.Settings, "检查间隔（小时）", interval, "6",
                    onCommit = {
                        interval = it
                        prefs.intervalHours(it.toIntOrNull()?.coerceAtLeast(1) ?: 6)
                        toast("已保存并重新排程")
                    })
                EditRow(Icons.Default.Share, "上游 unified_rule.txt URL", ruleUrl, "留空用默认",
                    onCommit = { ruleUrl = it; prefs.ruleUrl(it); toast("已保存") })
                EditRow(Icons.Default.Share, "上游 rule_patch.txt URL", patchUrl, "留空用默认",
                    onCommit = { patchUrl = it; prefs.patchUrl(it); toast("已保存") })
            }
        }

        item {
            SectionLabel("用户规则文件")
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("格式：字词<Tab>读音<Tab>条件…；条件支持 TEXT / !TEXT / WORD_INSIDE / PINYIN / POS。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = userText, onValueChange = { userText = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 8,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("保存并应用", Icons.Default.Add, {
                            toast(Actions.saveUserRules(ctx, userText)); refreshAll()
                        }, Modifier.weight(1f))
                        TextButton(modifier = Modifier.weight(1f), onClick = {
                            if (Actions.copy(ctx, "user_rules", userText)) toast("已复制")
                        }) { Text("复制全文") }
                    }
                }
            }
        }

        item {
            SectionLabel("快速生成一行")
            GroupCard {
                EditRow(Icons.Default.Edit, "字词（如 行 / 姐姐）", qWord, "行",
                    onCommit = { qWord = it })
                EditRow(Icons.Default.Edit, "读音（如 hang2 / *3 *1）", qPron, "hang2",
                    onCommit = { qPron = it })
                DropdownRow(Icons.Default.Settings, "规则类型", null,
                    listOf("TEXT", "WORD_INSIDE", "PINYIN", "POS"), qType,
                    onSelect = { qType = it })
                EditRow(Icons.Default.Edit, "匹配值（留空自动圈定字词）", qValue, "(雪中悍刀(行))",
                    onCommit = { qValue = it })
                EditRow(Icons.Default.Edit, "注释（可空）", qComment, "备注",
                    onCommit = { qComment = it })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ActionButton("生成并追加", Icons.Default.Add, {
                        if (qWord.isBlank() || qPron.isBlank()) { toast("字词和读音必填"); return@ActionButton }
                        val cond = when (qType) {
                            "WORD_INSIDE", "POS", "PINYIN" ->
                                "$qType(${qValue.ifBlank { qWord }})"
                            else -> "TEXT(${qValue.ifBlank { "($qWord)" }})"
                        }
                        val line = "$qWord\t$qPron\t$cond" +
                                (if (qComment.isBlank()) "" else "\t# $qComment")
                        userText = userText.trimEnd() + "\n" + line + "\n"
                        toast("已追加：$line（记得点保存并应用）")
                    }, Modifier.fillMaxWidth())
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (viewTitle != null) {
        AlertDialog(
            onDismissRequest = { viewTitle = null },
            title = { Text(viewTitle!!) },
            text = {
                LazyColumn { item { Text(viewBody,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall) } }
            },
            confirmButton = {
                TextButton(onClick = {
                    Actions.copy(ctx, viewTitle!!, viewBody); viewTitle = null; toast("已复制")
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { viewTitle = null }) { Text("关闭") } }
        )
    }
}

private fun countLines(f: File): Int = try {
    f.readLines(Charsets.UTF_8).count { it.isNotBlank() && !it.trim().startsWith("#") }
} catch (_: Exception) { 0 }
