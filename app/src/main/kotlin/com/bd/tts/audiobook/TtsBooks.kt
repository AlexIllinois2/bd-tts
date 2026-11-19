package com.bd.tts.audiobook

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 听书文件库。
 *
 * .tts 是本 App 自定义格式：UTF-8 纯文本，一行 = 一次朗读单元。
 * 行号按物理行从 1 开始（空行 / # 开头的行也算行号，但不朗读），
 * 这样「跳转到第 N 行」和用户用文本编辑器看到的行号一致。
 *
 * 性能约束：文件可能有上百 MB，因此绝不把整本读进内存。
 * 导入 / 打开时为每一行记录字节偏移（索引落在私有目录的 .idx/ 里，
 * 用文件大小 + 修改时间校验，文件变了自动重建），播放与显示时
 * RandomAccessFile.seek() 到偏移只读取当前行。
 */
data class TtsBook(
    val id: String,          // 磁盘上的文件名，例如 "三体.tts"
    val name: String,        // 不含扩展名
    val lines: Int,          // -1 表示尚未建索引
    val bytes: Long,
    val modified: Long
)

/** 一本书的随机读取句柄。只持有行偏移数组，不持有整个文件。 */
class TtsBookReader internal constructor(
    private val file: File,
    val id: String,
    private val offsets: IntArray
) {
    val lineCount: Int get() = offsets.size

    // 复用一个句柄，短行很多时避免每行都 open/close 一次文件。
    private var raf: RandomAccessFile? = null

    /** 读取指定物理行（0 基）；超出行数返回空串。单行超过 MAX_LINE_BYTES 会被截断。 */
    @Synchronized
    fun textAt(line: Int): String {
        if (line < 0 || line >= offsets.size) return ""
        val out = ByteArrayOutputStream(512)
        val buf = ByteArray(1024)
        try {
            val r = raf ?: RandomAccessFile(file, "r").also { raf = it }
            r.seek(offsets[line].toLong())
            while (out.size() < TtsBooks.MAX_LINE_BYTES) {
                val n = r.read(buf)
                if (n <= 0) break
                var nl = -1
                for (i in 0 until n) {
                    if (buf[i] == 0x0A.toByte()) { nl = i; break }
                }
                val take = if (nl >= 0) nl else n
                if (take > 0) out.write(buf, 0, minOf(take, TtsBooks.MAX_LINE_BYTES - out.size()))
                if (nl >= 0) break
            }
        } catch (t: Throwable) {
            raf = null
            return "（读取该行失败：${t.message}）"
        }
        var s = String(out.toByteArray(), Charsets.UTF_8)
        if (line == 0 && s.startsWith("\uFEFF")) s = s.substring(1)
        if (s.endsWith("\r")) s = s.substring(0, s.length - 1)
        return s
    }

    @Synchronized
    internal fun close() {
        try { raf?.close() } catch (_: Throwable) {}
        raf = null
    }
}

object TtsBooks {
    const val EXT = ".tts"
    internal const val MAX_LINE_BYTES = 256 * 1024

    private const val MAGIC = 0x54545332      // "TTS2"
    private const val VERSION = 1
    private const val MAX_INDEXED_LINES = 20_000_000

    /** 导入的副本放在 App 私有目录：不需要 READ_EXTERNAL_STORAGE，URI 失效也不影响。 */
    fun dir(c: Context): File =
        File(c.applicationContext.filesDir, "audiobook").apply { mkdirs() }

    private fun indexDir(c: Context): File = File(dir(c), ".idx").apply { mkdirs() }

    private fun indexFile(c: Context, id: String): File = File(indexDir(c), "$id.idx")

    /** id 只允许是本库里的纯文件名，挡住 ../ 之类的路径穿越。 */
    fun file(c: Context, id: String): File? {
        if (id.isBlank() || id.contains('/') || id.contains('\\')) return null
        if (!id.endsWith(EXT, true)) return null
        val f = File(dir(c), id)
        return if (f.isFile) f else null
    }

    /**
     * 书架列表：只为每本书读几十字节的索引头拿行数，不扫描正文。
     * 索引缺失 / 失效时会在调用线程重建——调用方必须自己放到 IO 线程。
     */
    fun list(c: Context): List<TtsBook> =
        (dir(c).listFiles { f -> f.isFile && f.name.endsWith(EXT, true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { f ->
                TtsBook(
                    id = f.name,
                    name = f.nameWithoutExtension,
                    lines = try { open(c, f.name)?.lineCount ?: -1 } catch (_: Throwable) { -1 },
                    bytes = f.length(),
                    modified = f.lastModified()
                )
            }

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
        val f = file(c, id) ?: return null
        val idx = indexFile(c, id)
        val offsets = loadIndex(f, idx) ?: buildIndex(f, idx)
        val reader = TtsBookReader(f, id, offsets)
        cache[id] = reader
        return reader
    }

    private fun loadIndex(f: File, idx: File): IntArray? = try {
        if (!idx.isFile) null
        else DataInputStream(FileInputStream(idx)).use { d ->
            if (d.readInt() != MAGIC || d.readInt() != VERSION) null
            else if (d.readLong() != f.length() || d.readLong() != f.lastModified()) null
            else {
                val n = d.readInt()
                if (n < 0 || n > MAX_INDEXED_LINES) null
                else IntArray(n) { d.readInt() }
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun buildIndex(f: File, idx: File): IntArray {
        val len = f.length()
        if (len > Int.MAX_VALUE)
            throw IllegalStateException("听书文件超过 2 GB，暂不支持")
        if (len == 0L) return IntArray(0)

        val sink = IntArraySink()
        sink.add(0)
        FileInputStream(f).use { input ->
            val buf = ByteArray(64 * 1024)
            var pos = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (buf[i] == 0x0A.toByte()) {
                        val next = pos + i + 1
                        // 末尾换行不额外产生空行，与旧 countLines 口径一致。
                        if (next < len.toInt()) sink.add(next)
                    }
                }
                pos += n
            }
        }
        val result = sink.copy()
        val tmp = File(idx.path + ".tmp")
        try {
            DataOutputStream(FileOutputStream(tmp)).use { d ->
                d.writeInt(MAGIC)
                d.writeInt(VERSION)
                d.writeLong(f.length())
                d.writeLong(f.lastModified())
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
        return result
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
     * 从 SAF URI 复制一份进书库。文件名冲突时自动加 -2 / -3，
     * 扩展名统一规范成 .tts（选到 .txt 也能直接听）。
     */
    fun import(c: Context, uri: Uri, hintName: String?): TtsBook {
        val raw = (hintName?.substringAfterLast('/') ?: "")
            .takeIf { it.isNotBlank() } ?: "听书"
        val base = raw.substringBeforeLast('.', raw)
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .trim()
            .ifBlank { "听书" }
            .take(80)
        var target = File(dir(c), base + EXT)
        var i = 2
        while (target.exists()) {
            target = File(dir(c), "$base-$i$EXT")
            i++
        }
        val input = c.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取所选文件")
        input.use { src ->
            FileOutputStream(target).use { dst -> src.copyTo(dst, 64 * 1024) }
        }
        val lines = try { open(c, target.name)?.lineCount ?: 0 } catch (_: Throwable) { 0 }
        return TtsBook(
            id = target.name,
            name = target.nameWithoutExtension,
            lines = lines,
            bytes = target.length(),
            modified = target.lastModified()
        )
    }

    @Synchronized
    fun delete(c: Context, id: String): Boolean {
        cache.remove(id)?.close()
        indexFile(c, id).delete()
        return file(c, id)?.delete() ?: false
    }
}
