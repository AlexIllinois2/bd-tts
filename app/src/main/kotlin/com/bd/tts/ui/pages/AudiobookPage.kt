package com.bd.tts.ui.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bd.tts.audiobook.AudiobookService
import com.bd.tts.audiobook.AudiobookState
import com.bd.tts.audiobook.AudiobookText
import com.bd.tts.audiobook.BookPrefs
import com.bd.tts.audiobook.SleepTimer
import com.bd.tts.audiobook.TtsBook
import com.bd.tts.audiobook.TtsBooks
import com.bd.tts.ui.Actions
import com.bd.tts.ui.ActionButton
import com.bd.tts.ui.GroupCard
import com.bd.tts.ui.Pill
import com.bd.tts.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 听书页：书架（导入 / 删除）+ 播放器（续播 / 暂停 / 上下行 / 拖进度 / 跳行）+ 位置记忆。
 * .tts 格式说明见 docs/AUDIOBOOK_TTS_FORMAT.md。
 *
 * 无索引：添加瞬时完成，总行数由 C/JNI 在后台数一次记下来；书架 / 进度 / 跳转
 * 都用行号，行号 ↔ 字节偏移由 JNI 现算。界面只读共享状态里的当前行文本，
 * 不持有读取句柄、不扫正文，避免 ANR / OOM。
 */
