package com.bd.tts.audiobook

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * 听书文件库。
 *
 * .tts 是本 App 自定义格式：UTF-8 纯文本，一行 = 一次朗读单元。
 * 行号按物理行从 1 开始（空行 / # 开头的行也算行号，但不朗读），
 * 这样「跳转到第 N 行」和用户用文本编辑器看到的行号一致。
 *
 * 文件不复制进 App：书架里保存的是用户添加时选中的 SAF URI（并申请持久读权限），
 * 播放 / 显示时直接从该 URI 所在位置读取。App 私有目录只放「行字节偏移」索引
 * （用文件大小校验，文件变了自动重建），因此绝不把整本读进内存，上百 MB 也能用。
 */
data class TtsBook(
    val id: String,          // 添加时选中的 URI 字符串（持久且唯一）
    val name: String,        // 不含扩展名的显示名
    val lines: Int,          // -1 表示尚未建索引
    val bytes: Long,
    val modified: Long
)

/** 一本书的随机读取句柄。只持有行偏移数组，不持有整个文件。 */
class TtsBookReader internal constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    val id: String,
    private val offsets: IntArray
) {
    val lineCount: Int get() = offsets.size

    // 复用一个描述符 / 句柄，短行很多时避免每行都重新打开。
    private var inputStream: FileInputStream? = null
    private var pfd: ParcelFileDescriptor? = null

    /**
     * 读取指定物理行（0 基）；超出行数返回空串。单行超过 MAX_LINE_BYTES 会被截断。
     * 优先用文件描述符 seek（快）；个别 provider 的 fd 不支持 seek 时退回顺序跳过。
     */
    @Synchronized
    fun textAt(line: Int): String {
        if (line < 0 || line >= offsets.size) return ""
        val off = offsets[line].toLong()
        val out = ByteArrayOutputStream(512)
        val buf = ByteArray(1024)
        try {
            // FileInputStream(FileDescriptor) + FileChannel.position() 实现按字节偏移 seek；
            // Android stub 里的 RandomAccessFile 没有 (FileDescriptor, String) 构造函数。
            val fis: FileInputStream = inputStream ?: run {
                val d = resolver.openFileDescriptor(uri, "r") ?: throw IOException("无法打开所选文件")
                pfd = d
                FileInputStream(d.fileDescriptor).also { inputStream = it }
            }
            fis.channel.position(off)
            fillLine({ b, o, l -> fis.read(b, o, l) }, out, buf)
        } catch (t: Throwable) {
            closeHandle()
            // 退回顺序读取（内容 provider 的 fd 可能是不可 seek 的管道）。
            out.reset()
            try {
                val input = resolver.openInputStream(uri)
                    ?: return "（无法读取所选文件，可能已移动或权限被撤销）"
                input.use { s ->
                    var remaining = off
                    val skip = ByteArray(8192)
                    while (remaining > 0) {
                        val n = s.read(skip, 0, minOf(remaining.toInt(), skip.size))
                        if (n <= 0) return "（文件内容已变化，请重新添加）"
                        remaining -= n
                    }
                    fillLine({ b, o, l -> s.read(b, o, l) }, out, buf)
                }
            } catch (e: Throwable) {
                return "（读取该行失败：${e.message}）"
            }
        }
        var s = String(out.toByteArray(), Charsets.UTF_8)
        if (line == 0 && s.startsWith("\uFEFF")) s = s.substring(1)
        if (s.endsWith("\r")) s = s.substring(0, s.length - 1)
        return s
    }

    /** 从当前位置读到换行 / EOF / 上限为止。 */
    private inline fun fillLine(
        read: (ByteArray, Int, Int) -> Int,
        out: ByteArrayOutputStream,
        buf: ByteArray
    ) {
        while (out.size() < TtsBooks.MAX_LINE_BYTES) {
            val n = read(buf, 0, buf.size)
            if (n <= 0) break
            var nl = -1
            for (i in 0 until n) {
                if (buf[i] == 0x0A.toByte()) { nl = i; break }
            }
            val take = if (nl >= 0) nl else n
            if (take > 0) out.write(buf, 0, minOf(take, TtsBooks.MAX_LINE_BYTES - out.size()))
            if (nl >= 0) break
        }
    }

    @Synchronized
    internal fun close() = closeHandle()

    private fun closeHandle() {
        try { inputStream?.close() } catch (_: Throwable) {}
        inputStream = null
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null
    }
}

