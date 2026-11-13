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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import com.bd.tts.legado.LegadoHttpService
import com.bd.tts.legado.LegadoImport
import com.bd.tts.legado.LegadoState
import com.bd.tts.ui.ActionButton
import com.bd.tts.ui.Actions
import com.bd.tts.ui.EditRow
import com.bd.tts.ui.GroupCard
import com.bd.tts.ui.Pill
import com.bd.tts.ui.SectionLabel
import com.bd.tts.ui.SettingRow
import com.bd.tts.ui.SwitchRow
import com.bd.tts.util.Prefs

@Composable
fun LegadoPage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.get(ctx) }
    var port by remember(refresh) { mutableStateOf(prefs.httpPort().toString()) }
    var bindAll by remember(refresh) { mutableStateOf(prefs.httpBindAll()) }
    var speakerEnabled by remember(refresh) { mutableStateOf(prefs.httpSpeakerEnabled()) }
    var emotionEnabled by remember(refresh) { mutableStateOf(prefs.httpEmotionEnabled()) }
    var autoStart by remember(refresh) { mutableStateOf(prefs.httpAutoStart()) }
    var lanHost by remember { mutableStateOf("192.168.1.100") }
    var viewTitle by remember { mutableStateOf<String?>(null) }
    var viewBody by remember { mutableStateOf("") }

    val running = LegadoState.running
    val portNum = port.toIntOrNull() ?: 8765
    val host = if (bindAll) lanHost else LegadoImport.DEFAULT_HOST
    val importJson = remember(refresh, port, bindAll, lanHost) {
        LegadoImport.build(ctx, host, portNum)
    }
    val urlRule = remember(port, host) { LegadoImport.urlRule(host, portNum) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionLabel("服务")
            GroupCard {
                SettingRow(
                    icon = if (running) Icons.Default.Check else Icons.Default.Warning,
                    title = if (running) "本地朗读服务运行中" else "本地朗读服务未启动",
                    subtitle = if (running) "http://${LegadoState.address}  ·  请求 ${LegadoState.requestCount} 次 · 合成 ${LegadoState.synthCount} 次"
                    else "启动后 Legado 的「网络朗读」可以直接连到本引擎",
                    onClick = {
                        if (running) LegadoHttpService.stop(ctx) else LegadoHttpService.start(ctx)
                        toast(if (running) "正在停止" else "正在启动（前台服务）")
                    },
                    trailing = { Pill(if (running) "RUNNING" else "STOPPED") }
                )
                if (LegadoState.lastError.isNotEmpty()) {
                    SettingRow(Icons.Default.Warning, "最近错误", LegadoState.lastError)
                }
                EditRow(Icons.Default.Settings, "监听端口", port, "8765",
                    subtitle = "修改后需重启服务才生效",
                    onCommit = {
                        port = it
                        val v = it.toIntOrNull()?.coerceIn(1024, 65535) ?: 8765
                        prefs.httpPort(v); toast("端口=$v，重启服务后生效")
                    })
                SwitchRow(Icons.Default.Share, "监听所有网卡（0.0.0.0）",
                    "默认只监听 127.0.0.1；打开后同一局域网的设备也能调用，注意只在可信网络使用",
                    bindAll, { bindAll = it; prefs.httpBindAll(it); toast("重启服务后生效") })
                if (bindAll) {
                    EditRow(Icons.Default.Info, "设备局域网 IP（仅用于生成导入 JSON）", lanHost,
                        "192.168.1.100", onCommit = { lanHost = it })
                }
                SwitchRow(Icons.Default.Settings, "随应用启动自动拉起",
                    "打开后下次在首页进入应用时自动启动服务", autoStart,
                    { autoStart = it; prefs.httpAutoStart(it) })
            }
        }

        item {
            SectionLabel("Legado 导入配置")
            GroupCard {
                SettingRow(Icons.Default.Info, "导入方式",
                    "Legado → 我的 → 朗读引擎 → 右上角导入 → 粘贴下面这段 JSON（或「网络朗读」新建后粘贴 URL 规则）")
                SettingRow(Icons.Default.Share, "发言人映射",
                    "每个本地声音预设 = 一个 toneID；Legado 多角色朗读会自动带上 currentToneID",
                    onClick = {
                        viewTitle = "speakersJson"; viewBody = LegadoImport.speakersJson(ctx)
                    })
                SettingRow(Icons.Default.Settings, "情绪映射",
                    "${LegadoImport.EMOTIONS.size} 个情绪 → 语速/音高/音量微调；命中时同时下发引擎情感项",
                    onClick = {
                        viewTitle = "emotionsJson"; viewBody = LegadoImport.emotionsJson()
                    })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("复制导入 JSON", Icons.Default.Share, {
                        if (Actions.copy(ctx, "legado-http-tts", importJson)) toast("已复制，去 Legado 导入")
                    }, Modifier.weight(1f))
                    ActionButton("分享 JSON", Icons.Default.PlayArrow, {
                        Actions.share(ctx, importJson)
                    }, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        viewTitle = "HttpTTS 导入 JSON"; viewBody = importJson
                    }) { Text("查看 JSON") }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        viewTitle = "URL 规则"; viewBody = urlRule
                    }) { Text("查看 URL 规则") }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        if (Actions.copy(ctx, "legado-url", urlRule)) toast("已复制 URL 规则")
                    }) { Text("复制规则") }
                }
            }
        }

        item {
            SectionLabel("参数覆盖策略")
            GroupCard {
                SwitchRow(Icons.Default.Settings, "发言人 → 预设",
                    "URL 里的 tone / toneId 映射到本地声音预设；关掉则一律用当前预设",
                    speakerEnabled, { speakerEnabled = it; prefs.httpSpeakerEnabled(it) })
                SwitchRow(Icons.Default.Info, "情绪 → 韵律微调",
                    "emotion / tag 命中内置情绪表后调整语速、音高、音量，并设置引擎情感项",
                    emotionEnabled, { emotionEnabled = it; prefs.httpEmotionEnabled(it) })
                SettingRow(Icons.Default.Info, "直接覆盖示例",
                    "GET /tts?text=你好&speed=25&tts_silence_duration=180  或 POST form / JSON body；请求里给的参数优先生效")
            }
        }

        item {
            SectionLabel("请求日志", trailing = {
                TextButton(onClick = { LegadoState.log("—— 手动标记 ——") }) { Text("标记") }
            })
            GroupCard {
                if (LegadoState.logs.isEmpty()) {
                    SettingRow(Icons.Default.Info, "暂无请求", "启动服务后这里会显示每次调用与合成结果")
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        LegadoState.logs.take(30).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp))
                        }
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
                LazyColumn(Modifier.height(360.dp)) {
                    item { Text(viewBody, style = MaterialTheme.typography.bodySmall) }
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
