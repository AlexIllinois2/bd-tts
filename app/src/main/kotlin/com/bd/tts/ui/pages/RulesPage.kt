package com.bd.tts.ui.pages

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.bd.tts.rules.RuleFiles
import com.bd.tts.ui.ActionButton
import com.bd.tts.ui.Actions
import com.bd.tts.ui.EditRow
import com.bd.tts.ui.GroupCard
import com.bd.tts.ui.Pill
import com.bd.tts.ui.SectionLabel
import com.bd.tts.ui.SwitchRow
import com.bd.tts.util.Prefs
import kotlin.math.roundToInt

/**
 * 发音规则页：
 *   1) 规则总开关（一键短路，不动各文件自己的开关；第三方 HTTP 传参也覆盖它）
 *   2) 规则文件列表：本地 / 网络，不限数量；拖动排序、单文件启停、移除、编辑
 *      编辑 = 文本编辑（系统选编辑器）或表单编辑（弹发音页，应用追加到该文件）
 *   3) 上游 / vendor 的合并、更新、回滚与查看
 */
@Composable
fun RulesPage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    var entries by remember(refresh) { mutableStateOf(Actions.listRuleFiles(ctx)) }
    var report by remember(refresh) { mutableStateOf(Actions.conflictReport(ctx)) }
    var autoUpdate by remember(refresh) { mutableStateOf(prefs.autoUpdate()) }
    var interval by remember(refresh) { mutableStateOf(prefs.intervalHours().toString()) }
    var ruleUrl by remember(refresh) { mutableStateOf(prefs.ruleUrl()) }
    var globalOn by remember(refresh) { mutableStateOf(prefs.rulesEnabled()) }

    var viewTitle by remember { mutableStateOf<String?>(null) }
    var viewBody by remember { mutableStateOf("") }
    var networkDialog by remember { mutableStateOf(false) }
    var networkUrl by remember { mutableStateOf("") }
    var editChoiceId by remember { mutableStateOf<String?>(null) }
    var removeConfirmId by remember { mutableStateOf<String?>(null) }
    var pendingEditorId by remember { mutableStateOf<String?>(null) }
    var formTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun refreshAll() {
        entries = Actions.listRuleFiles(ctx)
        report = Actions.conflictReport(ctx)
        globalOn = prefs.rulesEnabled()
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var left = uris.size
        for (uri in uris) {
            val name = Actions.displayName(ctx, uri)
            Actions.addLocalRuleFile(ctx, uri, name) { msg ->
                toast(msg)
                left--
                if (left == 0) refreshAll()
            }
        }
    }
    val editorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
        val id = pendingEditorId
        pendingEditorId = null
        if (id != null) Actions.reloadRuleFile(ctx, id) { msg -> toast(msg); refreshAll() }
    }

    fun openTextEditor(id: String) {
        val f = RuleFiles.fileById(ctx, id)
        if (f == null) { toast("规则文件已不存在"); return }
        try { f.parentFile?.mkdirs() } catch (_: Exception) {}
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            val intent = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            pendingEditorId = id
            editorLauncher.launch(Intent.createChooser(intent, "选择文本编辑器"))
        } catch (e: Exception) {
            toast("打不开编辑器：${e.message}")
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ------------------------------------------------------------ 总开关
        item {
            SectionLabel("规则总开关", trailing = { Pill(if (globalOn) "已启用" else "已短路") })
            GroupCard {
                SwitchRow(
                    icon = Icons.Default.Settings,
                    title = "启用发音规则",
                    checked = globalOn,
                    onCheckedChange = { on ->
                        Actions.setRulesEnabled(ctx, on) { msg -> toast(msg); refreshAll() }
                    }
                )
            }
        }

        // ------------------------------------------------------------ 规则文件
        item {
            SectionLabel("规则文件", trailing = { Pill("${entries.size} 份") })
            GroupCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("添加本地文件", Icons.Default.Add,
                        { fileLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                    ActionButton("添加网络文件", Icons.Default.Share,
                        { networkDialog = true }, Modifier.weight(1f))
                }
                if (entries.isEmpty()) {
                    Text("还没有规则文件，点上面按钮添加；旧版「用户规则」会在首次启动时自动进列表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                } else {
                    entries.forEachIndexed { i, e ->
                        RuleFileRow(
                            entry = e, index = i, total = entries.size,
                            onMove = { from, to ->
                                Actions.moveRuleFile(ctx, from, to) { msg ->
                                    toast(msg); refreshAll()
                                }
                            },
                            onToggle = { on ->
                                Actions.setRuleFileEnabled(ctx, e.id, on) { msg ->
                                    toast(msg); refreshAll()
                                }
                            },
                            onEdit = { editChoiceId = e.id },
                            onRefresh = {
                                Actions.refreshRuleFile(ctx, e.id) { msg ->
                                    toast(msg); refreshAll()
                                }
                            },
                            onRemove = { removeConfirmId = e.id }
                        )
                        if (i < entries.size - 1) HorizontalDivider()
                    }
                }
            }
        }

        // ------------------------------------------------------------ 合并与更新
        item {
            SectionLabel("更新")
            GroupCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("检查更新", Icons.Default.Refresh, {
                        Actions.checkUpdate(ctx) { msg -> toast(msg); refreshAll() }
                    }, Modifier.weight(1f))
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
                EditRow(Icons.Default.Share, "远程文件地址", ruleUrl, "留空用默认（源站+镜像回退）",
                    onCommit = { ruleUrl = it; prefs.ruleUrl(it); toast("已保存") })
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // ---------------------------------------------------------------- 对话框
    if (networkDialog) {
        AlertDialog(
            onDismissRequest = { networkDialog = false },
            title = { Text("添加网络规则文件") },
            text = {
                Column {
                    Text("填一个可直接下载纯文本的 http(s) 地址；会立刻下载缓存，离线也能继续用。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = networkUrl, onValueChange = { networkUrl = it },
                        placeholder = { Text("https://…/unified_rule.txt") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = networkUrl.trim()
                    networkDialog = false
                    if (url.isEmpty()) toast("请先填地址")
                    else Actions.addNetworkRuleFile(ctx, url) { msg -> toast(msg); refreshAll() }
                }) { Text("下载并添加") }
            },
            dismissButton = { TextButton(onClick = { networkDialog = false }) { Text("取消") } }
        )
    }

    val choiceId = editChoiceId
    if (choiceId != null) {
        val name = entries.firstOrNull { it.id == choiceId }?.name ?: "规则文件"
        AlertDialog(
            onDismissRequest = { editChoiceId = null },
            title = { Text("编辑「$name」") },
            text = {
                Text("文本编辑：从系统选一个编辑器直接改这个文件；表单编辑：弹出发音页，按「应用」后追加到这个文件。网络规则只提供重新下载，不在这里编辑。")
            },
            confirmButton = {
                TextButton(onClick = { editChoiceId = null; openTextEditor(choiceId) }) {
                    Text("文本编辑")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        editChoiceId = null
                        formTarget = choiceId to name
                    }) { Text("表单编辑") }
                    TextButton(onClick = { editChoiceId = null }) { Text("取消") }
                }
            }
        )
    }

    val removeId = removeConfirmId
    if (removeId != null) {
        val name = entries.firstOrNull { it.id == removeId }?.name ?: "规则文件"
        AlertDialog(
            onDismissRequest = { removeConfirmId = null },
            title = { Text("移除「$name」？") },
            text = { Text("只从合并列表移除，文件本身不会被删除；可以随时重新添加。") },
            confirmButton = {
                TextButton(onClick = {
                    removeConfirmId = null
                    Actions.removeRuleFile(ctx, removeId) { msg -> toast(msg); refreshAll() }
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removeConfirmId = null }) { Text("取消") } }
        )
    }

    val target = formTarget
    if (target != null) {
        Dialog(onDismissRequest = { formTarget = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                PronunciationPage(
                    refresh = refresh, toast = toast,
                    targetId = target.first, targetName = target.second,
                    onClose = { formTarget = null; refreshAll() }
                )
            }
        }
    }

    if (viewTitle != null) {
        AlertDialog(
            onDismissRequest = { viewTitle = null },
            title = { Text(viewTitle!!) },
            text = {
                LazyColumn {
                    item {
                        Text(viewBody, style = MaterialTheme.typography.bodySmall)
                    }
                }
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

/** 一行规则文件：拖动排序 + 自身开关 + 编辑 / 更新 / 移除。 */
@Composable
private fun RuleFileRow(
    entry: Actions.RuleFileView,
    index: Int,
    total: Int,
    onMove: (Int, Int) -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit
) {
    var offset by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 84.dp.toPx() }
    val currentIndex = rememberUpdatedState(index)

    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { translationY = if (dragging) offset else 0f }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {},
            modifier = Modifier.pointerInput(entry.id) {
                detectDragGestures(
                    onDragStart = { dragging = true; offset = 0f },
                    onDrag = { change, dy -> change.consume(); offset += dy.y },
                    onDragEnd = {
                        val steps = (offset / rowHeightPx).roundToInt()
                        dragging = false
                        offset = 0f
                        val from = currentIndex.value
                        val to = (from + steps).coerceIn(0, total - 1)
                        onMove(from, to)
                    },
                    onDragCancel = { dragging = false; offset = 0f }
                )
            }
        ) { Icon(Icons.Default.Menu, "拖动排序") }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${index + 1}. ${if (entry.network) "网络" else "本地"} · ${entry.lines} 行 / ${entry.bytes} B"
                    + (if (entry.enabled) "" else " · 已禁用"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(entry.spec, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))
        Switch(checked = entry.enabled, onCheckedChange = onToggle)
        if (entry.network) {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, "重新下载", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "编辑", tint = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, "移除", tint = MaterialTheme.colorScheme.error)
        }
    }
}