object TtsBooks {
    const val EXT = ".tts"
    internal const val MAX_LINE_BYTES = 256 * 1024

    private const val MAGIC = 0x54545332      // "TTS2"
    private const val VERSION = 2
    private const val MAX_INDEXED_LINES = 20_000_000

    private const val LIB_PREFS = "bd_audiobook_lib"
    private const val LIB_KEY = "books"
    private const val MIGRATED = "migrated_from_private_copies"
    private const val SEP = '\u001f'

    /** 只有行偏移索引落在 App 私有目录，书本身仍在用户选中的原位置。 */
    private fun indexDir(c: Context): File =
        File(c.applicationContext.filesDir, "audiobook/.idx").apply { mkdirs() }

    private fun indexFile(c: Context, id: String): File =
        File(indexDir(c), "${md5(id) ?: id.hashCode().toUInt().toString(16)}.idx")

    private fun md5(s: String): String? = try {
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        null
    }

    // ---------------------------------------------------------------- 书架登记

    private fun lib(c: Context) =
        c.applicationContext.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    /** 书架条目存在轻量偏好里：id 就是 URI 字符串，name/bytes/modified 是添加时记下的元信息。 */
    private fun readRegistry(c: Context): MutableMap<String, TtsBook> = synchronized(this) {
        val pref = lib(c)
        val out = LinkedHashMap<String, TtsBook>()
        (pref.getStringSet(LIB_KEY, emptySet()) ?: emptySet()).forEach { raw ->
            val p = raw.split(SEP, limit = 4)
            if (p.size == 4 && p[0].isNotBlank()) {
                out[p[0]] = TtsBook(
                    id = p[0], name = p[1], lines = -1,
                    bytes = p[2].toLongOrNull() ?: -1,
                    modified = p[3].toLongOrNull() ?: -1
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
                        id = id, name = f.nameWithoutExtension, lines = -1,
                        bytes = f.length(), modified = f.lastModified()
                    )
                    changed = true
                }
            }
            if (changed) writeRegistry(c, out)
        }
        out
    }

    private fun writeRegistry(c: Context, books: Map<String, TtsBook>) {
        val set = books.values.map { "${it.id}$SEP${it.name}$SEP${it.bytes}$SEP${it.modified}" }.toSet()
        lib(c).edit().putStringSet(LIB_KEY, set).apply()
    }

    fun get(c: Context, id: String): TtsBook? = readRegistry(c)[id]

    /**
     * 书架列表：只为每本书读索引头拿行数，不扫描正文。
     * 索引缺失 / 失效时会在调用线程重建——调用方必须自己放到 IO 线程。
     */
    fun list(c: Context): List<TtsBook> =
        readRegistry(c).values.map { b ->
            val lines = try { open(c, b.id)?.lineCount ?: -1 } catch (_: Throwable) { -1 }
            b.copy(lines = lines)
        }.sortedByDescending { it.modified }

    /** 最近 3 本的偏移数组常驻内存；其余只留磁盘索引。 */
    private val cache = object : LinkedHashMap<String, TtsBookReader>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TtsBookReader>?): Boolean {
            val tooMany = size > 3
            if (tooMany) eldest?.value?.close()
            return tooMany
        }
    }

    /** 打开（必要时建索引）。同步方法，仅做内存操作 + 文件流，调用方应在 IO 线程。 */
    @Synchronized
    fun open(c: Context, id: String): TtsBookReader? {
        cache[id]?.let { return it }
        var book = readRegistry(c)[id] ?: return null
        val uri = try { Uri.parse(id) } catch (_: Throwable) { return null }
        val idx = indexFile(c, id)
        var offsets = loadIndex(idx, book.bytes, book.modified)
        if (offsets == null) {
            val built = buildIndex(c, uri, idx, book.modified)
            offsets = built.offsets
            if (built.bytes != book.bytes) {
                // 文件在外部被改动：更新登记，下次按新大小校验索引。
                val reg = readRegistry(c)
                reg[id] = book.copy(bytes = built.bytes)
                writeRegistry(c, reg)
                book = reg[id]!!
            }
        }
        val reader = TtsBookReader(c.applicationContext.contentResolver, uri, id, offsets)
        cache[id] = reader
        return reader
    }

    private fun loadIndex(idx: File, bytes: Long, modified: Long): IntArray? = try {
        if (!idx.isFile) null
        else DataInputStream(FileInputStream(idx)).use { d ->
            if (d.readInt() != MAGIC || d.readInt() != VERSION) null
            else if (d.readLong() != bytes || d.readLong() != modified) null
            else {
                val n = d.readInt()
                if (n < 0 || n > MAX_INDEXED_LINES) null
                else IntArray(n) { d.readInt() }
            }
        }
    } catch (_: Throwable) {
        null
    }

    private class BuiltIndex(val offsets: IntArray, val bytes: Long)

    /**
     * 顺序扫描 URI 建行偏移索引；同时得到真实字节数（不依赖 OpenableColumns，
     * 各 provider 的 SIZE 不一定可靠）。索引仍只含偏移，不含正文。
     */
    private fun buildIndex(c: Context, uri: Uri, idx: File, modified: Long): BuiltIndex {
        val input = c.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取所选文件")
        val sink = IntArraySink()
        sink.add(0)
        var pos = 0L
        input.use { stream ->
            val buf = ByteArray(64 * 1024)
            // 换行后的新行起点先暂存，确认后面确实还有字节才建行；
            // 这样末尾换行不会产生额外空行，且不依赖 available()（其值未必可靠）。
            var pending: Int? = null
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (buf[i] == 0x0A.toByte()) {
                        pending?.let { sink.add(it) }
                        pending = (pos + i + 1).toInt()
                    } else {
                        pending?.let { sink.add(it); pending = null }
                    }
                }
                pos += n
            }
        }
        if (pos > Int.MAX_VALUE)
            throw IllegalStateException("听书文件超过 2 GB，暂不支持")
        // 空文件不算任何可朗读行，与旧实现一致。
        if (pos == 0L) return BuiltIndex(IntArray(0), 0L)

        val result = sink.copy()
        val tmp = File(idx.path + ".tmp")
        try {
            DataOutputStream(FileOutputStream(tmp)).use { d ->
                d.writeInt(MAGIC)
                d.writeInt(VERSION)
                d.writeLong(pos)
                d.writeLong(modified)
                d.writeInt(result.size)
                for (v in result) d.writeInt(v)
            }
            if (!tmp.renameTo(idx)) {
                idx.delete()
                tmp.renameTo(idx)
            }
        } catch (_: Throwable) {
            // 索引只是加速手段，写失败不影响本次播放。
            tmp.delete()
        }
        return BuiltIndex(result, pos)
    }

    private class IntArraySink {
        private var data = IntArray(1024)
        var size = 0
            private set

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun copy(): IntArray = data.copyOf(size)
    }

    /**
     * 添加一个 SAF 文件到书架：**不复制文件**，只登记 URI 并申请持久读权限。
     * URI 本身就是 id；同一 URI 重复添加只会返回已有条目。
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
        reg[id]?.let { existing ->
            val r = try { open(c, id) } catch (_: Throwable) { null }
            return existing.copy(lines = r?.lineCount ?: existing.lines)
        }

        val raw = (hintName?.substringAfterLast('/') ?: "")
            .takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "听书"
        val name = raw.substringBeforeLast('.', raw)
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n$SEP]"), "_")
            .trim()
            .ifBlank { "听书" }
            .take(80)

        val modified = System.currentTimeMillis()
        val built = buildIndex(c, uri, indexFile(c, id), modified)
        val book = TtsBook(
            id = id, name = name,
            lines = built.offsets.size, bytes = built.bytes, modified = modified
        )
        reg[id] = book
        writeRegistry(c, reg)
        return book
    }

    /** 从书架移除（不会删除用户原文件），释放持久权限并清掉索引与缓存。 */
    @Synchronized
    fun delete(c: Context, id: String): Boolean {
        cache.remove(id)?.close()
        indexFile(c, id).delete()
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
