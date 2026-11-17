package com.bd.tts.rules

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bd.tts.util.Io
import com.bd.tts.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用户管理的规则文件列表：本地文件 / 网络文件，顺序即优先级（越靠上越高）。
 *
 * 列表本身存 Prefs(JSON)，正文存在 files/rules/sources/<id>.txt；
 * id=user_rules 是老版本用户规则的入口，正文仍落 RuleManager.userFile，
 * 这样经典设置页和历史规则都能继续用。网络文件把正文缓存到本地，离线也能读。
 */
object RuleFiles {
    const val LEGACY_ID = "user_rules"

    data class Source(
        val id: String,
        val name: String,
        val spec: String,
        val network: Boolean,
        val enabled: Boolean
    )

    private fun dir(c: Context): File = File(c.filesDir, "rules/sources")

    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    @JvmStatic
    fun list(c: Context): List<Source> {
        val raw = Prefs.get(c).ruleSources()
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                val out = ArrayList<Source>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(Source(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        spec = o.getString("spec"),
                        network = o.optBoolean("network", false),
                        enabled = o.optBoolean("enabled", true)
                    ))
                }
                return out
            } catch (_: Exception) { /* 落下面的迁移/重建 */ }
        }
        // 首次启动：把旧 user_rules.txt 变成列表第一条，老规则不丢。
        val seed = listOf(Source(LEGACY_ID, "用户规则（旧版）", "user_rules.txt", false, true))
        save(c, seed)
        return seed
    }

    @JvmStatic
    fun save(c: Context, items: List<Source>) {
        val arr = JSONArray()
        for (s in items) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("name", s.name)
            o.put("spec", s.spec)
            o.put("network", s.network)
            o.put("enabled", s.enabled)
            arr.put(o)
        }
        Prefs.get(c).ruleSources(arr.toString())
    }

    @JvmStatic
    fun find(c: Context, id: String): Source? = list(c).firstOrNull { it.id == id }

    @JvmStatic
    fun file(c: Context, s: Source): File =
        if (s.id == LEGACY_ID) RuleManager.userFile(c) else File(dir(c), s.id + ".txt")

    @JvmStatic
    fun fileById(c: Context, id: String): File? = find(c, id)?.let { file(c, it) }

    @JvmStatic
    fun text(c: Context, s: Source): String = try {
        val f = file(c, s)
        if (f.isFile) Io.readUtf8(f) else ""
    } catch (_: Exception) { "" }

    @JvmStatic
    fun textById(c: Context, id: String): String = find(c, id)?.let { text(c, it) } ?: ""

    @JvmStatic
    fun writeText(c: Context, s: Source, text: String) {
        if (s.id == LEGACY_ID) {
            RuleManager.setUserText(c, text)
            return
        }
        Io.ensureDir(dir(c))
        Io.atomicWrite(file(c, s), text)
    }

    @JvmStatic
    fun addLocal(c: Context, uri: Uri, name: String): Source {
        val bytes = uri.scheme?.let { c.contentResolver.openInputStream(uri)?.use { Io.readAll(it) } }
            ?: throw IllegalStateException("读不到所选文件")
        val src = Source(newId(), name.ifBlank { "本地规则" }, uri.toString(), false, true)
        Io.ensureDir(dir(c))
        Io.atomicWrite(file(c, src), bytes)
        save(c, listOf(src) + list(c))
        return src
    }

    @JvmStatic
    fun addNetwork(c: Context, url: String): Source {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://"))
            throw IllegalStateException("网络规则地址要以 http:// 或 https:// 开头")
        val name = try {
            val parsed = URL(u)
            val tail = parsed.path.substringAfterLast('/')
            if (tail.isNotBlank()) tail else parsed.host
        } catch (_: Exception) { u.take(40) }
        val src = Source(newId(), name, u, true, true)
        download(c, src)              // 先下载成功再入库，避免加进一条空规则
        save(c, listOf(src) + list(c))
        return src
    }

    /** 重新下载网络规则正文到本地缓存；本地文件是 no-op。 */
    @JvmStatic
    fun download(c: Context, s: Source): String {
        if (!s.network) return text(c, s)
        val conn = URL(s.spec).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        try {
            val code = conn.responseCode
            if (code < 200 || code >= 300) throw IllegalStateException("HTTP $code")
            val bytes = conn.inputStream.use { Io.readAll(it) }
            if (bytes.size > 4 * 1024 * 1024) throw IllegalStateException("规则文件超过 4 MB")
            val txt = String(bytes, Charsets.UTF_8)
            Io.ensureDir(dir(c))
            Io.atomicWrite(file(c, s), txt)
            return txt
        } finally {
            conn.disconnect()
        }
    }

    @JvmStatic
    fun remove(c: Context, id: String) {
        val cur = list(c).toMutableList()
        if (cur.removeAll { it.id == id }) save(c, cur)
    }

    /** 拖动排序：from → to，越靠上优先级越高。 */
    @JvmStatic
    fun move(c: Context, from: Int, to: Int) {
        val cur = list(c).toMutableList()
        if (from !in cur.indices || to !in cur.indices || from == to) return
        cur.add(to, cur.removeAt(from))
        save(c, cur)
    }

    @JvmStatic
    fun setEnabled(c: Context, id: String, enabled: Boolean) {
        save(c, list(c).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    @JvmStatic
    fun displayName(c: Context, uri: Uri): String {
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cur ->
                    if (cur.moveToFirst()) {
                        val n = cur.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "本地规则"
    }
}