@Composable
fun AudiobookPage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AudiobookState.ui.collectAsStateWithLifecycle()

    var books by remember { mutableStateOf(emptyList<TtsBook>()) }
    var positions by remember { mutableStateOf(emptyMap<String, Int>()) } // 每本书的记忆行号
    var booksLoading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(state.bookId.ifBlank { BookPrefs.lastBook(ctx) }) }
    var idleSentences by remember { mutableStateOf(emptyList<String>()) }  // 未播放时当前位置的句子
    var slider by remember { mutableFloatStateOf(0f) }                     // 全书行进度 0..1
    var dragging by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var removeId by remember { mutableStateOf<String?>(null) }
    var timerDialog by remember { mutableStateOf(false) }
    var timerText by remember { mutableStateOf(BookPrefs.sleepMinutes(ctx).toString()) }

    val live = state.bookId == selected           // 正在播放/暂停的是否为当前选中的书
    val selectedBook = books.firstOrNull { it.id == selected }
    val sizeBytes = if (live && state.sizeBytes > 0) state.sizeBytes
                    else selectedBook?.bytes ?: 0L
    val lineCount = if (live && state.lineCount > 0) state.lineCount
                    else selectedBook?.lines ?: -1
    val posLine = if (live) state.line else (positions[selected] ?: 0)
    val fraction = if (live) state.fraction
                   else if (lineCount > 0) (posLine.toFloat() / lineCount).coerceIn(0f, 1f) else 0f
    val playingThis = live && state.playing
    val busyThis = live && state.preparing
    val bookName = selectedBook?.name ?: selected.ifBlank { "未选择听书" }
    // 播放中用服务发布的当前行文本；未播放时读记忆位置那一行。
    val showingLive = live && state.lineText.isNotBlank()
    val shownSentences = if (showingLive) AudiobookText.sentences(state.lineText) else idleSentences
    val activeSentence = if (showingLive) state.sentence else 0
    val shownLine = when {
        dragging && lineCount > 0 -> (slider * lineCount).toInt() + 1
        live -> state.line + 1
        lineCount > 0 -> (posLine + 1).coerceAtMost(lineCount)
        else -> 0
    }

    // 书架列表：只读登记元信息，瞬时完成。缺行数的旧条目在后台补数后刷新。
    LaunchedEffect(refresh) {
        booksLoading = true
        val loaded = withContext(Dispatchers.IO) { TtsBooks.list(ctx) }
        books = loaded
        positions = loaded.associate { it.id to BookPrefs.position(ctx, it.id) }
        booksLoading = false
        val pending = loaded.filter { it.lines < 0 }
        if (pending.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                pending.forEach { TtsBooks.refreshLineCount(ctx, it.id) }
            }
            books = withContext(Dispatchers.IO) { TtsBooks.list(ctx) }
        }
    }

    // 未播放时：读记忆行所在行的文本用于展示。reader 无状态（每次显式定位），
    // 与播放线程共享也安全；只读一行，不扫描文件。
    LaunchedEffect(selected, posLine, showingLive) {
        if (showingLive || selected.isBlank()) {
            if (!showingLive) idleSentences = emptyList()
            return@LaunchedEffect
        }
        idleSentences = withContext(Dispatchers.IO) {
            val r = TtsBooks.open(ctx, selected) ?: return@withContext emptyList()
            val off = TtsBooks.lineOffset(r, posLine)
            if (off < 0) return@withContext emptyList()
            val lr = r.readLineAt(off) ?: return@withContext emptyList()
            AudiobookText.sentences(AudiobookText.display(lr.text, 20000))
        }
    }

    // 从状态栏通知点进 App / 在通知里切书时，界面跟着切过去。
    LaunchedEffect(state.bookId, books) {
        if (state.bookId.isNotBlank() && state.bookId != selected &&
            books.any { it.id == state.bookId }) selected = state.bookId
    }

    // 进度条跟随播放进度；拖动时不刷新，避免打架。
    LaunchedEffect(fraction, state.bookId, selected, dragging) {
        if (dragging) return@LaunchedEffect
        slider = fraction
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("未授予通知权限，状态栏播放控件可能不显示")
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = mutableListOf<TtsBook>()
            for (uri in uris) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { TtsBooks.import(ctx, uri, Actions.displayName(ctx, uri)) }
                }
                result.onSuccess { imported.add(it) }
                    .onFailure { toast("添加失败：${it.message}") }
            }
            // 添加瞬时完成；总行数放到 IO 线程用 C/JNI 数，不卡界面。
            withContext(Dispatchers.IO) {
                imported.forEach { TtsBooks.refreshLineCount(ctx, it.id) }
            }
            val loaded = withContext(Dispatchers.IO) { TtsBooks.list(ctx) }
            books = loaded
            positions = loaded.associate { it.id to BookPrefs.position(ctx, it.id) }
            imported.lastOrNull()?.let { b ->
                selected = b.id
                slider = 0f
                toast("已从原位置添加「${b.name}」，正在统计行数")
            }
        }
    }

    fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun playFrom(line: Int = -1) {
        if (selected.isBlank()) { toast("请先添加并选择一本听书"); return }
        ensureNotifPermission()
        BookPrefs.setLastBook(ctx, selected)
        AudiobookService.play(ctx, selected, line)
    }

    fun jumpToLine(line: Int) {
        if (selected.isBlank()) return
        if (lineCount <= 0) { toast("总行数未知，无法定位"); return }
        val target = line.coerceIn(0, lineCount - 1)
        BookPrefs.savePosition(ctx, selected, target, lineCount)
        positions = positions + (selected to target)
        slider = target.toFloat() / lineCount
        if (live) AudiobookService.seek(ctx, target)
        else toast("已定位到第 ${target + 1} 行，点「播放」从此开始")
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ------------------------------------------------------------ 书架
        item {
            SectionLabel("书架", trailing = { Pill("${books.size} 本") })
            GroupCard {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton("添加 .tts 文件", Icons.Default.Add,
                        { picker.launch(arrayOf("*/*")) }, Modifier.weight(1f))
                }
                if (booksLoading) {
                    Text("加载中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                } else {
                    books.forEachIndexed { i, b ->
                        val saved = positions[b.id]
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    selected = b.id
                                    BookPrefs.setLastBook(ctx, b.id)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (b.id == selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (b.lines >= 0) "${b.lines} 行" else "行数统计中…") +
                                        (if (saved != null) " · 第 ${saved + 1} 行" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { selected = b.id; playFrom() }) {
                                Icon(Icons.Default.PlayArrow, "播放",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { removeId = b.id }) {
                                Icon(Icons.Default.Delete, "删除",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (i < books.lastIndex) HorizontalDivider()
                    }
                }
            }
        }

        // ------------------------------------------------------------ 播放器
        item {
            SectionLabel("播放器", trailing = {
                Pill(when {
                    busyThis -> "合成中"
                    playingThis -> "播放中"
                    else -> "就绪"
                })
            })
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(bookName, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (lineCount > 0) "第 $shownLine / $lineCount 行"
                            else "行数统计中…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        if (live && state.sentenceCount > 0) {
                            Text("第 ${state.sentence + 1}/${state.sentenceCount} 句",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Slider(
                        value = slider.coerceIn(0f, 1f),
                        enabled = lineCount > 0,
                        onValueChange = { dragging = true; slider = it },
                        onValueChangeFinished = {
                            dragging = false
                            if (lineCount > 0) jumpToLine((slider * lineCount).toInt())
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.message.isNotBlank() && live) {
                        Text(state.message, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { AudiobookService.prev(ctx) },
                            enabled = live) {
                            Icon(Icons.Default.SkipPrevious, "上一行")
                        }
                        IconButton(onClick = {
                            when {
                                playingThis -> AudiobookService.pause(ctx)
                                live && !busyThis -> AudiobookService.resume(ctx)
                                else -> playFrom()
                            }
                        }) {
                            Icon(
                                imageVector = if (playingThis) Icons.Default.Pause
                                              else Icons.Default.PlayArrow,
                                contentDescription = if (playingThis) "暂停" else "播放",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(onClick = { AudiobookService.next(ctx) },
                            enabled = live) {
                            Icon(Icons.Default.SkipNext, "下一行")
                        }
                        IconButton(onClick = { AudiobookService.stop(ctx) },
                            enabled = live) {
                            Icon(Icons.Default.Stop, "停止")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            jumpText = (posLine + 1).toString()
                            jumpDialog = true
                        }) { Text("跳转") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            timerText = BookPrefs.sleepMinutes(ctx).toString()
                            timerDialog = true
                        }) {
                            Text(if (state.sleepRemainingMs > 0)
                                "定时 ${SleepTimer.format(state.sleepRemainingMs)}" else "定时")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // 一次只显示当前朗读的那一句；句比显示区高时区域内滚动，换句回到顶部。
                    val currentSentence = shownSentences.getOrNull(activeSentence).orEmpty()
                    val textScroll = rememberScrollState()
                    LaunchedEffect(activeSentence, selected) { textScroll.scrollTo(0) }
                    val pageHeight = LocalConfiguration.current.screenHeightDp.dp - 16.dp
                    Box(
                        Modifier.fillMaxWidth().height(pageHeight)
                            .verticalScroll(textScroll)
                    ) {
                        when {
                            showingLive && currentSentence.isBlank() ->
                                Text("（空行 / 注释行，会自动跳过）", style = MaterialTheme.typography.bodyMedium)
                            currentSentence.isBlank() ->
                                Text("点「播放」开始朗读，或拖动进度条定位", style = MaterialTheme.typography.bodyMedium)
                            else -> Text(currentSentence, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // ---------------------------------------------------------------- 对话框
    if (jumpDialog) {
        AlertDialog(
            onDismissRequest = { jumpDialog = false },
            title = { Text("跳转") },
            text = {
                Column {
                    OutlinedTextField(
                        value = jumpText, onValueChange = { jumpText = it },
                        label = { Text("行号（1 - $lineCount）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = jumpText.trim().toIntOrNull()
                    when {
                        lineCount <= 0 -> toast("总行数未知，请稍候重试")
                        n == null || n < 1 || n > lineCount ->
                            toast("请输入 1 到 $lineCount 之间的行号")
                        else -> { jumpToLine(n - 1); jumpDialog = false }
                    }
                }) { Text("跳转") }
            },
            dismissButton = { TextButton(onClick = { jumpDialog = false }) { Text("取消") } }
        )
    }

    if (timerDialog) {
        AlertDialog(
            onDismissRequest = { timerDialog = false },
            title = { Text("定时停止播放") },
            text = {
                Column {
                    OutlinedTextField(
                        value = timerText, onValueChange = { timerText = it },
                        label = { Text("时长（分钟，1 - 1440）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = timerText.trim().toIntOrNull()
                    if (n == null || n < 1 || n > 1440) {
                        toast("请输入 1 到 1440 之间的分钟数")
                    } else {
                        SleepTimer.start(ctx, n)
                        timerDialog = false
                        toast("已设定 $n 分钟后停止播放")
                    }
                }) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = {
                val wasActive = SleepTimer.remainingMs() > 0
                SleepTimer.cancel()
                timerDialog = false
                if (wasActive) toast("已取消定时")
            }) { Text(if (SleepTimer.remainingMs() > 0) "取消定时" else "取消") } }
        )
    }

    val pendingRemove = removeId
    if (pendingRemove != null) {
        val name = books.firstOrNull { it.id == pendingRemove }?.name ?: pendingRemove
        AlertDialog(
            onDismissRequest = { removeId = null },
            title = { Text("删除「$name」？") },
            text = { Text("只会从书架移除，不会删除你的原文件；同时清除已记忆的播放位置。正在播放这本时会先停止。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = pendingRemove
                    removeId = null
                    scope.launch {
                        if (state.bookId == id) AudiobookService.stop(ctx)
                        val ok = withContext(Dispatchers.IO) { TtsBooks.delete(ctx, id) }
                        BookPrefs.clearPosition(ctx, id)
                        if (selected == id) selected = ""
                        val loaded = withContext(Dispatchers.IO) { TtsBooks.list(ctx) }
                        books = loaded
                        positions = loaded.associate { it.id to BookPrefs.position(ctx, it.id) }
                        toast(if (ok) "已从书架移除（原文件保留）" else "移除失败，条目可能已不存在")
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text("取消") } }
        )
    }
}
