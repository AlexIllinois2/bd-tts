package com.bd.tts.audiobook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.bd.tts.engine.EngineController
import com.bd.tts.sound.Knobs
import com.bd.tts.sound.SoundProfiles
import com.bd.tts.sound.StereoWiden
import com.bd.tts.ui.MainActivity
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * 听书前台播放服务：把一行按句末标点切句、长句再切片段，**逐片段**合成播放。
 *
 * 合成流水线：producer 线程用游标按「行 → 句 → 片段」顺序持续合成，放进有界队列
 * （默认领先播放位置多个片段）；worker 线程只管从队列取现成 PCM 播放。宿主合成
 * 往往比播放慢，提前排队能把长句的等待藏进前面几句的播放时间里，消除句间卡顿。
 * 队列有背压：播得慢时 producer 停在队列满处，不会无限合成占用内存。
 *
 * 暂停 / 跳行用「代数（generation）」作令牌：引擎合成同步且不可中断，旧任务合成完
 * 发现代数变了就丢弃；队列元素带代数校验，跳行 / 停止时整队作废，不会串音。
 * 音频用 WRITE_NON_BLOCKING，暂停能在片段内部立即生效。
 */
class AudiobookService : Service() {

    companion object {
        const val ACTION_PLAY = "com.bd.tts.audiobook.PLAY"
        const val ACTION_PAUSE = "com.bd.tts.audiobook.PAUSE"
        const val ACTION_RESUME = "com.bd.tts.audiobook.RESUME"
        const val ACTION_TOGGLE = "com.bd.tts.audiobook.TOGGLE"
        const val ACTION_STOP = "com.bd.tts.audiobook.STOP"
        const val ACTION_PREV = "com.bd.tts.audiobook.PREV"
        const val ACTION_NEXT = "com.bd.tts.audiobook.NEXT"
        const val ACTION_SEEK = "com.bd.tts.audiobook.SEEK"
        const val EXTRA_BOOK = "book"
        const val EXTRA_LINE = "line"

        private const val CHANNEL_ID = "bd_audiobook"
        private const val NOTIF_ID = 2001
        private const val TAG = "BdAudiobook"
        /** 单行送给引擎的上限：超长行不截断会把 native 前端和内存拖垮。 */
        private const val MAX_SYNTH_CHARS = 2000
        /** 进度每 3 秒落盘一次即可；行切换 / 暂停 / 停止时强制落盘。 */
        private const val SAVE_INTERVAL_MS = 3000L
        /** 行内进度最多 8fps 推给 Compose，避免每写 8KB 就触发一次重组。 */
        private const val FRACTION_UI_INTERVAL_MS = 120L
        /**
         * 预合成队列容量：领先播放位置这么多个片段。host2 一句合成可能十几秒，
         * 队列越深越能扛住长句，但 PCM 常驻内存（16-bit/24kHz，长片段单片可达数百 KB），
         * 8 片是体积与流畅度的折中。播得慢时 producer 停在队列满处，不会无限增长。
         */
        private const val PREFETCH_QUEUE_SIZE = 8

        /** line < 0 表示按记忆的位置续播。 */
        fun play(c: Context, bookId: String, line: Int = -1) {
            start(c, Intent(c, AudiobookService::class.java).setAction(ACTION_PLAY)
                .putExtra(EXTRA_BOOK, bookId).putExtra(EXTRA_LINE, line))
        }

        fun control(c: Context, action: String) {
            start(c, Intent(c, AudiobookService::class.java).setAction(action))
        }

        fun pause(c: Context) = control(c, ACTION_PAUSE)
        fun resume(c: Context) = control(c, ACTION_RESUME)
        fun stop(c: Context) = control(c, ACTION_STOP)
        fun prev(c: Context) = control(c, ACTION_PREV)
        fun next(c: Context) = control(c, ACTION_NEXT)
        fun seek(c: Context, line: Int) {
            start(c, Intent(c, AudiobookService::class.java).setAction(ACTION_SEEK)
                .putExtra(EXTRA_LINE, line))
        }

        private fun start(c: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
            else @Suppress("DEPRECATION") c.startService(i)
        }
    }

    private val lock = Object()
    private val gen = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var worker: Thread? = null
    @Volatile private var paused = false
    @Volatile private var bookId = ""
    @Volatile private var lineIndex = 0
    @Volatile private var lineCount = 0

