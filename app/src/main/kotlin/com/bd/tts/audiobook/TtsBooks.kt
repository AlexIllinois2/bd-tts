package com.bd.tts.audiobook

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * 听书文件库（无索引，字节流式读取 + JNI 行数统计）。
 *
 * .tts 是本 App 自定义格式：UTF-8 纯文本，一行 = 一次朗读单元。
 * 行号按物理行从 1 开始（空行 / # 开头的行也算行号，但不朗读）。
 *
 * 文件不复制进 App：书架里保存的是用户添加时选中的 SAF URI（并申请持久读权限），
 * 播放 / 显示时直接从该 URI 所在位置读取。
 *
 * 性能取舍：**不建任何常驻的行偏移索引**。UTF-8 变长编码，按"第 N 行"随机访问
 * 传统做法是预扫全文建偏移表，添加 / 打开都要等十几秒。这里改为：
 *   - 添加 / 打开：只做 URI 登记与元数据查询，瞬时完成；
 *   - 总行数：添加后在 IO 线程用 TtsNative（C/JNI）数一次 \n，记进书架；
 *   - 行号 ↔ 字节偏移：跳转 / 续播时用 TtsNative 线性扫描现算（毫秒级），不落盘、不常驻；
 *   - 顺序朗读：从当前字节位置顺序读下一行，一步 seek + 一次 read，零扫描。
 * 每行字节数上限受 MAX_LINE_BYTES 约束，超长行会被截断并丢弃剩余部分。
 */
data class TtsBook(
    val id: String,          // 添加时选中的 URI 字符串（持久且唯一）
    val name: String,        // 不含扩展名的显示名
    val bytes: Long,         // 文件总字节数（仅用于显示大小）
    val lines: Int,          // 总行数；-1 = 未统计 / 统计失败（异步补）
    val modified: Long
)

/** 一次行读取的结果。所有偏移都是字节，0 基。 */
data class LineRead(
    val text: String,        // UTF-8 解码、去 BOM / \r、按 MAX_LINE_BYTES 截断后的行文本
    val start: Long,         // 本行行首字节偏移
    val byteLength: Long,    // 本行内容字节数（不含 \n），供计算行内进度
    val next: Long           // 下一行行首字节偏移（EOF 时 = fileSize）
)

/**
 * 一本书的流式读取句柄。
 *
 * 无状态：每个方法都先显式 channel.position(...) 再读，因此 UI 和播放线程
 * 可安全共享同一实例（与旧 textAt 的线程安全模型一致）。只持有一个复用的
 * 文件描述符，不持有整本书、不持有偏移表。
 */
