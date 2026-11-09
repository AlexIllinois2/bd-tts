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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.midu.tts.legado.LegadoHttpService
import com.midu.tts.legado.LegadoState
import com.midu.tts.ui.ActionButton
import com.midu.tts.ui.Actions
import com.midu.tts.ui.GroupCard
import com.midu.tts.ui.HeroCard
import com.midu.tts.ui.Pill
import com.midu.tts.ui.SectionLabel
import com.midu.tts.ui.SettingRow

@Composable
fun HomePage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf(Actions.statusLines(ctx)) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) { lines = Actions.statusLines(ctx) }

    val engineStatus = lines.firstOrNull { it.first == "引擎" }?.second ?: "未初始化"
    val failed = engineStatus.contains("失败") || engineStatus.contains("不可用")
    val running = LegadoState.running

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeroCard(
                title = "离线字节语音引擎",
                subtitle = engineStatus,
                badge = when {
                    failed -> "需要检查语音包 / 后端"
                    engineStatus.contains("未初始化") -> "尚未合成 · 配置已就绪"
                    else -> "引擎已就绪"
                },
                ok = !failed,
                icon = if (failed) Icons.Default.Warning else Icons.Default.Check,
                footer = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("三份规则")
                        Pill("Shizuku")
                        Pill("Legado HTTP")
                    }
                }
            )
        }

        item {
            SectionLabel("快捷操作")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("冒烟自检", Icons.Default.PlayArrow, {
                        if (!busy) {
                            busy = true
                            Actions.smoke(ctx) { busy = false; lines = Actions.statusLines(ctx); toast(it) }
                        }
                    }, Modifier.weight(1f))
                    ActionButton("试听一句", Icons.Default.Refresh, {
                        if (!busy) {
                            busy = true
                            Actions.play(ctx, "今天天气不错，我们一起去公园散步吧。") {
                                busy = false; lines = Actions.statusLines(ctx); toast(it)
                            }
                        }
                    }, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("系统 TTS", Icons.Default.Settings,
                        { Actions.openSystemTts(ctx) }, Modifier.weight(1f))
                    ActionButton("经典设置", Icons.Default.Info,
                        { Actions.openLegacy(ctx) }, Modifier.weight(1f))
                }
            }
        }

        item {
            SectionLabel("运行状态")
            GroupCard {
                lines.forEach { (k, v) -> SettingRow(Icons.Default.Info, k, v) }
            }
        }

        item {
            SectionLabel("Legado 本地朗读服务")
            GroupCard {
                SettingRow(
                    icon = Icons.Default.Share,
                    title = if (running) "服务运行中" else "服务未启动",
                    subtitle = if (running) "http://${LegadoState.address}/tts · 已完成 ${LegadoState.synthCount} 次合成"
                    else "启动后 Legado 可通过 HttpTTS 直接调用本引擎",
                    onClick = {
                        if (running) LegadoHttpService.stop(ctx) else LegadoHttpService.start(ctx)
                        toast(if (running) "正在停止服务" else "正在启动服务")
                    },
                    trailing = { Pill(if (running) "ON" else "OFF") }
                )
                SettingRow(Icons.Default.Info, "最近请求", LegadoState.lastRequest)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
