package com.midu.tts.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.midu.tts.SettingsActivity
import com.midu.tts.engine.EngineController
import com.midu.tts.rules.RuleManager
import com.midu.tts.rules.RuleUpdateService
import com.midu.tts.shizuku.ShizukuClient
import com.midu.tts.sound.SoundProfiles
import com.midu.tts.source.ResourceRoot
import com.midu.tts.util.Diagnostics
import com.midu.tts.util.Io
import com.midu.tts.util.Prefs
import java.io.File
import java.util.Date

/** Compose UI 到既有 Java 逻辑层的桥：所有耗时操作都放工作线程，回调切主线程。 */
object Actions {
    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
    private fun bg(block: () -> Unit) = Thread(block, "midu-ui-bg").apply { isDaemon = true; start() }

    fun smoke(c: Context, done: (String) -> Unit) = bg {
        val msg = EngineController.get(c).smoke()
        ui { done(msg) }
    }

    fun play(c: Context, text: String, done: (String) -> Unit) = bg {
        val result = try {
            val pcm = EngineController.get(c)
                .synthesize(text, SoundProfiles.active(c), 100, 100)
            playPcm(pcm)
            "播放完成：${pcm.size} B PCM（${pcm.size / 2 / 24000.0} s）"
        } catch (e: Exception) { "播放失败：${e.message}" }
        ui { done(result) }
    }

    private fun playPcm(pcm: ByteArray) {
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, 8192)).build()
        track.play()
        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        track.stop()
        track.release()
    }

    fun applyRules(c: Context): String = try {
        val r = RuleManager.apply(c)
        if (r.changed) {
            RuleManager.generationAfterChange(c, true)
            EngineController.get(c).invalidate()
        }
        r.summary()
    } catch (e: Exception) { "合并失败：${e.message}" }

    fun checkUpdate(c: Context, done: (String) -> Unit) {
        RuleUpdateService.checkNowAsync(c) { msg ->
            EngineController.get(c).invalidate()
            done(msg)
        }
    }

    fun userRules(c: Context): String = try { RuleManager.userText(c) }
        catch (e: Exception) { "# 读取失败：${e.message}" }

    fun saveUserRules(c: Context, text: String): String = try {
        RuleManager.setUserText(c, text)
        applyRules(c)
    } catch (e: Exception) { "保存失败：${e.message}" }

    fun mergedText(c: Context): String = try { RuleManager.mergedText(c) }
        catch (e: Exception) { "读取失败：${e.message}" }

    fun conflictReport(c: Context): String = try {
        Io.readUtf8(File(RuleManager.dir(c), "merge_report.txt"))
    } catch (e: Exception) { "读取失败：${e.message}" }

    fun rollback(c: Context): String = try {
        if (RuleManager.rollbackUpstream(c)) "已回滚到上一版 · ${applyRules(c)}" else "没有上一版"
    } catch (e: Exception) { "回滚失败：${e.message}" }

    fun diagnostics(c: Context): String = Diagnostics.report(c)

    fun statusLines(c: Context): List<Pair<String, String>> {
        val p = Prefs.get(c)
        val ec = EngineController.get(c)
        val last = p.lastCheck()
        return listOf(
            "引擎" to ec.status(),
            "后端" to ec.lastBackend().ifEmpty { "尚未合成" },
            "语音源" to p.source(),
            "音色" to p.voice(),
            "Shizuku" to ShizukuClient.describe(),
            "当前预设" to SoundProfiles.active(c).name,
            "规则代数" to p.generation().toString(),
            "上次检查" to if (last == 0L) "从未" else Date(last).toString()
        )
    }

    fun listVoices(c: Context, done: (List<String>, String) -> Unit) = bg {
        val (list, err) = try {
            val s = ResourceRoot.resolve(c)
            val v = s.listVoices().toList()
            s.close()
            v to ""
        } catch (e: Exception) { emptyList<String>() to (e.message ?: "未知错误") }
        ui { done(list, err) }
    }

    fun probeSource(c: Context, done: (String) -> Unit) = bg {
        val msg = try {
            val s = ResourceRoot.resolve(c)
            val st = s.fingerprint("zh-cn/ptl.dat")
            s.close()
            if (st.startsWith("-1:")) "读不到 zh-cn/ptl.dat（检查路径 / Shizuku 权限）"
            else "读到 zh-cn/ptl.dat：$st"
        } catch (e: Exception) { "失败：${e.message}" }
        ui { done(msg) }
    }

    fun requestShizuku(a: Activity) = ShizukuClient.requestPermission(a)

    fun invalidate(c: Context) = EngineController.get(c).invalidate()

    fun openSystemTts(c: Context) = try {
        c.startActivity(Intent("android.settings.TTS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}

    fun openLegacy(c: Context) = c.startActivity(Intent(c, SettingsActivity::class.java))

    fun copy(c: Context, label: String, text: String): Boolean = try {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    } catch (_: Exception) { false }

    fun share(c: Context, text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).setType("application/json")
                .putExtra(Intent.EXTRA_TEXT, text)
            c.startActivity(Intent.createChooser(send, "导出 HttpTTS JSON")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
