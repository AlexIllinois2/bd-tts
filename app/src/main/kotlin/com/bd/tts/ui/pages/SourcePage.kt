package com.bd.tts.ui.pages

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bd.tts.shizuku.ShizukuClient
import com.bd.tts.source.MultiTts
import com.bd.tts.ui.ActionButton
import com.bd.tts.ui.Actions
import com.bd.tts.ui.DropdownRow
import com.bd.tts.ui.EditRow
import com.bd.tts.ui.GroupCard
import com.bd.tts.ui.SectionLabel
import com.bd.tts.ui.SettingRow
import com.bd.tts.ui.SwitchRow
import com.bd.tts.util.Prefs

@Composable
fun SourcePage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    var customPath by remember(refresh) { mutableStateOf(prefs.customPath()) }
    var soPath by remember(refresh) { mutableStateOf(prefs.soPath()) }
    var multitts by remember(refresh) { mutableStateOf(prefs.multittsRoot()) }
    var rigPath by remember(refresh) { mutableStateOf(prefs.host2Rig()) }
    var backend by remember(refresh) { mutableStateOf(prefs.backend()) }
    var daemonOn by remember { mutableStateOf(prefs.host2DaemonEnabled()) }
    var voice by remember(refresh) { mutableStateOf(prefs.voice()) }
    var voices by remember(refresh) { mutableStateOf(listOf(voice)) }
    var diagOpen by remember { mutableStateOf(false) }
    var diag by remember(refresh) { mutableStateOf(Actions.diagnostics(ctx)) }

    // 进页面就枚举语音包里的音色，否则下拉只有当前一项，用户根本没法切换。
    LaunchedEffect(refresh) {
        Actions.listVoices(ctx) { list, _ ->
            if (list.isNotEmpty()) voices = (list + voice).distinct()
        }
    }

    val mtInstalled = remember(refresh) { MultiTts.installed(ctx) }
    val shizukuOk = remember(refresh) { ShizukuClient.granted() }
    val autoMode = mtInstalled && shizukuOk

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        customPath = uri.toString()
        prefs.customPath(uri.toString())
        Actions.invalidate(ctx)
        toast("已选择语音包文件夹，下次合成生效")
    }
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        customPath = uri.toString()
        prefs.customPath(uri.toString())
        Actions.invalidate(ctx)
        toast("已选择语音包 zip / apk，下次合成生效")
    }
    val soLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        soPath = uris.joinToString("\n") { it.toString() }
        prefs.soPath(soPath)
        Actions.invalidate(ctx)
        toast("so 来源已保存（${uris.size} 项），下次合成生效")
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionLabel("快捷入口")
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("系统 TTS", Icons.Default.Settings,
                    { Actions.openSystemTts(ctx) }, Modifier.weight(1f))
                ActionButton("经典设置", Icons.Default.Info,
                    { Actions.openLegacy(ctx) }, Modifier.weight(1f))
            }
        }

        item {
            SectionLabel("离线语音位置")
            GroupCard {
                SettingRow(Icons.Default.Info, "MultiTTS",
                    if (mtInstalled) "已安装 · org.nobody.multitts" else "未安装")
                SettingRow(Icons.Default.Info, "Shizuku", ShizukuClient.describe())
                SettingRow(
                    icon = Icons.Default.Settings,
                    title = "获取方式",
                    subtitle = when {
                        autoMode -> "全自动：从 MultiTTS apk 提取 so；语音包取 apk 内嵌包或其数据目录"
                        mtInstalled -> "so 尝试直读 MultiTTS apk，读不动请手动选；语音包请选择 zip 或文件夹"
                        else -> "手动：选择 so 来源（shim 等无版权风险库已内置）+ 语音包 zip 或文件夹"
                    })
                if (!autoMode) {
                    if (!soPath.isBlank()) {
                        SettingRow(Icons.Default.Info, "so 来源（手动）",
                            "${soPath.split("\n").size} 项已选，点击清除",
                            onClick = {
                                soPath = ""; prefs.soPath(""); Actions.invalidate(ctx)
                                toast("已清除 so 来源")
                            })
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("选 so 来源", Icons.Default.Edit,
                            { soLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("选语音包 zip", Icons.Default.Edit,
                        { zipLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                    ActionButton("选语音包文件夹", Icons.Default.Edit,
                        { treeLauncher.launch(null) }, Modifier.weight(1f))
                }
                EditRow(Icons.Default.Edit, "语音包路径（可手填普通路径）", customPath,
                    "例如 /sdcard/AAA/apk/MultiTTS", onCommit = {
                        customPath = it; prefs.customPath(it); Actions.invalidate(ctx); toast("已保存")
                    }, subtitle = "解压好的 midu 资源根、语音包 zip / apk 都行；Shizuku 在场还支持文件夹里放包按需解压")
                EditRow(Icons.Default.Edit, "MultiTTS midu 根目录", multitts,
                    Prefs.DEFAULT_MULTITTS_ROOT, onCommit = {
                        multitts = it; prefs.multittsRoot(it); Actions.invalidate(ctx); toast("已保存")
                    }, subtitle = "全自动链路的兜底：apk 里没有语音包时直读这里")
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("试读 ptl.dat", Icons.Default.PlayArrow, {
                        Actions.probeSource(ctx) { toast(it) }
                    }, Modifier.weight(1f))
                }
            }
        }

        item {
            SectionLabel("音色与后端")
            GroupCard {
                DropdownRow(Icons.Default.Settings, "当前音色",
                    "语音包里的所有音色；切换后常驻引擎会在下次合成自动重启（首次稍慢）",
                    voices, voice, onSelect = {
                        voice = it; prefs.voice(it); Actions.invalidate(ctx)
                        toast("音色=$it · 下次合成按新音色重启常驻引擎")
                    })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ActionButton("刷新音色列表", Icons.Default.Refresh, {
                        Actions.listVoices(ctx) { list, err ->
                            if (list.isNotEmpty()) { voices = list; toast("找到 ${list.size} 个音色") }
                            else toast("没枚举到音色：$err")
                        }
                    }, Modifier.fillMaxWidth())
                }
                DropdownRow(Icons.Default.Settings, "运行后端",
                    "auto = 进程内 JNI 失败自动降级 host2；inprocess = 支持全部 tts_* 选项",
                    listOf(Prefs.BACKEND_AUTO, Prefs.BACKEND_INPROCESS, Prefs.BACKEND_HOST2),
                    backend, onSelect = {
                        backend = it; prefs.backend(it); Actions.invalidate(ctx); toast("后端=$it")
                    })
                EditRow(Icons.Default.Edit, "host2 rig 路径", rigPath,
                    Prefs.DEFAULT_HOST2_RIG, onCommit = {
                        rigPath = it; prefs.host2Rig(it); toast("已保存")
                    })
                SwitchRow(
                    icon = Icons.Default.Settings,
                    title = "host2 常驻引擎",
                    subtitle = "--serve / socket：模型只加载一次、PCM 逐块回传；缺 host2_serve 会回退一次性 exec。端口 ${prefs.host2DaemonPort()}",
                    checked = daemonOn,
                    onCheckedChange = { on ->
                        daemonOn = on; prefs.host2DaemonEnabled(on)
                        if (!on) Actions.restartDaemon(ctx) { }
                        toast(if (on) "已启用常驻后端（下次合成生效）" else "已关闭常驻后端")
                    }
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ActionButton("重启常驻引擎", Icons.Default.Refresh, {
                        Actions.restartDaemon(ctx) { toast(it) }
                    }, Modifier.fillMaxWidth())
                }
            }
        }

        item {
            SectionLabel("Shizuku")
            GroupCard {
                SettingRow(Icons.Default.Info, "连接状态", ShizukuClient.describe())
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    ActionButton("检测 / 授权", Icons.Default.PlayArrow, {
                        val act = ctx as? android.app.Activity
                        if (act != null) {
                            Actions.requestShizuku(act)
                            toast("如果弹出授权框，请允许")
                        } else toast("拿不到 Activity，请从经典设置页授权")
                    }, Modifier.fillMaxWidth())
                }
            }
        }

        item {
            SectionLabel("环境诊断", trailing = {
                TextButton(onClick = { diagOpen = !diagOpen; diag = Actions.diagnostics(ctx) }) {
                    Text(if (diagOpen) "收起" else "展开")
                }
            })
            GroupCard {
                SettingRow(Icons.Default.Info, "ABI / 库 / 资源自检",
                    "展开看完整报告；排查 MuMu、Shizuku、缺库时用", onClick = { diagOpen = !diagOpen })
                if (diagOpen) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // 报告很长，限高并内部滚动，避免把整个诊断页撑成一屏放不下的长卡片。
                        Text(diag, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()))
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            if (Actions.copy(ctx, "diagnostics", diag)) toast("已复制诊断报告")
                        }) { Text("复制报告") }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