    /**
     * 一个朗读片段：行号 + 行内句号 + 句内片段号 + 全局序号。
     * seq 单调递增，是队列元素与代数的匹配令牌，跳行后旧 seq 一律作废。
     */
    private data class PlayUnit(
        val seq: Long,
        val line: Int,
        val sentence: Int,
        val chunk: Int,
        val sentenceCount: Int,
        val chunkCount: Int,
        val text: String
    )

    /** 已合成好的片段；带代数 + seq + 文本三重校验，跳行/停止后整队作废。 */
    private data class ReadyPcm(
        val g: Int, val unit: PlayUnit, val pcm: ByteArray
    )

    /** 有界流水线队列：满时 producer 阻塞，形成背压。 */
    @Volatile private var queue: ArrayBlockingQueue<ReadyPcm>? = null
    @Volatile private var producer: Thread? = null
    @Volatile private var producerError: String? = null
    private val seqCounter = AtomicInteger(0)

    private var lastSaveMs = 0L
    private var lastFractionUiMs = 0L
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSession(this, "bd_audiobook").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resumePlayback() }
                override fun onPause() { pausePlayback() }
                override fun onStop() { stopPlayback() }
                override fun onSkipToNext() { seekRelative(1) }
                override fun onSkipToPrevious() { seekRelative(-1) }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务必须在 5 秒内贴出通知：先贴当前状态，再处理动作。
        startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_PLAY -> {
                val id = intent?.getStringExtra(EXTRA_BOOK)?.takeIf { it.isNotBlank() }
                    ?: AudiobookState.ui.value.bookId
                if (id.isNullOrBlank()) {
                    AudiobookState.update { it.copy(message = "请先添加并选择一本听书") }
                } else {
                    val line = intent?.getIntExtra(EXTRA_LINE, -1) ?: -1
                    if (id == bookId && line < 0 && worker?.isAlive == true) resumePlayback()
                    else startBook(id, line, pausedAtStart = false)
                }
            }
            ACTION_SEEK -> seekTo(intent?.getIntExtra(EXTRA_LINE, 0) ?: 0)
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_TOGGLE -> if (paused) resumePlayback() else pausePlayback()
            ACTION_PREV -> seekRelative(-1)
            ACTION_NEXT -> seekRelative(1)
            ACTION_STOP -> stopPlayback()
        }
        if (AudiobookState.ui.value.bookId.isNotBlank()) updateNotification()
        return START_NOT_STICKY
    }

    // --------------------------------------------------------------- 播放控制

    private fun startBook(id: String, requestedLine: Int, pausedAtStart: Boolean) {
        val f = TtsBooks.file(this, id)
        if (f == null) {
            AudiobookState.update { it.copy(message = "听书文件不存在或已被移除") }
            return
        }
        bookId = id
        BookPrefs.setLastBook(this, id)
        val g = gen.incrementAndGet()
        clearPrefetch()
        producerError = null
        synchronized(lock) { paused = pausedAtStart; lock.notifyAll() }
        worker?.interrupt()
        // 新队列 + 新生产者：先启动 producer 尽早开始合成首句，worker 从队列消费。
        val q = ArrayBlockingQueue<ReadyPcm>(PREFETCH_QUEUE_SIZE)
        queue = q
        seqCounter.set(0)
        val prod = Thread({ produceBook(g, id, requestedLine) }, "bd-audiobook-producer-$g")
            .apply { isDaemon = true }
        producer = prod
        prod.start()
        val t = Thread({ runBook(g) }, "bd-audiobook-$g").apply { isDaemon = true }
        worker = t
        t.start()
        AudiobookState.update {
            it.copy(
                bookId = id, bookName = f.nameWithoutExtension,
                line = if (requestedLine < 0) it.line else requestedLine,
                lineCount = lineCount, sentence = 0, sentenceCount = 0,
                fraction = 0f,
                playing = !pausedAtStart, preparing = !pausedAtStart, message = ""
            )
        }
        session?.isActive = true
        if (!pausedAtStart) acquireWakeLock()
    }

    /** 取某一行的句子列表（原文、去空）。整行只读当前行，切句后每句单独限长交给引擎。 */
    private fun sentencesOf(reader: TtsBookReader, line: Int): List<String> {
        val raw = reader.textAt(line).trim()
        if (raw.isEmpty() || raw.startsWith("#")) return emptyList()
        return AudiobookText.sentences(raw)
    }

    /**
     * 朗读游标：按「行 → 句 → 片段」顺序推进，自动跳过空行 / 注释行。
     * 只由 producer 线程使用，因此无需同步。null 表示读完全书。
     */
    private class BookCursor(
        private val service: AudiobookService,
        private val reader: TtsBookReader,
        private val total: Int,
        startLine: Int
    ) {
        private var line = startLine.coerceIn(0, maxOf(0, total - 1))
        private var sentences: List<String> = service.sentencesOf(reader, line)
        private var sentence = 0
        private var chunks: List<String> = if (sentences.isEmpty()) emptyList()
            else AudiobookText.chunks(sentences[0])
        private var chunk = 0

        val currentLine: Int get() = line

        fun next(): PlayUnit? {
            while (true) {
                if (chunk < chunks.size) {
                    val text = chunks[chunk]
                    chunk++
                    // 纯标点片段引擎读不出内容，只会合成几秒静音（表现为段尾停顿），
                    // 还白耗一次合成往返——直接跳过。tidy 已合并 ≤2 字碎片，这里兜底更长的情况。
                    if (text.none { it.isLetterOrDigit() }) continue
                    val seq = service.seqCounter.getAndIncrement().toLong()
                    val u = PlayUnit(
                        seq, line, sentence, chunk - 1, sentences.size, chunks.size, text
                    )
                    return u
                }
                // 本句片段读完 → 下一句
                sentence++
                if (sentence < sentences.size) {
                    chunks = AudiobookText.chunks(sentences[sentence])
                    chunk = 0
                    continue
                }
                // 本行读完 → 下一非空行
                line++
                if (line >= total) return null
                sentences = service.sentencesOf(reader, line)
                sentence = 0
                chunks = emptyList()
                chunk = 0
                if (sentences.isNotEmpty()) chunks = AudiobookText.chunks(sentences[0])
            }
        }
    }

    /** seq = -1 的哨兵：producer 结束（读完或失败）时入队，唤醒阻塞在 take 的 worker。 */
    private fun endMarker(g: Int) = ReadyPcm(
        g, PlayUnit(-1L, 0, 0, 0, 0, 0, ""), ByteArray(0)
    )

    /**
     * producer：建立行索引后，用游标持续合成片段塞进有界队列。
     * 队列满时 put 阻塞形成背压；代数变化 / 中断即退出。合成是同步不可中断的，
     * 若恰好卡在引擎里，合成完发现代数变了就丢弃，不会污染新队列。
     * 无论正常读完还是失败，都会入队一个哨兵唤醒 worker（代数已变 / 被中断时除外）。
     */
    private fun produceBook(g: Int, id: String, requestedLine: Int) {
        try {
            produceIntoQueue(g, id, requestedLine)
        } finally {
            if (g == gen.get() && !Thread.currentThread().isInterrupted) {
                try {
                    queue?.put(endMarker(g))
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun produceIntoQueue(g: Int, id: String, requestedLine: Int) {
        val reader = try {
            TtsBooks.open(this, id)
        } catch (t: Throwable) {
            Log.e(TAG, "建立听书行索引失败", t)
            producerError = "读取失败：${t.message}"
            return
        }
        if (g != gen.get()) return
        if (reader == null || reader.lineCount == 0) {
            producerError = "文件里没有可朗读的行"
            return
        }

        val total = reader.lineCount
        lineCount = total
        var start = requestedLine
        if (start < 0) start = BookPrefs.position(this, id)   // 恒定续播；重听就跳到第 1 行
        start = start.coerceIn(0, total - 1)
        lineIndex = start
        savePositionIfDue(force = true)
        AudiobookState.update { it.copy(lineCount = total) }

        val cursor = BookCursor(this, reader, total, start)
        val q = queue ?: return
        while (g == gen.get() && !Thread.currentThread().isInterrupted) {
            val unit = cursor.next() ?: break
            val pcm = try {
                synthesize(unit.text)
            } catch (t: Throwable) {
                Log.e(TAG, "第 ${unit.line + 1} 行片段合成失败", t)
                producerError = "第 ${unit.line + 1} 行合成失败：${t.message}"
                return
            }
            if (g != gen.get()) return
            try {
                q.put(ReadyPcm(g, unit, pcm))   // 满则阻塞，形成背压
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** worker：只从队列取已合成片段播放，不再触碰引擎，因此不会被合成耗时拖住。 */
    private fun runBook(g: Int) {
        val q = queue ?: return
        while (g == gen.get()) {
            val ready = try {
                q.take()   // 队列空时等待 producer
            } catch (e: InterruptedException) {
                return
            }
            if (g != gen.get() || ready.g != g) continue
            val unit = ready.unit
            if (unit.seq < 0) break   // 结束哨兵：读完或 producer 失败
            lineIndex = unit.line
            savePositionIfDue()
            val inLine = (unit.sentence + (unit.chunk + 1).toFloat() / maxOf(1, unit.chunkCount))
                .toFloat() / maxOf(1, unit.sentenceCount)
            AudiobookState.update {
                it.copy(
                    line = unit.line, lineCount = lineCount, sentence = unit.sentence,
                    sentenceCount = unit.sentenceCount,
                    fraction = inLine.coerceIn(0f, 1f),
                    preparing = false, playing = !paused, message = ""
                )
            }
            main.post { updateNotification() }

            if (!waitWhilePaused(g) || g != gen.get()) return
            if (!playPcm(g, ready.pcm, unit) || g != gen.get()) return
        }

        if (g == gen.get()) {
            savePositionIfDue(force = true)
            val err = producerError
            main.post {
                stopPlayback(clearBook = false, message = err ?: "播放完毕")
            }
        }
    }

    private fun synthesize(text: String): ByteArray =
        EngineController.get(this).synthesize(
            text.take(MAX_SYNTH_CHARS), SoundProfiles.active(this), 100, 100
        )

    private fun clearPrefetch() {
        queue?.clear()
        producer?.interrupt()
        producer = null
        queue = null
    }

    /** 位置保存节流：逐片段 apply() 在上百万行的书里会造成磁盘写风暴。producer/worker 共用，需同步。 */
    @Synchronized
    private fun savePositionIfDue(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSaveMs < SAVE_INTERVAL_MS) return
        lastSaveMs = now
        val id = bookId
        if (id.isNotBlank() && lineCount > 0)
            BookPrefs.savePosition(this, id, lineIndex, lineCount)
    }

    /** WRITE_NON_BLOCKING + 显式 pause：暂停和拖动能在片段内部立即生效。 */
    private fun playPcm(g: Int, pcm: ByteArray, unit: PlayUnit): Boolean {
        val profile = SoundProfiles.active(this)
        val widener = StereoWiden(profile.intOf(Knobs.K_STEREO_WIDTH), 24000)
        val stereo = widener.active()
        val data = if (stereo) widener.widen(pcm, pcm.size) else pcm
        val channels = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val frameBytes = if (stereo) 4 else 2
        val minBuf = AudioTrack.getMinBufferSize(24000, channels, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            AudiobookState.update { it.copy(message = "音频设备不可用（minBuffer=$minBuf）") }
            return false
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000).setChannelMask(channels).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf, 8192)).build()

        var ok = true
        try {
            track.play()
            var off = 0
            val chunk = max(2048, min(8192, minBuf))
            while (off < data.size) {
                if (g != gen.get()) { ok = false; break }
                if (paused) {
                    track.pause()
                    if (!waitWhilePaused(g)) { ok = false; break }
                    track.play()
                }
                val n = track.write(data, off, minOf(chunk, data.size - off),
                    AudioTrack.WRITE_NON_BLOCKING)
                when {
                    n > 0 -> {
                        off += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFractionUiMs >= FRACTION_UI_INTERVAL_MS) {
                            lastFractionUiMs = now
                            val inSentence = (unit.chunk + off.toFloat() / data.size) /
                                maxOf(1, unit.chunkCount)
                            val inLine = (unit.sentence + inSentence) / maxOf(1, unit.sentenceCount)
                            AudiobookState.update { it.copy(fraction = inLine.coerceIn(0f, 1f)) }
                        }
                    }
                    n == 0 -> if (!sleepShort(10)) { ok = false; break }
                    else -> { ok = false; break }
                }
            }
            // 数据写完不等于播完，等缓冲排空才进入下一行。
            val totalFrames = data.size / frameBytes
            val deadline = SystemClock.elapsedRealtime() +
                    data.size / (24L * frameBytes) + 5000L
            while (ok && g == gen.get() && track.playbackHeadPosition < totalFrames
                && SystemClock.elapsedRealtime() < deadline) {
                if (paused) {
                    track.pause()
                    if (!waitWhilePaused(g)) { ok = false; break }
                    track.play()
                } else if (!sleepShort(30)) { ok = false; break }
            }
            if (ok) AudiobookState.update {
                val done = (unit.sentence + (unit.chunk + 1).toFloat() / maxOf(1, unit.chunkCount)) /
                    maxOf(1, unit.sentenceCount)
                it.copy(fraction = done.coerceIn(0f, 1f))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "播放失败", t)
            AudiobookState.update { it.copy(message = "播放失败：${t.message}") }
            ok = false
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
        return ok && g == gen.get()
    }

    private fun waitWhilePaused(g: Int): Boolean {
        synchronized(lock) {
            while (paused && g == gen.get()) {
                try { lock.wait(200) } catch (_: InterruptedException) { return false }
            }
        }
        return g == gen.get()
    }

    private fun sleepShort(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun pausePlayback() {
        if (bookId.isBlank() || worker?.isAlive != true) return
        synchronized(lock) { paused = true; lock.notifyAll() }
        savePositionIfDue(force = true)
        releaseWakeLock()
        AudiobookState.update { it.copy(playing = false, message = "已暂停") }
        updateNotification()
    }

    private fun resumePlayback() {
        if (bookId.isBlank()) return
        if (worker?.isAlive != true) {
            startBook(bookId, -1, pausedAtStart = false)
            return
        }
        synchronized(lock) { paused = false; lock.notifyAll() }
        acquireWakeLock()
        session?.isActive = true
        AudiobookState.update { it.copy(playing = true, preparing = false, message = "") }
        updateNotification()
    }

    private fun seekTo(line: Int) {
        val id = bookId
        if (id.isBlank()) {
            val total = AudiobookState.ui.value.lineCount
            AudiobookState.update {
                it.copy(line = line.coerceIn(0, maxOf(0, total - 1)), fraction = 0f)
            }
            return
        }
        val target = line.coerceIn(0, maxOf(0, lineCount - 1))
        val wasPaused = paused
        savePositionIfDue(force = true)
        startBook(id, target, pausedAtStart = wasPaused)
        updateNotification()
    }

    private fun seekRelative(delta: Int) {
        if (bookId.isBlank()) return
        seekTo(lineIndex + delta)
    }

    private fun stopPlayback(clearBook: Boolean = true, message: String = "已停止") {
        gen.incrementAndGet()
        clearPrefetch()
        synchronized(lock) { paused = false; lock.notifyAll() }
        worker?.interrupt()
        worker = null
        savePositionIfDue(force = true)
        releaseWakeLock()
        session?.isActive = false
        if (clearBook) bookId = ""
        AudiobookState.update {
            it.copy(
                bookId = if (clearBook) "" else it.bookId,
                playing = false, preparing = false,
                fraction = if (message == "播放完毕") 1f else 0f,
                message = message
            )
        }
        @Suppress("DEPRECATION") stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --------------------------------------------------------------- 通知栏

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, "听书播放", NotificationManager.IMPORTANCE_LOW)
                ch.description = "听书进度、播放 / 暂停与上下行控制"
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "更新听书通知失败：${t.message}")
        }
    }

    private fun buildNotification(): Notification {
        val ui = AudiobookState.ui.value
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_IMMUTABLE else 0)
        fun svc(action: String) = PendingIntent.getService(this, action.hashCode(),
            Intent(this, AudiobookService::class.java).setAction(action), flags)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra("tab", "audiobook")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags)
        fun act(icon: Int, title: String, action: String) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), title, svc(action))
                .build()
        val playIcon = if (ui.playing) android.R.drawable.ic_media_pause
                       else android.R.drawable.ic_media_play
        val status = when {
            ui.preparing -> "合成中…"
            ui.playing -> "播放中"
            ui.message.isNotBlank() -> ui.message
            else -> "已暂停"
        }
        val text = if (ui.lineCount > 0)
            "第 ${ui.line + 1} / ${ui.lineCount} 行" +
                (if (ui.sentenceCount > 0) " · 第 ${ui.sentence + 1}/${ui.sentenceCount} 句" else "") +
                " · $status" else status

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(ui.bookName.ifBlank { "听书" })
            .setContentText(text)
            .setSubText(if (ui.lineCount > 0) "共 ${ui.lineCount} 行" else null)
            .setContentIntent(open)
            .setOngoing(ui.playing || ui.preparing)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle()
                .setMediaSession(session?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
        b.addAction(act(android.R.drawable.ic_media_previous, "上一行", ACTION_PREV))
        b.addAction(act(playIcon, if (ui.playing) "暂停" else "播放", ACTION_TOGGLE))
        b.addAction(act(android.R.drawable.ic_media_next, "下一行", ACTION_NEXT))
        b.addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "停止", ACTION_STOP))
        return b.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bd:audiobook").apply {
            setReferenceCounted(false)
            if (!isHeld) acquire()
        }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    override fun onDestroy() {
        gen.incrementAndGet()
        clearPrefetch()
        synchronized(lock) { paused = false; lock.notifyAll() }
        worker?.interrupt()
        savePositionIfDue(force = true)
        releaseWakeLock()
        try { session?.isActive = false; session?.release() } catch (_: Throwable) {}
        session = null
        super.onDestroy()
    }
}
