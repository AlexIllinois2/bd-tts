package com.midu.tts.legado

import android.content.Context
import com.midu.tts.engine.EngineController
import com.midu.tts.sound.Knob
import com.midu.tts.sound.Knobs
import com.midu.tts.sound.SoundProfile
import com.midu.tts.sound.SoundProfiles
import com.midu.tts.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 极轻量的本地 HTTP/1.1 服务（不引第三方 HTTP 库，避免额外依赖与明文策略问题）。
 *
 * 路由：
 *   GET  /                 说明页
 *   GET  /health           健康 / 引擎状态
 *   GET  /knobs            全部可调能力（含证据等级）
 *   GET  /profiles         声音预设
 *   GET  /legado.json      可直接导入 Legado 的 HttpTTS JSON
 *   GET|POST /tts          合成，返回 audio/wav 二进制流
 *
 * /tts 参数（query 或 form-urlencoded / JSON body，body 优先）：
 *   text | speakText                  朗读文本（必填）
 *   speed | speakSpeed                Legado 档位 5..50（20=正常），>60 视为百分比
 *   pitch                            音高百分比，100=正常
 *   tone | toneId | profile          发言人 toneID（= 本地预设 id）
 *   emotion | tag                    情绪名或 [情绪标签]
 *   任意 tts_* / voiceconv_*          直接覆盖对应 Knob（受实验性开关约束）
 */
