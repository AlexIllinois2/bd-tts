package com.bd.tts.ui.pages

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
import com.bd.tts.rules.RuleManager
import com.bd.tts.ui.ActionButton
import com.bd.tts.ui.Actions
import com.bd.tts.ui.EditRow
import com.bd.tts.ui.GroupCard
import com.bd.tts.ui.Pill
import com.bd.tts.ui.SectionLabel
import com.bd.tts.ui.SettingRow
import com.bd.tts.ui.SwitchRow
import com.bd.tts.util.Prefs
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
    var viewTitle by remember { mutableStateOf<String?>(null) }
    var viewBody by remember { mutableStateOf("") }

    fun refreshAll() {
        report = Actions.conflictReport(ctx)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                EditRow(Icons.Default.Share, "上游 unified_rule.txt URL", ruleUrl, "留空用默认（源站+镜像回退）",
                    onCommit = { ruleUrl = it; prefs.ruleUrl(it); toast("已保存") })
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
