package com.bd.tts.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.bd.tts.SettingsActivity
import com.bd.tts.engine.EngineController
import com.bd.tts.engine.Host2Daemon
import com.bd.tts.rules.PronunciationEditor
import com.bd.tts.rules.RuleFiles
import com.bd.tts.rules.RuleManager
import com.bd.tts.rules.RuleUpdateService
import com.bd.tts.shizuku.ShizukuClient
import com.bd.tts.sound.Knobs
import com.bd.tts.sound.SoundProfiles
import com.bd.tts.sound.StereoWiden
import com.bd.tts.source.Provisioner
import com.bd.tts.source.ResourceRoot
import com.bd.tts.util.Diagnostics
import com.bd.tts.util.Io
import com.bd.tts.util.Prefs
import java.io.File
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Compose UI 到既有 Java 逻辑层的桥：所有耗时操作都放工作线程，回调切主线程。 */
object Actions {
    private const val TAG = "BdActions"
    private val main = Handler(Looper.getMainLooper())
    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
    // 复用线程而不是每次点击都 new Thread；空闲线程会自动回收。
    private val ioPool = Executors.newCachedThreadPool { r ->
        Thread(r, "midu-ui-bg").apply { isDaemon = true }
    }
    private fun bg(block: () -> Unit) { ioPool.execute(block) }

    // 试听取消用序号而不是布尔：点「停止」后可以立刻再点「播放」，
    // 旧的合成任务不会把新任务的状态误清掉。
    private val playSeq = AtomicLong(0)
    private val cancelSeq = AtomicLong(0)
    private fun cancelled(seq: Long) = cancelSeq.get() >= seq

    fun smoke(c: Context, done: (String) -> Unit) = bg {
        val msg = EngineController.get(c).smoke()
        ui { done(msg) }
    }

    fun play(c: Context, text: String, done: (String) -> Unit) = bg {
        val seq = playSeq.incrementAndGet()
        val result = try {
            val pcm = EngineController.get(c)
                .synthesize(text, SoundProfiles.active(c), 100, 100)
            if (cancelled(seq)) "已取消：${pcm.size} B PCM 已合成，未播放"
            else {
                // 与系统 TTS / Legado HTTP 出口一致：按当前预设做本地立体声展宽，
                // 试听才能反映真实朗读效果。
                val widener = StereoWiden(
                    SoundProfiles.active(c).intOf(Knobs.K_STEREO_WIDTH), 24000)
                playPcm(pcm, seq, widener)
                if (cancelled(seq)) "播放已停止"
                else "播放完成：${pcm.size} B PCM（${pcm.size / 2 / 24000.0} s）"
            }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError / LinkageError 都是 Error 不是 Exception；
            // 只 catch Exception 会让后台线程静默死掉，界面上就是「点了没反应」。
            Log.e(TAG, "合成/播放失败", t)
            "播放失败：${t.javaClass.simpleName}：${t.message}"
        }
        ui { done(result) }
    }

    /** 中断当前试听。合成阶段点只会跳过随后的播放：host2 是独立进程，没法中途掐掉。 */
    fun stopPlay() {
        cancelSeq.set(playSeq.get())
        // 不在这里 pause/flush AudioTrack：WRITE_BLOCKING 的 write 可能被暂停永久卡住。
        // 分块写最多再等一个 chunk（约 170 ms）就会看到取消标记自行退出。
    }

