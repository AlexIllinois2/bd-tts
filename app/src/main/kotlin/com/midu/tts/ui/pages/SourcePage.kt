package com.midu.tts.ui.pages

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.midu.tts.shizuku.ShizukuClient
import com.midu.tts.ui.ActionButton
import com.midu.tts.ui.Actions
import com.midu.tts.ui.DropdownRow
import com.midu.tts.ui.EditRow
import com.midu.tts.ui.GroupCard
import com.midu.tts.ui.SectionLabel
import com.midu.tts.ui.SettingRow
import com.midu.tts.util.Prefs

@Composable
fun SourcePage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    var source by remember(refresh) { mutableStateOf(prefs.source()) }
    var customPath by remember(refresh) { mutableStateOf(prefs.customPath()) }
    var multitts by remember(refresh) { mutableStateOf(prefs.multittsRoot()) }
    var rigPath by remember(refresh) { mutableStateOf(prefs.host2Rig()) }
    var backend by remember(refresh) { mutableStateOf(prefs.backend()) }
    var voice by remember(refresh) { mutableStateOf(prefs.voice()) }
    var voices by remember(refresh) { mutableStateOf(listOf(voice)) }
    var diagOpen by remember { mutableStateOf(false) }
    var diag by remember(refresh) { mutableStateOf(Actions.diagnostics(ctx)) }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        customPath = uri.toString()
        prefs.customPath(uri.toString()).source(Prefs.SOURCE_DIRECTORY)
        source = Prefs.SOURCE_DIRECTORY
        Actions.invalidate(ctx)
        toast("已选择目录树，下次合成生效")
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionLabel("离线语音位置")
            GroupCard {
                val sourceNames = listOf("内置（APK assets）", "自定义目录 / SAF 树", "MultiTTS 字节语音包（Shizuku）")
                val sourceIds = listOf(Prefs.SOURCE_BUNDLED, Prefs.SOURCE_DIRECTORY, Prefs.SOURCE_SHIZUKU)
                DropdownRow(
                    icon = Icons.Default.Settings,
                    title = "语音来源",
                    subtitle = "厂商 .so / ptl.dat / licbag 一个字节不改；只镜像到本 app 可读的有效资源根",
                    options = sourceNames,
                    selected = sourceNames[sourceIds.indexOf(source).coerceAtLeast(0)],
                    onSelect = { name ->
                        val id = sourceIds[sourceNames.indexOf(name)]
                        source = id; prefs.source(id); Actions.invalidate(ctx)
                        toast("来源已切换：$name")
                    }
                )
                EditRow(Icons.Default.Edit, "自定义目录（普通路径或 content:// 树）", customPath,
                    "/sdcard/midu_voice", onCommit = {
                        customPath = it; prefs.customPath(it); Actions.invalidate(ctx); toast("已保存")
                    })
                EditRow(Icons.Default.Edit, "MultiTTS midu 根目录", multitts,
                    Prefs.DEFAULT_MULTITTS_ROOT, onCommit = {
                        multitts = it; prefs.multittsRoot(it); Actions.invalidate(ctx); toast("已保存")
                    })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("选目录 (SAF)", Icons.Default.Edit,
                        { treeLauncher.launch(null) }, Modifier.weight(1f))
                    ActionButton("试读 ptl.dat", Icons.Default.PlayArrow, {
                        Actions.probeSource(ctx) { toast(it) }
                    }, Modifier.weight(1f))
                }
            }
        }

        item {
            SectionLabel("音色与后端")
            GroupCard {
                DropdownRow(Icons.Default.Settings, "当前音色", "BV001 这一代固定 24 kHz / s16le / 单声道",
                    voices, voice, onSelect = {
                        voice = it; prefs.voice(it); Actions.invalidate(ctx); toast("音色=$it")
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
                SettingRow(Icons.Default.Warning, "MuMu 已知限制",
                    "x86_64 + houdini 转译下 ARM64 的 LD_PRELOAD shim 无法注入，进程内 JNI 会在 libsscronet.so 崩溃；原生 arm64 真机不受影响。")
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
                        Text(diag, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
