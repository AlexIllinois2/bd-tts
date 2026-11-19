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
import androidx.compose.runtime.mutableIntStateOf
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
import com.bd.tts.audiobook.TtsBookReader
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
 * 性能：书架、行索引、当前行文本全部在 IO 线程读取；界面只保留当前行的一小段文本，
 * 不持有整本书，避免上百 MB 时 ANR / OOM。
 */
@Composable
fun AudiobookPage(refresh: Int, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AudiobookState.ui.collectAsStateWithLifecycle()

    var books by remember { mutableStateOf(emptyList<TtsBook>()) }
    var positions by remember { mutableStateOf(emptyMap<String, Int>()) }
    var booksLoading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(state.bookId.ifBlank { BookPrefs.lastBook(ctx) }) }
    var reader by remember { mutableStateOf<TtsBookReader?>(null) }
    var loading by remember { mutableStateOf(false) }
    var shownSentences by remember { mutableStateOf(emptyList<String>()) }
    var cursor by remember { mutableIntStateOf(0) }
    var slider by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var jumpDialog by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var removeId by remember { mutableStateOf<String?>(null) }
    var timerDialog by remember { mutableStateOf(false) }
    var timerText by remember { mutableStateOf(BookPrefs.sleepMinutes(ctx).toString()) }

    val total = if (state.bookId == selected && state.lineCount > 0) state.lineCount
                else reader?.lineCount ?: 0
    val maxLine = maxOf(0, total - 1)
    val playingThis = state.bookId == selected && state.playing
    val busyThis = state.bookId == selected && state.preparing
    val shownLine = if (state.bookId == selected && state.lineCount > 0)
        state.line.coerceIn(0, maxLine) else cursor.coerceIn(0, maxLine)
    val bookName = books.firstOrNull { it.id == selected }?.name
        ?: selected.ifBlank { "未选择听书" }

    // 书架列表：建索引 / 读行数都在 IO 线程，主线程只拿结果。
    LaunchedEffect(refresh) {
        booksLoading = true
        val loaded = withContext(Dispatchers.IO) { TtsBooks.list(ctx) }
        books = loaded
        positions = loaded.associate { it.id to BookPrefs.position(ctx, it.id) }
        booksLoading = false
    }

    LaunchedEffect(selected) {
        if (selected.isBlank()) {
            reader = null
            shownSentences = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val (r, pos) = withContext(Dispatchers.IO) {
            val opened = TtsBooks.open(ctx, selected)
            opened to BookPrefs.position(ctx, selected)
        }
        reader = r
        cursor = pos.coerceIn(0, maxOf(0, (r?.lineCount ?: 1) - 1))
        loading = false
    }

    // 读取当前行的完整纯文本并按句切分（只切显示用，与合成流水线互不相干）；
    // 界面一次只呈现一句：太小的显示区放不下两句。全部在 IO 线程完成。
    LaunchedEffect(shownLine, reader) {
        val r = reader
        shownSentences = if (r == null) emptyList() else withContext(Dispatchers.IO) {
            // 显示整行按句切分、去掉注音标记；合成仍由服务读原文，发音不受影响。
            AudiobookText.sentences(AudiobookText.display(r.textAt(shownLine), 20000))
        }
    }

    // 从状态栏通知点进 App / 在通知里切书时，界面跟着切过去。
    LaunchedEffect(state.bookId, books) {
        if (state.bookId.isNotBlank() && state.bookId != selected &&
            books.any { it.id == state.bookId }) selected = state.bookId
    }

    LaunchedEffect(cursor, state.fraction, state.bookId, selected, total) {
        if (dragging) return@LaunchedEffect
        slider = if (state.bookId == selected && state.lineCount > 0)
            (state.line + state.fraction).coerceIn(0f, total.toFloat())
        else cursor.toFloat()
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
            val loaded = withContext(Dispatchers.IO) { TtsBooks.list(ctx) }
            books = loaded
            positions = loaded.associate { it.id to BookPrefs.position(ctx, it.id) }
            imported.lastOrNull()?.let { b ->
                selected = b.id
                cursor = 0
                toast("已从原位置添加「${b.name}」· ${b.lines} 行")
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

    fun playFrom(line: Int) {
        if (selected.isBlank()) { toast("请先添加并选择一本听书"); return }
        ensureNotifPermission()
        BookPrefs.setLastBook(ctx, selected)
        AudiobookService.play(ctx, selected, line)
    }

    fun jumpTo(line: Int) {
        if (selected.isBlank()) return
        val target = line.coerceIn(0, maxLine)
        cursor = target
        BookPrefs.savePosition(ctx, selected, target, total)
        if (state.bookId == selected && state.lineCount > 0) AudiobookService.seek(ctx, target)
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
                                    "${if (b.lines < 0) "—" else b.lines} 行 · ${b.bytes / 1024} KB · " +
                                        "上次第 ${(positions[b.id] ?: 0) + 1} 行",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { selected = b.id; playFrom(-1) }) {
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
                        Text("第 ${shownLine + 1} 行", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        if (state.bookId == selected && state.sentenceCount > 0) {
                            Text("第 ${state.sentence + 1}/${state.sentenceCount} 句",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("共 $total 行", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(
                        value = slider.coerceIn(0f, total.toFloat()),
                        onValueChange = { dragging = true; slider = it },
                        onValueChangeFinished = { dragging = false; jumpTo(slider.toInt()) },
                        valueRange = 0f..maxOf(1f, total.toFloat()),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.message.isNotBlank() && state.bookId == selected) {
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
                            enabled = state.bookId == selected) {
                            Icon(Icons.Default.SkipPrevious, "上一行")
                        }
                        IconButton(onClick = {
                            when {
                                playingThis -> AudiobookService.pause(ctx)
                                state.bookId == selected && !busyThis -> AudiobookService.resume(ctx)
                                else -> playFrom(-1)
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
                            enabled = state.bookId == selected) {
                            Icon(Icons.Default.SkipNext, "下一行")
                        }
                        IconButton(onClick = { AudiobookService.stop(ctx) },
                            enabled = state.bookId == selected) {
                            Icon(Icons.Default.Stop, "停止")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            jumpText = (shownLine + 1).toString()
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
                    // 当前句显示放在播放控制区下面：句长不同导致的高度变化
                    // 不会再把按钮顶来顶去，影响按按钮。一次只显示当前朗读的那一句，
                    // 不播放时显示该行第一句；句比显示区高时区域内滚动，换句回到顶部。
                    val activeSentence = if (state.bookId == selected &&
                        state.sentence in shownSentences.indices) state.sentence else 0
                    val currentSentence = shownSentences.getOrNull(activeSentence).orEmpty()
                    val textScroll = rememberScrollState()
                    LaunchedEffect(shownLine, activeSentence) { textScroll.scrollTo(0) }
                    // 高度固定为一页（屏幕高度减去页面上下留白），与文本长短无关；
                    // 内容超出时在区域内滚动，按钮区完全不受影响。
                    val pageHeight = LocalConfiguration.current.screenHeightDp.dp - 16.dp
                    Box(
                        Modifier.fillMaxWidth().height(pageHeight)
                            .verticalScroll(textScroll)
                    ) {
                        when {
                            loading -> Text("读取中…", style = MaterialTheme.typography.bodyMedium)
                            currentSentence.isBlank() ->
                                Text("（空行 / 注释行，会自动跳过）", style = MaterialTheme.typography.bodyMedium)
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
            title = { Text("跳转到指定行") },
            text = {
                Column {
                    Text("共 $total 行，行号从 1 开始；空行与 # 注释行也占行号但不会朗读。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = jumpText, onValueChange = { jumpText = it },
                        label = { Text("行号（1 - $total）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = jumpText.trim().toIntOrNull()
                    when {
                        total == 0 -> toast("请先添加一本听书")
                        n == null || n < 1 || n > total -> toast("请输入 1 到 $total 之间的行号")
                        else -> { jumpTo(n - 1); jumpDialog = false }
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
                    Text("倒计时结束后自动停止播放；时长会记住，下次打开自动填入。",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
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
            dismissButton = {
                TextButton(onClick = {
                    val wasActive = SleepTimer.remainingMs() > 0
                    SleepTimer.cancel()
                    timerDialog = false
                    if (wasActive) toast("已取消定时")
                }) { Text(if (SleepTimer.remainingMs() > 0) "取消定时" else "取消") }
            }
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