    private fun playPcm(pcm: ByteArray, seq: Long, widener: StereoWiden) {
        val stereo = widener.active()
        val data = if (stereo) widener.widen(pcm, pcm.size) else pcm
        val channels = if (stereo) AudioFormat.CHANNEL_OUT_STEREO
                       else AudioFormat.CHANNEL_OUT_MONO
        val frameBytes = if (stereo) 4 else 2
        val min = AudioTrack.getMinBufferSize(24000, channels,
            AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000)
                .setChannelMask(channels).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, 8192)).build()
        try {
            track.play()
            // WRITE_BLOCKING 的 write 不保证一次写满；长音频不循环写会丢掉尾音。
            var off = 0
            while (off < data.size && !cancelled(seq) && !Thread.currentThread().isInterrupted) {
                val n = track.write(data, off, minOf(8192, data.size - off),
                    AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                off += n
            }
            // 数据写完≠播完，缓冲里还有余量；直接 stop 会吃掉最后一小截。
            val totalFrames = data.size / frameBytes
            val bytesPerMs = 24L * frameBytes          // 24000 采样/s × 每帧字节 ÷ 1000
            val deadline = SystemClock.elapsedRealtime() + data.size / bytesPerMs + 5000L
            while (!cancelled(seq) && track.playbackHeadPosition < totalFrames
                    && SystemClock.elapsedRealtime() < deadline) {
                try { Thread.sleep(40) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                }
            }
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
    }

    fun applyRules(c: Context): String = try {
        val r = RuleManager.apply(c)
        if (r.changed) {
            RuleManager.generationAfterChange(c, true)
            EngineController.get(c).invalidate()
            // host2 常驻引擎按代数重启重载规则：空闲时后台预热（下次朗读零等待）；
            // 朗读中 prewarm 会等 host2Lock，重启自然落在句界，当前句完整播完。
            EngineController.get(c).prewarmHost2()
        }
        r.summary()
    } catch (e: Exception) { "合并失败：${e.message}" }

    // ------------------------------------------------------- 规则文件（多份、可排序）

    data class RuleFileView(
        val id: String, val name: String, val spec: String,
        val network: Boolean, val enabled: Boolean,
        val lines: Int, val bytes: Long
    )

    /** 规则页列表用：同步读偏好 + 读各文件正文，量很小。 */
    fun listRuleFiles(c: Context): List<RuleFileView> = RuleFiles.list(c).map { s ->
        val text = RuleFiles.text(c, s)
        RuleFileView(s.id, s.name, s.spec, s.network, s.enabled,
            PronunciationEditor.countRules(text), text.toByteArray(Charsets.UTF_8).size.toLong())
    }

    fun ruleFileText(c: Context, id: String): String = RuleFiles.textById(c, id)

    fun displayName(c: Context, uri: Uri): String = RuleFiles.displayName(c, uri)

    /** 总开关：短路全部规则，但不动各文件自己的 enabled。 */
    fun setRulesEnabled(c: Context, on: Boolean): String {
        val p = Prefs.get(c)
        if (p.rulesEnabled() == on) return "规则总开关已是${if (on) "启用" else "禁用"}状态"
        p.rulesEnabled(on)
        return applyRules(c)
    }

    fun setRulesEnabled(c: Context, on: Boolean, done: (String) -> Unit) = bg {
        val msg = setRulesEnabled(c, on)
        ui { done(msg) }
    }

    fun addLocalRuleFile(c: Context, uri: Uri, name: String, done: (String) -> Unit) = bg {
        val msg = try {
            val s = RuleFiles.addLocal(c, uri, name)
            "已添加「${s.name}」· ${applyRules(c)}"
        } catch (e: Exception) { "添加失败：${e.message}" }
        ui { done(msg) }
    }

    fun addNetworkRuleFile(c: Context, url: String, done: (String) -> Unit) = bg {
        val msg = try {
            val s = RuleFiles.addNetwork(c, url)
            "已下载并添加「${s.name}」· ${applyRules(c)}"
        } catch (e: Exception) { "添加网络规则失败：${e.message}" }
        ui { done(msg) }
    }

    fun removeRuleFile(c: Context, id: String, done: (String) -> Unit) = bg {
        val msg = try {
            RuleFiles.remove(c, id)
            "已移除规则文件 · ${applyRules(c)}"
        } catch (e: Exception) { "移除失败：${e.message}" }
        ui { done(msg) }
    }

    fun moveRuleFile(c: Context, from: Int, to: Int, done: (String) -> Unit) = bg {
        val msg = try {
            RuleFiles.move(c, from, to)
            if (from == to) "位置未变" else "顺序已保存 · ${applyRules(c)}"
        } catch (e: Exception) { "排序失败：${e.message}" }
        ui { done(msg) }
    }

    fun setRuleFileEnabled(c: Context, id: String, on: Boolean, done: (String) -> Unit) = bg {
        val msg = try {
            RuleFiles.setEnabled(c, id, on)
            "已${if (on) "启用" else "禁用"}该文件 · ${applyRules(c)}"
        } catch (e: Exception) { "切换失败：${e.message}" }
        ui { done(msg) }
    }

    /** 网络文件重新下载；本地文件是 no-op。 */
    fun refreshRuleFile(c: Context, id: String, done: (String) -> Unit) = bg {
        val msg = try {
            val s = RuleFiles.find(c, id) ?: throw IllegalStateException("文件已不存在")
            if (!s.network) "本地文件无需下载"
            else {
                val n = RuleFiles.download(c, s).toByteArray(Charsets.UTF_8).size
                "已更新网络规则（$n B）· ${applyRules(c)}"
            }
        } catch (e: Exception) { "更新失败：${e.message}" }
        ui { done(msg) }
    }

    /** 外部编辑器保存后重新读取并合并；没变化也会重算一次，保证顺序/总开关一致。 */
    fun reloadRuleFile(c: Context, id: String, done: (String) -> Unit) = bg {
        val msg = try {
            if (RuleFiles.find(c, id) == null) throw IllegalStateException("文件已不存在")
            "已读取编辑结果 · ${applyRules(c)}"
        } catch (e: Exception) { "读取失败：${e.message}" }
        ui { done(msg) }
    }

    fun checkUpdate(c: Context, done: (String) -> Unit) {
        RuleUpdateService.checkNowAsync(c) { msg ->
            EngineController.get(c).invalidate()
            EngineController.get(c).prewarmHost2()   // 上游真变了才有效果；没变时是空操作
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
            "JNI 守护" to ec.inprocessGuard(),
            "host2 常驻" to if (!p.host2DaemonEnabled()) "已关闭"
                else "启用 · 127.0.0.1:${p.host2DaemonPort()}",
            "语音源" to Provisioner.describeSource(c),
            "音色" to p.voice(),
            "Shizuku" to ShizukuClient.describe(),
            "当前预设" to SoundProfiles.active(c).name,
            "规则代数" to p.generation().toString(),
            "上次检查" to if (last == 0L) "从未" else Date(last).toString()
        )
    }

    fun listVoices(c: Context, done: (List<String>, String) -> Unit) = bg {
        val (list, err) = try {
            val s = ResourceRoot.resolve(c, Prefs.get(c).voice())
            val v = s.listVoices().toList()
            s.close()
            v to ""
        } catch (e: Exception) { emptyList<String>() to (e.message ?: "未知错误") }
        ui { done(list, err) }
    }

    fun probeSource(c: Context, done: (String) -> Unit) = bg {
        val msg = try {
            val s = ResourceRoot.resolve(c, Prefs.get(c).voice())
            val st = s.fingerprint("zh-cn/ptl.dat")
            s.close()
            if (st.startsWith("-1:")) "读不到 zh-cn/ptl.dat（检查路径 / Shizuku 权限）"
            else "读到 zh-cn/ptl.dat：$st"
        } catch (e: Exception) { "失败：${e.message}" }
        ui { done(msg) }
    }

    fun requestShizuku(a: Activity) = ShizukuClient.requestPermission(a)

    fun invalidate(c: Context) = EngineController.get(c).invalidate()

    /** 改了声音预设 / 换了 host2_serve 二进制后，让常驻引擎下次重新拉起、重读选项。 */
    fun restartDaemon(c: Context, done: (String) -> Unit) = bg {
        val msg = try {
            Host2Daemon.stop(c)
            "已停止常驻引擎，下次合成重新加载（首次仍需几十秒）"
        } catch (t: Throwable) { "重启失败：${t.message}" }
        ui { done(msg) }
    }

    /** 诊断用：解除「上次 native 崩溃」的禁用状态，重新允许 AUTO 尝试进程内 JNI。 */
    fun resetInprocessGuard(c: Context) = EngineController.get(c).resetInprocessGuard()

    /**
     * 打开系统「文字转语音」设置页（切换默认引擎 / 试听）。
     * AOSP 的 android.settings.TTS_SETTINGS 在 MIUI / HyperOS 上没有对应 Activity，
     * 只发它会静默失败、按钮像没反应。注意 com.android.settings.TTS_SETTINGS 虽然
     * resolveActivity 能命中，外部直接启动会立刻 finish 回桌面，不能当兜底。
     * 降级到无障碍设置（里面有「文字转语音输出」）与设置主页，各 ROM 都能打开。
     */
    fun openSystemTts(c: Context) {
        for (action in listOf(
            "android.settings.TTS_SETTINGS",
            "android.settings.ACCESSIBILITY_SETTINGS",
            "android.settings.SETTINGS"
        )) {
            try {
                val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(c.packageManager) == null) continue
                c.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

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

    // ------------------------------------------------------- 词语读音（拼音）调整

    data class RuleStatus(
        val lines: Int, val bytes: Long, val mtime: Long,
        val generation: Int, val mergedLines: Int
    )

    data class ApplyResult(
        val ok: Boolean, val message: String,
        val added: Int, val duplicates: Int, val status: RuleStatus
    )

    /** 状态条：目标规则文件的真实行数 / 字节 / 修改时间 + 规则代数 + 合并结果条数。 */
    fun ruleStatus(c: Context, targetId: String? = null): RuleStatus = try {
        val text = if (targetId == null) RuleManager.userText(c) else RuleFiles.textById(c, targetId)
        val f = if (targetId == null) RuleManager.userFile(c) else RuleFiles.fileById(c, targetId)
        val merged = try { RuleManager.mergedText(c) } catch (_: Exception) { "" }
        RuleStatus(
            PronunciationEditor.countRules(text),
            if (f != null && f.isFile) f.length() else 0L,
            if (f != null && f.isFile) f.lastModified() else 0L,
            Prefs.get(c).generation(),
            PronunciationEditor.countRules(merged)
        )
    } catch (_: Exception) { RuleStatus(0, 0L, 0L, Prefs.get(c).generation(), 0) }

    /**
     * 「应用」= 待应用规则追加进 user_rules.txt（整行去重）→ 合并三份规则 →
     * 原子写进引擎资源根 → 规则代数 +1 → 重建引擎。耗时操作放工作线程，回调切主线程。
     * 对齐工具页 app.js 的 applyPending()：即使内容区为空也重新整理一次。
     */
    fun appendPendingRules(c: Context, pending: List<String>, done: (ApplyResult) -> Unit,
                           targetId: String? = null) = bg {
        val result = try {
            val before = if (targetId == null) RuleManager.userText(c)
                         else RuleFiles.textById(c, targetId)
            val r = PronunciationEditor.append(before, pending)
            if (r.added.isNotEmpty()) {
                if (targetId == null) {
                    RuleManager.setUserText(c, r.text)
                } else {
                    val src = RuleFiles.find(c, targetId)
                        ?: throw IllegalStateException("目标规则文件已不存在")
                    RuleFiles.writeText(c, src, r.text)
                }
            }
            val merge = applyRules(c)
            val st = ruleStatus(c, targetId)
            ApplyResult(true,
                "已应用：新增 ${r.added.size} 条，重复跳过 ${r.duplicates.size} 条 · " +
                        "目标文件 ${st.lines} 行 / ${st.bytes} B · 合并 ${st.mergedLines} 条 · " +
                        "规则代数 ${st.generation} · $merge",
                r.added.size, r.duplicates.size, st)
        } catch (e: Exception) {
            ApplyResult(false, "应用失败：${e.message}", 0, 0, ruleStatus(c, targetId))
        }
        ui { done(result) }
    }

    /** 查找已有读音：用户规则优先，其次合并结果（含上游 / vendor）。 */
    fun findRules(c: Context, word: String,
                  done: (List<PronunciationEditor.Hit>) -> Unit,
                  targetId: String? = null) = bg {
        val hits = try {
            val targetText = if (targetId == null) RuleManager.userText(c)
                             else RuleFiles.textById(c, targetId)
            PronunciationEditor.findHits(
                targetText,
                try { RuleManager.mergedText(c) } catch (_: Exception) { "" },
                word)
        } catch (_: Exception) { emptyList() }
        ui { done(hits) }
    }
}