class LegadoHttpServer(
    private val app: Context,
    private val port: Int,
    private val bindAll: Boolean,
    private val onEvent: (String) -> Unit
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    private data class Resp(
        val code: Int,
        val type: String,
        val body: ByteArray,
        val allow: String = "GET, POST, OPTIONS"
    )

    fun start() {
        if (running) return
        val addr = if (bindAll) null else InetAddress.getByName("127.0.0.1")
        serverSocket = ServerSocket(port, 32, addr)
        running = true
        val shown = if (bindAll) "0.0.0.0" else "127.0.0.1"
        onEvent("监听 $shown:$port")
        acceptThread = Thread({ acceptLoop() }, "midu-http-accept").apply {
            isDaemon = true; start()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        pool.shutdownNow()
        onEvent("已停止")
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val s = serverSocket?.accept() ?: break
                s.soTimeout = 180_000
                pool.execute { handle(s) }
            } catch (e: Exception) {
                if (running) onEvent("accept 异常：${e.message}")
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val ins = s.getInputStream()
                val requestLine = readLine(ins) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.ROOT)
                val target = parts[1]
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(ins) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase(Locale.ROOT)] =
                        line.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1 shl 20) ?: 0
                val body = if (len > 0) {
                    val buf = ByteArray(len)
                    readFully(ins, buf)
                    String(buf, Charsets.UTF_8)
                } else ""

                val q = target.indexOf('?')
                val path = if (q < 0) target else target.substring(0, q)
                val query = if (q < 0) "" else target.substring(q + 1)

                LegadoState.markRequest("$method $path")
                val resp = if (method == "OPTIONS") Resp(204, "text/plain", ByteArray(0))
                else try {
                    dispatch(method, path, query, headers, body)
                } catch (e: Exception) {
                    onEvent("× $path 失败：${e.message}")
                    Resp(500, "text/plain; charset=utf-8",
                        ("Midu TTS 合成失败：" + (e.message ?: "未知错误")).toByteArray(Charsets.UTF_8))
                }
                writeResponse(s.getOutputStream(), resp)
            }
        } catch (e: Exception) {
            onEvent("请求异常：${e.message}")
        }
    }

    private fun dispatch(method: String, path: String, query: String,
                         headers: Map<String, String>, body: String): Resp {
        return when (path) {
            "/", "/index.html" -> text(200, "text/html; charset=utf-8", indexHtml())
            "/health" -> text(200, "application/json; charset=utf-8", healthJson().toString(2))
            "/knobs" -> text(200, "application/json; charset=utf-8", knobsJson().toString(2))
            "/profiles" -> text(200, "application/json; charset=utf-8", profilesJson().toString(2))
            "/legado.json", "/import" -> {
                val p = Prefs.get(app)
                text(200, "application/json; charset=utf-8",
                    LegadoImport.build(app, if (p.httpBindAll()) lanHint() else LegadoImport.DEFAULT_HOST,
                        p.httpPort()))
            }
            "/tts" -> {
                if (method != "GET" && method != "POST") return text(405, "text/plain", "Method Not Allowed")
                synthesize(query, headers["content-type"] ?: "", body)
            }
            else -> text(404, "text/plain; charset=utf-8", "404 · 试试 /health /knobs /profiles /legado.json /tts")
        }
    }

    private fun synthesize(query: String, contentType: String, body: String): Resp {
        val args = LinkedHashMap<String, String>()
        args.putAll(parseQuery(query))
        args.putAll(parseBody(contentType, body))

        val text = (args["text"] ?: args["speakText"] ?: "").trim()
        if (text.isEmpty()) return text(400, "text/plain; charset=utf-8",
            "缺少 text / speakText 参数")

        val p = Prefs.get(app)
        val rate = toRatePercent(args["speed"] ?: args["speakSpeed"])
        val pitchPct = (args["pitch"]?.toIntOrNull() ?: 100).coerceIn(20, 300)

        // 1) 发言人 → 本地预设
        val tone = args["tone"] ?: args["toneId"] ?: args["profile"] ?: ""
        val base = if (p.httpSpeakerEnabled() && tone.isNotBlank())
            SoundProfiles.byId(app, tone) else SoundProfiles.active(app)
        val profile: SoundProfile = base.copy()

        // 2) 情绪 → 韵律微调
        if (p.httpEmotionEnabled()) {
            val emoToken = args["emotion"] ?: args["tag"] ?: ""
            LegadoImport.findEmotion(emoToken)?.let { e ->
                profile.put(Knobs.K_SPEED,
                    (profile.intOf(Knobs.K_SPEED) + e.speedDelta).coerceIn(1, 20).toString())
                profile.put(Knobs.K_PITCH,
                    (profile.intOf(Knobs.K_PITCH) + e.pitchDelta).coerceIn(1, 20).toString())
                e.volume?.let { profile.put(Knobs.K_VOLUME, it.coerceIn(0, 100).toString()) }
                if (e.engineEmotion.isNotEmpty() && profile.isEnabled("tts_emotion")) {
                    profile.put("tts_emotion", e.engineEmotion)
                }
            }
        }

        // 3) 任意 tts_* / voiceconv_* 覆盖（实验性项受开关约束）
        val overridden = ArrayList<String>()
        for ((k, v) in args) {
            if (!Knobs.isOverridable(k)) continue
            val knob = Knobs.byKey(k) ?: continue
            if (knob.evidence == Knob.STATIC && !p.httpAllowExperimental()) continue
            profile.put(k, v)
            profile.setEnabled(k, true)
            overridden.add(k)
        }
        onEvent("合成 ${text.length} 字 · 预设=${profile.name} · rate=$rate% · 覆盖=${overridden.size} 项")

        val pcmOut = ByteArrayOutputStream()
        for (part in splitText(text)) {
            val pcm = EngineController.get(app).synthesize(part, profile, rate, pitchPct)
            pcmOut.write(pcm)
        }
        val wav = WavEncoder.wrap(pcmOut.toByteArray())
        LegadoState.markSynth(wav.size)
        return Resp(200, "audio/wav", wav)
    }

    // ------------------------------------------------------------------ JSON

    private fun healthJson(): JSONObject {
        // 把「当前预设实际会下发的选项」列出来，方便在不触发合成的情况下核对参数映射。
        val opts = JSONArray()
        for (o in SoundProfiles.active(app).toOptions()) opts.put(o.toString())
        return JSONObject()
            .put("ok", true)
            .put("service", "midu-tts-legado/1")
            .put("engine", EngineController.get(app).status())
            .put("backend", EngineController.get(app).lastBackend())
            .put("activeProfile", SoundProfiles.active(app).name)
            .put("knobs", Knobs.all().size)
            .put("profiles", SoundProfiles.all(app).size)
            .put("options", opts)
            .put("requests", LegadoState.requestCount)
            .put("synths", LegadoState.synthCount)
    }

    private fun knobsJson(): JSONArray {
        val a = JSONArray()
        for (k in Knobs.all()) {
            a.put(JSONObject()
                .put("key", k.key)
                .put("title", k.title)
                .put("group", k.group)
                .put("type", when (k.type) {
                    1 -> "int"; 2 -> "bool"; 3 -> "double"; else -> "string"
                })
                .put("default", k.def)
                .put("min", k.min).put("max", k.max)
                .put("evidence", k.evidenceLabel())
                .put("desc", k.desc))
        }
        return a
    }

    private fun profilesJson(): JSONArray {
        val a = JSONArray()
        for (p in SoundProfiles.all(app)) {
            a.put(JSONObject().put("id", p.id).put("name", p.name)
                .put("builtin", p.builtin).put("enabled", p.enabledCount())
                .put("describe", p.describe()))
        }
        return a
    }

    private fun lanHint(): String = LegadoImport.DEFAULT_HOST   // UI 上可自行改成设备 IP

    private fun indexHtml(): String = """
        <html><head><meta charset="utf-8"><title>Midu TTS · Legado 接口</title></head>
        <body style="font-family:system-ui;max-width:720px;margin:40px auto;line-height:1.7">
        <h2>Midu 离线 TTS · 本地朗读服务</h2>
        <p>本服务只在本机监听，给阅读类 App（Legado）当 HttpTTS 用。</p>
        <ul>
          <li><a href="/health">/health</a> 引擎状态</li>
          <li><a href="/knobs">/knobs</a> 全部可调发音能力</li>
          <li><a href="/profiles">/profiles</a> 声音预设</li>
          <li><a href="/legado.json">/legado.json</a> 可直接导入 Legado 的 HttpTTS 配置</li>
          <li><code>/tts?text=你好</code> 返回 audio/wav</li>
        </ul></body></html>
    """.trimIndent()

    // ---------------------------------------------------------------- helpers

    private fun text(code: Int, type: String, s: String) =
        Resp(code, type, s.toByteArray(Charsets.UTF_8))

    private fun toRatePercent(raw: String?): Int {
        val v = raw?.toFloatOrNull() ?: return 100
        // Legado speakSpeed 文档范围 5..50，20 视作 100%；>60 直接当百分比用。
        return if (v > 60f) v.roundToInt().coerceIn(20, 400)
        else (v / 20f * 100f).roundToInt().coerceIn(10, 300)
    }

    private fun splitText(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in text) {
            cur.append(ch)
            val brk = ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '；' ||
                    (cur.length >= 60 && (ch == '，' || ch == ','))
            if (brk || cur.length >= 120) {
                val s = cur.toString().trim()
                if (s.isNotEmpty()) out.add(s)
                cur.setLength(0)
            }
        }
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        if (out.isEmpty()) out.add(text)
        return out
    }

    private fun parseQuery(s: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        if (s.isEmpty()) return m
        for (seg in s.split('&')) {
            if (seg.isEmpty()) continue
            val i = seg.indexOf('=')
            val k = if (i < 0) seg else seg.substring(0, i)
            val v = if (i < 0) "" else seg.substring(i + 1)
            try {
                m[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {}
        }
        return m
    }

    private fun parseBody(contentType: String, body: String): Map<String, String> {
        if (body.isEmpty()) return emptyMap()
        if (contentType.contains("json", true)) {
            val m = LinkedHashMap<String, String>()
            try {
                val o = JSONObject(body)
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    m[k] = o.opt(k)?.toString() ?: ""
                }
            } catch (_: Exception) {}
            return m
        }
        return parseQuery(body.trim())
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) return sb.toString()
            if (b == 13) continue
            sb.append(b.toChar())
        }
    }

    private fun readFully(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
    }

    private fun writeResponse(out: OutputStream, r: Resp) {
        val reason = when (r.code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"
            404 -> "Not Found"; 405 -> "Method Not Allowed"
            500 -> "Internal Server Error"; else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(r.code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(r.type).append("\r\n")
            append("Content-Length: ").append(r.body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: ").append(r.allow).append("\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (r.body.isNotEmpty()) out.write(r.body)
        out.flush()
    }
}
