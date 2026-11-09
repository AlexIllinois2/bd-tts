package com.midu.tts.legado

import android.content.Context
import com.midu.tts.sound.SoundProfiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * 生成可直接粘进 Legado「朗读引擎 → 网络朗读 → 导入」的 HttpTTS JSON。
 *
 * 对齐 Legado 源码事实（app/src/main/java/io/legado/app/data/entities/HttpTTS.kt）：
 *   - 字段：id / name / url / contentType / concurrentRate / synthesisThreadCount /
 *           lastUpdateTime / speakersJson / emotionsJson
 *   - url 是「URL 规则」：`baseUrl,{options JSON}`，options 支持 method / headers / body
 *   - 占位符：{{speakText}} {{speakSpeed}} {{currentToneID}} {{currentSpeakerName}}
 *             {{currentEmotionName}} {{currentEmotionTag}}（没有 speakPitch）
 *   - 响应必须是音频二进制流，contentType 用 audio/wav
 *
 * 发言人 = 本地声音预设（toneID 即 profile.id），情绪 = 韵律微调。
 * 这样 Legado 的多角色朗读 / 情绪标签就直接落到了我们暴露出来的发音参数上。
 */
object LegadoImport {
    /** 固定 id：重复导入会覆盖同一条，不会越导越多。 */
    const val IMPORT_ID = 20261109001L
    const val DEFAULT_HOST = "127.0.0.1"

    data class Emotion(
        val name: String,
        val tag: String,
        val speedDelta: Int = 0,
        val pitchDelta: Int = 0,
        val volume: Int? = null,
        /** 只有用户在预设里启用 tts_emotion 才会真正下发给引擎（静态证据项）。 */
        val engineEmotion: String = ""
    )

    val EMOTIONS = listOf(
        Emotion("平静", "[平静]"),
        Emotion("高兴", "[高兴]", +2, +2, engineEmotion = "happy"),
        Emotion("悲伤", "[悲伤]", -1, -1, engineEmotion = "sad"),
        Emotion("愤怒", "[愤怒]", +2, +1, engineEmotion = "angry"),
        Emotion("温柔", "[温柔]", -1, +1, engineEmotion = "gentle"),
        Emotion("紧张", "[紧张]", +3, +1),
        Emotion("庄重", "[庄重]", -2, -1, engineEmotion = "serious"),
        Emotion("耳语", "[耳语]", -1, -1, volume = 60, engineEmotion = "whisper")
    )

    fun findEmotion(token: String): Emotion? {
        if (token.isBlank()) return null
        val t = token.trim().removePrefix("[").removeSuffix("]").trim()
        return EMOTIONS.firstOrNull { it.name == t || it.tag == token.trim() || it.name == token.trim() }
    }

    /** 发言人列表：把每个声音预设变成一个可选 toneID。 */
    fun speakersJson(c: Context): String {
        val a = JSONArray()
        for (p in SoundProfiles.all(c)) {
            a.put(JSONObject().put("speakerName", p.name).put("toneID", p.id))
        }
        return a.toString()
    }

    fun emotionsJson(): String {
        val a = JSONArray()
        for (e in EMOTIONS) {
            a.put(JSONObject().put("emotionName", e.name).put("emotionTag", e.tag))
        }
        return a.toString()
    }

    /**
     * URL 规则用 form-urlencoded POST（而不是 JSON body）：
     * Legado 会把 speakText 原样替换进字符串，中文/引号/换行交给 java.encodeURI 编码，
     * 不会破坏 JSON。这与官方百度示例的写法一致。
     */
    fun urlRule(host: String, port: Int): String {
        val body = buildString {
            append("text={{java.encodeURI(speakText)}}")
            append("&speed={{speakSpeed}}")
            append("&tone={{java.encodeURI(currentToneID)}}")
            append("&speaker={{java.encodeURI(currentSpeakerName)}}")
            append("&emotion={{java.encodeURI(currentEmotionName)}}")
            append("&tag={{java.encodeURI(currentEmotionTag)}}")
        }
        val options = JSONObject()
            .put("method", "POST")
            .put("headers", JSONObject().put("Content-Type", "application/x-www-form-urlencoded"))
            .put("body", body)
        // org.json 默认把 / 转义成 \/；Legado 能解析，但为了人眼可读与粘贴方便还原回来。
        return "http://$host:$port/tts," + options.toString().replace("\\/", "/")
    }

    fun build(c: Context, host: String, port: Int): String {
        val item = JSONObject()
            .put("id", IMPORT_ID)
            .put("name", "Midu 离线 TTS（本机）")
            .put("url", urlRule(host, port))
            .put("contentType", "audio/wav")
            .put("concurrentRate", "0")
            .put("synthesisThreadCount", 1)
            .put("lastUpdateTime", System.currentTimeMillis())
            .put("speakersJson", speakersJson(c))
            .put("emotionsJson", emotionsJson())
            .put("header", "")
        return JSONArray().put(item).toString(2).replace("\\/", "/")
    }
}
