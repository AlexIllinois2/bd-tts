package com.bd.tts.legado

import android.content.Context
import com.bd.tts.engine.EngineController
import com.bd.tts.engine.PcmSink
import com.bd.tts.sound.Knobs
import com.bd.tts.sound.SoundProfile
import com.bd.tts.sound.SoundProfiles
import com.bd.tts.sound.StereoWiden
import com.bd.tts.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 *   GET|POST /tts          合成，整段返回带长度的 audio/wav（兼容优先）
 *   GET|POST /tts/stream   流式合成，chunked + 长度未知 WAV 头，边合成边发
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
    // stop() 会 shutdownNow()，所以线程池必须可重建；否则「停止 → 再启动」之后
    // 每个请求都会以 RejectedExecutionException 失败，且界面上看不出原因。
    private var pool = newPool()
    private fun newPool() = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "midu-http-worker").apply { isDaemon = true }
    }

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
        if (pool.isShutdown || pool.isTerminated) pool = newPool()
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
                val resp: Resp? = when {
                    method == "OPTIONS" -> Resp(204, "text/plain", ByteArray(0))
                    path == "/tts/stream" && (method == "GET" || method == "POST") -> {
                        // 流式路由自己写 chunked 响应，不再经 writeResponse。
                        try {
                            streamTts(s.getOutputStream(), query, headers["content-type"] ?: "", body)
                        } catch (e: Exception) {
                            onEvent("× /tts/stream 失败：${e.message}")
                        }
                        null
                    }
                    else -> try {
                        dispatch(method, path, query, headers, body)
                    } catch (e: Exception) {
                        onEvent("× $path 失败：${e.message}")
                        Resp(500, "text/plain; charset=utf-8",
                            ("BD TTS 合成失败：" + (e.message ?: "未知错误")).toByteArray(Charsets.UTF_8))
                    }
                }
                if (resp != null) writeResponse(s.getOutputStream(), resp)
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
            "/tts/stream" -> text(405, "text/plain", "Method Not Allowed")
            else -> text(404, "text/plain; charset=utf-8", "404 · 试试 /health /knobs /profiles /legado.json /tts /tts/stream")
        }
    }

    private data class SynthPlan(
        val text: String, val profile: SoundProfile,
        val rate: Int, val pitch: Int, val note: String
    )

    /** /tts 与 /tts/stream 共用同一套参数映射；返回 null = 缺 text。 */
    private fun prepareSynth(query: String, contentType: String, body: String): SynthPlan? {
        val args = LinkedHashMap<String, String>()
        args.putAll(parseQuery(query))
        args.putAll(parseBody(contentType, body))

        val text = (args["text"] ?: args["speakText"] ?: "").trim()
        if (text.isEmpty()) return null

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
                if (e.engineEmotion.isNotEmpty()) {
                    profile.put("tts_emotion", e.engineEmotion)
                }
            }
        }

        // 3) 任意 tts_* / voiceconv_* 覆盖：调用方给的参数优先生效
        val overridden = ArrayList<String>()
        for ((k, v) in args) {
            if (!Knobs.isOverridable(k)) continue
            profile.put(k, v)
            overridden.add(k)
        }
        return SynthPlan(text, profile, rate, pitchPct,
            "${text.length} 字 · 预设=${profile.name} · rate=$rate% · 覆盖=${overridden.size} 项")
    }

    /** 兼容优先：整段合成后一次性返回带正确长度的 WAV（Legado 默认用这条）。 */
    private fun synthesize(query: String, contentType: String, body: String): Resp {
        val plan = prepareSynth(query, contentType, body)
            ?: return text(400, "text/plain; charset=utf-8", "缺少 text / speakText 参数")
        onEvent("合成 ${plan.note}")

        val widener = StereoWiden(plan.profile.intOf(Knobs.K_STEREO_WIDTH), 24000)
        val pcmOut = ByteArrayOutputStream()
        for (part in splitText(plan.text)) {
            val pcm = EngineController.get(app).synthesize(part, plan.profile, plan.rate, plan.pitch)
            pcmOut.write(pcm)
        }
        // 本地立体声展宽（Haas 反相侧链）：单声道下混兼容，只是把 WAV 头改成双声道。
        val mono = pcmOut.toByteArray()
        val pcm = if (widener.active()) widener.widen(mono, mono.size) else mono
        val wav = WavEncoder.wrap(pcm, channels = if (widener.active()) 2 else 1)
        LegadoState.markSynth(wav.size)
        return Resp(200, "audio/wav", wav)
    }

    /**
     * 实验性流式路由：HTTP chunked + 长度未知的 WAV 头。
     * 每来一帧 PCM 就写一个 chunk 并 flush，客户端可以边下边播。
     * 注意：部分客户端会先缓存完整响应或要求 Content-Length，此时请用 /tts。
     */
    private fun streamTts(out: OutputStream, query: String, contentType: String, body: String) {
        val plan = prepareSynth(query, contentType, body)
        if (plan == null) {
            writeResponse(out, text(400, "text/plain; charset=utf-8", "缺少 text / speakText 参数"))
            return
        }
        onEvent("流式合成 ${plan.note}")
        val widener = StereoWiden(plan.profile.intOf(Knobs.K_STEREO_WIDTH), 24000)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: audio/wav\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        writeChunk(out, wavStreamHeader(if (widener.active()) 2 else 1))
        var total = 0
        val sink = object : PcmSink {
            override fun onPcm(buf: ByteArray, len: Int) {
                total += len
                if (widener.active()) {
                    val s = widener.widen(buf, len)   // 展宽状态跨块/跨句连续
                    writeChunk(out, s, s.size)
                } else {
                    writeChunk(out, buf, len)
                }
                out.flush()
            }
        }
        val ec = EngineController.get(app)
        for (part in splitText(plan.text)) {
            ec.synthesizeStream(part, plan.profile, plan.rate, plan.pitch, sink)
        }
        out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        LegadoState.markSynth(total + 44)
    }

    private fun writeChunk(out: OutputStream, buf: ByteArray, len: Int = buf.size) {
        if (len <= 0) return
        out.write(Integer.toHexString(len).toByteArray(Charsets.US_ASCII))
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.write(buf, 0, len)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    /** RIFF / data 长度填 0x7fffffff：流式 WAV 的常见写法，播放器读到 EOF 为止。 */
    private fun wavStreamHeader(channels: Int = 1): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(0x7fffffff)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16); b.putShort(1); b.putShort(channels.toShort())   // PCM / 声道数
        b.putInt(24000); b.putInt(24000 * channels * 2)               // 采样率 / 字节率
        b.putShort((channels * 2).toShort()); b.putShort(16)          // 块对齐 / 位深
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(0x7fffffff)
        return b.array()
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
        <html><head><meta charset="utf-8"><title>BD TTS · Legado 接口</title></head>
        <body style="font-family:system-ui;max-width:720px;margin:40px auto;line-height:1.7">
        <h2>BD 离线 TTS · 本地朗读服务</h2>
        <p>本服务只在本机监听，给阅读类 App（Legado）当 HttpTTS 用。</p>
        <ul>
          <li><a href="/health">/health</a> 引擎状态</li>
          <li><a href="/knobs">/knobs</a> 全部可调发音能力</li>
          <li><a href="/profiles">/profiles</a> 声音预设</li>
          <li><a href="/legado.json">/legado.json</a> 可直接导入 Legado 的 HttpTTS 配置</li>
          <li><code>/tts?text=你好</code> 整段返回 audio/wav（兼容性最好）</li>
          <li><code>/tts/stream?text=你好</code> chunked 流式，边合成边发（实验性）</li>
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

    private fun readLine(ins: InputStream, max: Int = 16 * 1024): String? {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) return sb.toString()
            if (b == 13) continue
            if (sb.length >= max) throw IllegalArgumentException("请求行/请求头超过 $max 字节")
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