class TtsBookReader internal constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    val id: String,
    val fileSize: Long,
    pfd: ParcelFileDescriptor
) {
    val isEmpty: Boolean get() = fileSize <= 0L

    /** 供 TtsNative 做行号 ↔ 字节偏移转换；reader 关闭后该 fd 失效，调用方需保证句柄有效。 */
    val fd: Int = pfd.fd

    private var pfdRef: ParcelFileDescriptor? = pfd
    private var input: FileInputStream? = FileInputStream(pfd.fileDescriptor)
    private val scanBuf = ByteArray(64 * 1024)

    /**
     * 把任意字节偏移对齐到"包含该字节的那一行的行首"：向前找 <= offset 的最后一个 \n 之后。
     * 目标恰好落在行首时原样返回。超长行会倍增回看窗口，直到文件开头。
     */
    @Synchronized
    fun lineStartAt(offset: Long): Long {
        val target = offset.coerceIn(0L, maxOf(0L, fileSize))
        if (target == 0L) return 0L
        var from = maxOf(0L, target - TtsBooks.MAX_LINE_BYTES.toLong())
        while (true) {
            val len = (target - from).toInt()
            val buf = ByteArray(len)
            if (!readExact(from, buf, len)) return from
            for (i in len - 1 downTo 0) {
                if (buf[i] == 0x0A.toByte()) return from + i + 1
            }
            if (from == 0L) return 0L
            from = maxOf(0L, from - TtsBooks.MAX_LINE_BYTES.toLong())
        }
    }

    /**
     * 上一行的行首：lineStart 必定是某行首，lineStart-1 是分隔 \n，
     * 于是对 lineStart-1 做行首对齐即可得到上一行行首。首行返回 null。
     */
    @Synchronized
    fun previousLineStart(lineStart: Long): Long? {
        if (lineStart <= 0L) return null
        return lineStartAt(lineStart - 1)
    }

    /**
     * 从 lineStart 顺序读一行。返回文本与下一行行首；EOF（lineStart >= fileSize）返回 null。
     * 读满 MAX_LINE_BYTES 仍未遇换行时，丢弃本行剩余字节直到 \n / EOF，
     * 保证 next 落在真正的下一行行首。
     */
    @Synchronized
    fun readLineAt(lineStart: Long): LineRead? {
        val stream = input ?: return null
        if (fileSize > 0 && lineStart >= fileSize) return null
        try {
            stream.channel.position(lineStart)
        } catch (t: Throwable) {
            // 不可 seek 的 provider（管道）：退回顺序跳过。
            closeHandle()
            return readLineSequentially(lineStart)
        }

        val out = ByteArrayOutputStream(512)
        val buf = ByteArray(1024)
        var consumed = 0L
        var hitNl = false
        try {
            while (out.size() < TtsBooks.MAX_LINE_BYTES) {
                val n = stream.read(buf)
                if (n <= 0) break
                var nl = -1
                for (i in 0 until n) {
                    if (buf[i] == 0x0A.toByte()) { nl = i; break }
                }
                val take = if (nl >= 0) nl else n
                if (take > 0) out.write(buf, 0, minOf(take, TtsBooks.MAX_LINE_BYTES - out.size()))
                if (nl >= 0) {
                    // 只累加到换行符（含），buffer 里换行之后的字节属于下一行。
                    consumed += nl + 1
                    hitNl = true
                    break
                }
                consumed += n
            }
            // 被 MAX_LINE_BYTES 截断：吃掉本行剩余，让 next 落在真正的下一行行首。
            if (!hitNl && out.size() >= TtsBooks.MAX_LINE_BYTES) {
                while (true) {
                    val n = stream.read(scanBuf)
                    if (n <= 0) break
                    var pos = -1
                    for (i in 0 until n) if (scanBuf[i] == 0x0A.toByte()) { pos = i; break }
                    consumed += if (pos >= 0) pos + 1 else n
                    if (pos >= 0) break
                }
            }
        } catch (t: Throwable) {
            closeHandle()
            return null
        }
        if (consumed == 0L) return null
        return finishLine(out, lineStart, consumed, hitNl)
    }

    /** 不可 seek 时的退化：新开输入流，顺序跳过 lineStart 字节再读一行。 */
    private fun readLineSequentially(lineStart: Long): LineRead? {
        val s = try { resolver.openInputStream(uri) ?: return null } catch (_: Throwable) { return null }
        s.use {
            var left = lineStart
            val skip = ByteArray(8192)
            while (left > 0) {
                val n = it.read(skip, 0, minOf(left.toInt(), skip.size))
                if (n <= 0) return null
                left -= n
            }
            val out = ByteArrayOutputStream(512)
            val buf = ByteArray(1024)
            var consumed = 0L
            var hitNl = false
            while (out.size() < TtsBooks.MAX_LINE_BYTES) {
                val n = it.read(buf)
                if (n <= 0) break
                var nl = -1
                for (i in 0 until n) if (buf[i] == 0x0A.toByte()) { nl = i; break }
                val take = if (nl >= 0) nl else n
                if (take > 0) out.write(buf, 0, minOf(take, TtsBooks.MAX_LINE_BYTES - out.size()))
                if (nl >= 0) { consumed += nl + 1; hitNl = true; break }
                consumed += n
            }
            if (!hitNl && out.size() >= TtsBooks.MAX_LINE_BYTES) {
                while (true) {
                    val n = it.read(scanBuf)
                    if (n <= 0) break
                    var pos = -1
                    for (i in 0 until n) if (scanBuf[i] == 0x0A.toByte()) { pos = i; break }
                    consumed += if (pos >= 0) pos + 1 else n
                    if (pos >= 0) break
                }
            }
            if (consumed == 0L) return null
            return finishLine(out, lineStart, consumed, hitNl)
        }
    }

    private fun finishLine(out: ByteArrayOutputStream, start: Long, consumed: Long, hitNl: Boolean): LineRead {
        var s = String(out.toByteArray(), Charsets.UTF_8)
        if (start == 0L && s.startsWith("\uFEFF")) s = s.substring(1)
        if (s.endsWith("\r")) s = s.substring(0, s.length - 1)
        val next = start + consumed
        val len = (next - start - if (hitNl) 1L else 0L).coerceAtLeast(0L)
        return LineRead(s, start, len, next)
    }

    /** 从绝对偏移读满 len 字节；不足返回 false。 */
    private fun readExact(offset: Long, buf: ByteArray, len: Int): Boolean {
        val stream = input ?: return false
        try {
            stream.channel.position(offset)
            var n = 0
            while (n < len) {
                val r = stream.read(buf, n, len - n)
                if (r <= 0) return false
                n += r
            }
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    @Synchronized
    fun close() = closeHandle()

    private fun closeHandle() {
        try { input?.close() } catch (_: Throwable) {}
        input = null
        try { pfdRef?.close() } catch (_: Throwable) {}
        pfdRef = null
    }
}

object TtsBooks {
    const val EXT = ".tts"
    internal const val MAX_LINE_BYTES = 256 * 1024

    private const val LIB_PREFS = "bd_audiobook_lib"
    private const val LIB_KEY = "books"
    private const val MIGRATED = "migrated_from_private_copies"
    private const val INDEX_REMOVED = "line_index_removed_v3"
    private const val SEP = '\u001f'

    private fun lib(c: Context) =
        c.applicationContext.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    /** 一次性清理旧版行偏移索引侧车文件（现已完全不用）。 */
    private fun removeLegacyIndex(c: Context) {
        val pref = lib(c)
        if (pref.getBoolean(INDEX_REMOVED, false)) return
        pref.edit().putBoolean(INDEX_REMOVED, true).apply()
        try {
            File(c.applicationContext.filesDir, "audiobook/.idx").deleteRecursively()
        } catch (_: Throwable) {}
    }

    /** 书架条目存在轻量偏好里：id 就是 URI 字符串，name/bytes/modified 是添加时记下的元信息。 */
    private fun readRegistry(c: Context): MutableMap<String, TtsBook> = synchronized(this) {
        removeLegacyIndex(c)
        val pref = lib(c)
        val out = LinkedHashMap<String, TtsBook>()
        (pref.getStringSet(LIB_KEY, emptySet()) ?: emptySet()).forEach { raw ->
            // 格式：id SEP name SEP bytes SEP modified SEP lines。
            // 旧数据只有前 4 段，lines 缺省 -1，由异步统计补上。
            val p = raw.split(SEP, limit = 5)
            if (p.size >= 4 && p[0].isNotBlank()) {
                out[p[0]] = TtsBook(
                    id = p[0], name = p[1],
                    bytes = p[2].toLongOrNull() ?: -1,
                    modified = p[3].toLongOrNull() ?: -1,
                    lines = if (p.size >= 5) p[4].toIntOrNull() ?: -1 else -1
                )
            }
        }
        // 一次性迁移：旧版本把文件复制进私有目录，按文件名当 id。
        // 原副本保留不动，只把它们登记成 FileProvider URI，避免老用户书架变空。
        if (!pref.getBoolean(MIGRATED, false)) {
            pref.edit().putBoolean(MIGRATED, true).apply()
            val oldDir = File(c.applicationContext.filesDir, "audiobook")
            var changed = false
            (oldDir.listFiles { f -> f.isFile && f.name.endsWith(EXT, true) }
                ?: emptyArray()).forEach { f ->
                val uri = try {
                    androidx.core.content.FileProvider.getUriForFile(
                        c, "${c.packageName}.fileprovider", f
                    )
                } catch (_: Throwable) { null } ?: return@forEach
                val id = uri.toString()
                if (!out.containsKey(id)) {
                    try {
                        c.grantUriPermission(
                            c.packageName, uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                        c.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Throwable) {}
                    out[id] = TtsBook(
                        id = id, name = f.nameWithoutExtension,
                        bytes = f.length(), modified = f.lastModified(), lines = -1
                    )
                    changed = true
                }
            }
            if (changed) writeRegistry(c, out)
        }
        out
    }

    private fun writeRegistry(c: Context, books: Map<String, TtsBook>) {
        val set = books.values.map {
            "${it.id}$SEP${it.name}$SEP${it.bytes}$SEP${it.modified}$SEP${it.lines}"
        }.toSet()
        lib(c).edit().putStringSet(LIB_KEY, set).apply()
    }

    fun get(c: Context, id: String): TtsBook? = readRegistry(c)[id]

    /** 书架列表：只读登记元信息，不打开任何文件、不扫描正文，瞬时返回。 */
    fun list(c: Context): List<TtsBook> =
        readRegistry(c).values.sortedByDescending { it.modified }

    /** 最近 3 本的读取句柄常驻内存；其余只留登记。 */
    private val cache = object : LinkedHashMap<String, TtsBookReader>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TtsBookReader>?): Boolean {
            val tooMany = size > 3
            if (tooMany) eldest?.value?.close()
            return tooMany
        }
    }

    /**
     * 打开（仅为拿一个复用文件描述符 + 准确大小）。不做任何预扫描。
     * 同步方法，调用方应在 IO 线程。
     */
    @Synchronized
    fun open(c: Context, id: String): TtsBookReader? {
        cache[id]?.let { return it }
        val book = readRegistry(c)[id] ?: return null
        val uri = try { Uri.parse(id) } catch (_: Throwable) { return null }
        val d = try { c.contentResolver.openFileDescriptor(uri, "r") } catch (_: Throwable) { null }
            ?: return null
        // 管道 fd 的 statSize 为 -1，退回登记时查询到的大小。
        val size = try { d.statSize } catch (_: Throwable) { -1L }
            .takeIf { it > 0 } ?: book.bytes
        val reader = TtsBookReader(c.applicationContext.contentResolver, uri, id, size, d)
        cache[id] = reader
        return reader
    }

    /** 查询文件大小，仅用于进度分母；provider 给不出时返回 -1。 */
    private fun querySize(c: Context, uri: Uri): Long = try {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                val i = cur.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && !cur.isNull(i)) cur.getLong(i) else -1L
            } else -1L
        } ?: -1L
    } catch (_: Throwable) {
        -1L
    }

    /**
     * 添加一个 SAF 文件到书架：不复制文件、不扫描正文、不建索引，只登记 URI
     * 并申请持久读权限。URI 本身就是 id；同一 URI 重复添加只会返回已有条目。
     */
    fun import(c: Context, uri: Uri, hintName: String?): TtsBook {
        val id = uri.toString()
        try {
            c.grantUriPermission(
                c.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {}
        try {
            c.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {}

        val reg = readRegistry(c)
        reg[id]?.let { return it }

        val raw = (hintName?.substringAfterLast('/') ?: "")
            .takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "听书"
        val name = raw.substringBeforeLast('.', raw)
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n$SEP]"), "_")
            .trim()
            .ifBlank { "听书" }
            .take(80)

        // lines 先记 -1：添加必须瞬时。行数由调用方在 IO 线程异步调 refreshLineCount 补齐。
        val book = TtsBook(
            id = id, name = name, bytes = querySize(c, uri),
            lines = -1, modified = System.currentTimeMillis()
        )
        reg[id] = book
        writeRegistry(c, reg)
        return book
    }

    /**
     * 用 C/JNI 数一次总行数并写回书架登记。同步方法，调用方必须在 IO 线程。
     * 添加后异步调用即可；书架条目 lines 仍为 -1 时会显示"统计中"。
     * 返回行数，-1 表示失败（fd 失效 / 文件读不了）。
     */
    fun refreshLineCount(c: Context, id: String, known: Int = -1): Int {
        val lines = if (known >= 0) known else {
            val d = try {
                val uri = Uri.parse(id)
                c.contentResolver.openFileDescriptor(uri, "r")
            } catch (_: Throwable) { null } ?: return -1
            try {
                TtsNative.countLines(d.fd)
            } catch (_: Throwable) {
                -1
            } finally {
                try { d.close() } catch (_: Throwable) {}
            }
        }
        if (lines >= 0) {
            synchronized(this) {
                val reg = readRegistry(c)
                reg[id]?.let { book ->
                    if (book.lines != lines) {
                        reg[id] = book.copy(lines = lines)
                        writeRegistry(c, reg)
                    }
                }
            }
        }
        return lines
    }

    /** 行号 → 行首字节偏移（C/JNI 现算，无索引）。reader 须处于打开状态。 */
    fun lineOffset(reader: TtsBookReader, line: Int): Long =
        TtsNative.lineToOffset(reader.fd, line)

    /** 字节偏移 → 0 基行号（C/JNI 现算，无索引）。reader 须处于打开状态。 */
    fun lineOf(reader: TtsBookReader, offset: Long): Int =
        TtsNative.offsetToLine(reader.fd, offset)

    /** 从书架移除（不会删除用户原文件），释放持久权限并清掉读取句柄。 */
    @Synchronized
    fun delete(c: Context, id: String): Boolean {
        cache.remove(id)?.close()
        val reg = readRegistry(c)
        if (reg.remove(id) == null) return false
        writeRegistry(c, reg)
        try {
            c.contentResolver.releasePersistableUriPermission(
                Uri.parse(id), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {}
        return true
    }
}
