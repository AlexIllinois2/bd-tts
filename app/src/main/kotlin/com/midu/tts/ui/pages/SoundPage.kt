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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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
import com.midu.tts.engine.EngineOption
import com.midu.tts.sound.Knob
import com.midu.tts.sound.Knobs
import com.midu.tts.sound.SoundProfile
import com.midu.tts.sound.SoundProfiles
import com.midu.tts.ui.ActionButton
import com.midu.tts.ui.Actions
import com.midu.tts.ui.DropdownRow
import com.midu.tts.ui.EditRow
import com.midu.tts.ui.GroupCard
import com.midu.tts.ui.Pill
import com.midu.tts.ui.SectionLabel
import com.midu.tts.ui.SettingRow
import com.midu.tts.ui.SliderRow
import com.midu.tts.ui.SwitchRow

@Composable
fun SoundPage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val groups = remember { Knobs.grouped().toList() }
    var profiles by remember(refresh) { mutableStateOf(SoundProfiles.all(ctx).map { it.copy() }) }
    var activeId by remember(refresh) { mutableStateOf(SoundProfiles.active(ctx).id) }
    var profile by remember(refresh) { mutableStateOf(SoundProfiles.active(ctx).copy()) }
    var testText by remember { mutableStateOf("今天天气不错，银行的行长在人行道上行走了很久。") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var dialogText by remember { mutableStateOf("") }

    fun update(block: SoundProfile.() -> Unit) {
        val p = profile.copy().apply(block)
        profile = p
        SoundProfiles.update(ctx, p)
        profiles = SoundProfiles.all(ctx).map { it.copy() }
    }

    fun select(id: String) {
        SoundProfiles.setActive(ctx, id)
        activeId = id
        profile = SoundProfiles.active(ctx).copy()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionLabel("当前预设")
            GroupCard(title = "系统 TTS 与 Legado 共用这套参数") {
                DropdownRow(
                    icon = Icons.Default.Settings,
                    title = "预设",
                    subtitle = profile.describe(),
                    options = profiles.map { it.name },
                    selected = profiles.firstOrNull { it.id == activeId }?.name ?: profile.name,
                    onSelect = { name ->
                        profiles.firstOrNull { it.name == name }?.let { select(it.id) }
                    }
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        dialogText = "新预设"; dialog = "add"
                    }) { Text("新建") }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        dialogText = profile.name; dialog = "rename"
                    }) { Text("重命名") }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        if (!profile.builtin) {
                            SoundProfiles.remove(ctx, profile.id); select(SoundProfiles.ID_STD)
                            toast("已删除")
                        } else toast("内置预设不可删除")
                    }) { Text("删除") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        dialogText = SoundProfiles.exportJson(ctx); dialog = "json"
                    }) { Text("导出 JSON") }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        dialogText = ""; dialog = "import"
                    }) { Text("导入 JSON") }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        SoundProfiles.reset(ctx); select(SoundProfiles.ID_STD); toast("已恢复内置预设")
                    }) { Text("重置") }
                }
            }
        }

        item {
            SectionLabel("试听（用当前预设真实合成）")
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = testText, onValueChange = { testText = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    ActionButton("合成并播放", Icons.Default.PlayArrow, {
                        Actions.play(ctx, testText) { toast(it) }
                    }, Modifier.fillMaxWidth())
                }
            }
        }

        items(groups) { (group, list) ->
            val experimental = list.all { it.evidence == Knob.STATIC }
            var expanded by remember { mutableStateOf(!experimental) }
            SectionLabel(group, trailing = {
                Pill("${list.size} 项 · ${if (experimental) "未验证" else "可下发"}")
            })
            GroupCard {
                if (experimental && !expanded) {
                    SettingRow(
                        icon = Icons.Default.Warning,
                        title = "展开 ${list.size} 项未验证能力",
                        subtitle = "这些键只在 .so 字符串里出现过，未实测；默认不下发，开启前请先试听验证。",
                        onClick = { expanded = true }
                    )
                } else {
                    list.forEach { k -> KnobControl(k, profile, ::update) }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (dialog != null) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(when (dialog) {
                "add" -> "新建预设"; "rename" -> "重命名"; "json" -> "预设 JSON"; else -> "导入预设 JSON"
            }) },
            text = {
                OutlinedTextField(value = dialogText, onValueChange = { dialogText = it },
                    modifier = Modifier.fillMaxWidth(), minLines = if (dialog == "json" || dialog == "import") 6 else 1)
            },
            confirmButton = {
                TextButton(onClick = {
                    when (dialog) {
                        "add" -> { val p = SoundProfiles.addFromActive(ctx, dialogText); select(p.id); toast("已创建") }
                        "rename" -> { SoundProfiles.rename(ctx, profile.id, dialogText)
                            profiles = SoundProfiles.all(ctx).map { it.copy() }
                            profile = profile.copy().also { it.name = dialogText } }
                        "json" -> { Actions.copy(ctx, "midu-profiles", dialogText); toast("已复制") }
                        "import" -> {
                            toast(if (SoundProfiles.importJson(ctx, dialogText)) {
                                select(SoundProfiles.active(ctx).id); "导入成功"
                            } else "JSON 格式不对")
                        }
                    }
                    dialog = null
                }) { Text(when (dialog) { "json" -> "复制"; else -> "确定" }) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun KnobControl(k: Knob, p: SoundProfile, update: (SoundProfile.() -> Unit) -> Unit) {
    val locked = k.evidence == Knob.STATIC && !p.isEnabled(k.key)
    Column {
        if (k.evidence == Knob.STATIC) {
            SwitchRow(
                icon = Icons.Default.Info,
                title = "下发 ${k.title}",
                subtitle = "${k.evidenceLabel()} · ${k.key} · 默认关闭",
                checked = p.isEnabled(k.key),
                onCheckedChange = { on -> update { setEnabled(k.key, on) } }
            )
        }
        when {
            k.isSwitch() -> SwitchRow(
                icon = Icons.Default.Settings,
                title = if (locked) "${k.title}（未启用）" else k.title,
                subtitle = "${k.key} · ${k.desc}",
                checked = p.boolOf(k.key),
                onCheckedChange = { v -> update { put(k.key, if (v) "1" else "0") } }
            )
            k.isChoice() -> DropdownRow(
                icon = Icons.Default.Edit,
                title = k.title,
                subtitle = "${k.key} · ${k.desc}",
                options = k.choices.toList(),
                selected = p.str(k.key),
                onSelect = { v -> update { put(k.key, v) } }
            )
            k.isSlider() -> SliderRow(
                title = "${k.title}  ·  ${k.evidenceLabel()}",
                value = p.intOf(k.key),
                range = k.min..k.max,
                unit = k.unit,
                subtitle = "${k.key} · ${k.desc}",
                onCommit = { v -> update { put(k.key, v.toString()) } }
            )
            else -> EditRow(
                icon = Icons.Default.Edit,
                title = k.title,
                value = p.str(k.key),
                hint = k.key,
                subtitle = "${k.evidenceLabel()} · ${k.desc}",
                onCommit = { v -> update { put(k.key, v) } }
            )
        }
    }
}
