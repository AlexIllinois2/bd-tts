package com.bd.tts.engine;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.bd.tts.IRemoteFileService;
import com.bd.tts.rules.RuleManager;
import com.bd.tts.shizuku.ShizukuClient;
import com.bd.tts.source.Provisioner;
import com.bd.tts.source.ResourceRoot;
import com.bd.tts.util.Io;
import com.bd.tts.util.Prefs;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * host2 兼容后端的统一入口。两条路：
 *   1) 常驻 daemon（rig/host2_serve --serve）：模型只加载一次，走 loopback socket，
 *      PCM 逐块回传，可流式消费；
 *   2) 一次性 exec（rig/run_dev.sh 或直接 host2 本体 + LD_PRELOAD）：原来的路，
 *      每次合成起一个进程。
 *
 * 1) 是可选增强：rig 里没推 host2_serve、Shizuku 没授权、daemon 起不来或中途失败，
 * 都会自动退回 2)，已经能出声的配置不会被破坏。
 *
 * 参数覆盖分两档：常驻 daemon 支持协议 v2 后，每句把完整扩展选项快照下发给
 * 复用的引擎句柄；一次性 exec 和旧版 daemon 仍只认 TTS_SPEED / TTS_SIL /
 * TTS_SUBLEN / TTS_SPEED_RATIO 四个环境变量，其余 tts_* 会被忽略并打日志。
 */
public final class Host2ShellBackend {
    private static final String TAG = "BdHost2Backend";
    private static volatile int sLastRuleHash = 0;
    private Host2ShellBackend() {}

    /** 旧调用点兼容。 */
    public static byte[] synth(Context c, String text, String voice) throws Exception {
        return synth(c, text, voice, 10, null);
    }

    public static byte[] synth(Context c, String text, String voice,
                               int speed, List<EngineOption> options) throws Exception {
        checkText(text);
        // 纯标点 / 空白片段不进引擎：离线引擎会报 illegal text 且不给结束标记，
        // 常驻 daemon 会空等到超时并堵住后续请求。补短静音让它正常收尾。
        if (!Speakable.hasReadable(text)) return Speakable.shortSilence();
        ensureResources(c, voice);
        if (Host2Daemon.enabled(c)) {
            try {
                syncRules(c);
                byte[] pcm = Host2Daemon.synth(c, text, voice, speed, options);
                Log.i(TAG, "常驻后端返回 " + pcm.length + " B PCM");
                return pcm;
            } catch (Exception e) {
                Log.w(TAG, "常驻后端不可用，回退一次性 host2：" + e.getMessage());
            }
        }
        return synthOnce(c, text, voice, speed, options);
    }

    /**
     * 流式入口：daemon 每来一帧就回调一次；不可用时退化为一次性合成后回调整段。
     * 若 daemon 已经吐出过 PCM 才失败，就不回退（否则会把前半段重复播一遍），直接抛。
     */
    public static long stream(Context c, String text, String voice, int speed,
                              List<EngineOption> options, PcmSink sink) throws Exception {
        checkText(text);
        if (!Speakable.hasReadable(text)) {
            byte[] silence = Speakable.shortSilence();
            sink.onPcm(silence, silence.length);
            return silence.length;
        }
        ensureResources(c, voice);
        if (Host2Daemon.enabled(c)) {
            final long[] emitted = {0L};
            PcmSink wrapped = (buf, len) -> { emitted[0] += len; sink.onPcm(buf, len); };
            try {
                syncRules(c);
                return Host2Daemon.stream(c, text, voice, speed, options, wrapped);
            } catch (java.util.concurrent.CancellationException ce) {
                // 调用方主动停止（系统 TTS stop / Legado 调速）：直接上抛，散栈会
                // 关闭 socket，daemon 的断开探测随即放弃本句。既不能包装成失败、
                // 也不能回退一次性合成（否则被放弃的半段又完整合成一遍）。
                throw ce;
            } catch (Exception e) {
                if (emitted[0] > 0) throw new IllegalStateException(
                        "常驻后端中途失败（已输出 " + emitted[0] + " B PCM）：" + e.getMessage(), e);
                Log.w(TAG, "常驻流式不可用，回退一次性 host2：" + e.getMessage());
            }
        }
        byte[] pcm = synthOnce(c, text, voice, speed, options);
        sink.onPcm(pcm, pcm.length);
        return pcm.length;
    }

    private static void checkText(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("合成文本为空");
        // host2 靠环境变量传文本，单条 env 有内核上限（MAX_ARG_STRLEN）。
        // 常驻协议放宽到 64 KB，这里按两者中更保守的一次性路径统一限制。
        if (text.getBytes(StandardCharsets.UTF_8).length > 32 * 1024)
            throw new IllegalStateException("文本超过 32 KB，请分段合成（host2 用环境变量传文本）");
    }

    /**
     * 尽力而为的资源供给：从用户目录解压/镜像语音包与 so 到 rig（见 Provisioner）。
     * 失败不阻断——rig 里手工放好的老配置继续可用；缺什么会在合成错误里体现。
     */
    private static void ensureResources(Context c, String voice) {
        try {
            Provisioner.ensure(c, voice);
        } catch (Exception e) {
            Log.w(TAG, "资源供给未完成（按现有 rig 内容继续）：" + e.getMessage());
        }
    }

    /** 规则落到 rig 的资源根：一次性后端和常驻 daemon 都在那里读 unified_rule.txt。 */
    private static void syncRules(Context c) throws Exception {
        String merged = RuleManager.mergedText(c);
        int hash = merged.hashCode();
        if (hash != 0 && hash == sLastRuleHash) return;   // 逐句合成别反复写同一个文件
        Prefs p = Prefs.get(c);
        String rig = p.host2Rig().replaceAll("/+$", "");
        IRemoteFileService svc = ShizukuClient.acquire(c, 8000);
        if (svc == null) throw new IllegalStateException("Shizuku 不可用：" + ShizukuClient.error());
        if (merged.isEmpty()) { RuleManager.apply(c); merged = RuleManager.mergedText(c); }
        svc.writeFile(rig + "/midu/" + ResourceRoot.RULE_REL,
                merged.getBytes(StandardCharsets.UTF_8));
        sLastRuleHash = merged.hashCode();
    }

    /** 一次性 exec：rig 里有 run_dev.sh 就走脚本，没有就直接执行 host2 本体并注入 shim。 */
    private static byte[] synthOnce(Context c, String text, String voice,
                                    int speed, List<EngineOption> options) throws Exception {
        syncRules(c);
        Prefs p = Prefs.get(c);
        String rig = p.host2Rig().replaceAll("/+$", "");
        IRemoteFileService svc = ShizukuClient.acquire(c, 8000);
        if (svc == null) throw new IllegalStateException("Shizuku 不可用：" + ShizukuClient.error());

        String tag = "midu_" + android.os.Process.myPid() + "_" + System.nanoTime();
        String out = rig + "/" + tag + ".pcm";
        String log = rig + "/" + tag + ".log";
        List<String> envList = new ArrayList<>(Arrays.asList(
                "RIG=" + rig,
                "BIN=" + rig + "/host2",
                "LIB=" + rig + "/lib",
                "ROOT=" + rig + "/midu",
                "VOICE=" + voice,
                "TEXT=" + text,
                "OUT=" + out,
                "LOG=" + log,
                "APPEND=0",
                "WAITSEC=60",
                "TTS_SPEED=" + speed
        ));
        if (options != null) {
            for (EngineOption o : options) {
                switch (o.key) {
                    case "tts_silence_duration":     envList.add("TTS_SIL=" + o.value); break;
                    case "tts_sub_sentence_length":  envList.add("TTS_SUBLEN=" + o.value); break;
                    case "tts_speed_ratio":          envList.add("TTS_SPEED_RATIO=" + o.value); break;
                    case "tts_pitch":
                    case "tts_volume":
                        // 一次性 host2 只认 4 个 env 档位，没有这两个通道；
                        // 它们由常驻 daemon v2 的 O 快照消费，这里静默跳过即可。
                        break;
                    default:
                        Log.d(TAG, "一次性 host2 不支持选项 " + o.key
                                + "，已忽略（常驻 daemon v2 / 进程内 JNI 可下发）");
                        break;
                }
            }
        }
        // 注意：只有「直接 exec」才能把 LD_PRELOAD 放进环境；经 sh 会把 sh 自己也顶崩。
        File script = new File(rig, "run_dev.sh");
        String[] cmd;
        if (script.isFile()) {
            cmd = new String[]{"sh", script.getAbsolutePath()};
        } else {
            cmd = new String[]{rig + "/host2",
                    "--root", rig + "/midu", "--voice", voice, "--base", ResourceRoot.BASE,
                    "--text", text, "--out", out, "--synth"};
            envList.add("LD_LIBRARY_PATH=" + rig + "/lib");
            envList.add("LD_PRELOAD=log_shim.so:icu_shim.so:cronet_shim4.so");
            Log.d(TAG, "rig 缺 run_dev.sh，直接执行 host2（LD_PRELOAD 由 ProcessBuilder 注入）");
        }
        String[] env = envList.toArray(new String[0]);

        try {
            int rc;
            try {
                rc = svc.exec(cmd, env, rig, log);
            } catch (Exception e) {
                throw new IllegalStateException("host2 启动失败（rig=" + rig
                        + "；Shizuku 是否授权、host2 是否可执行？）：" + e.getMessage()
                        + tailLog(svc, log), e);
            }
            if (rc != 0) throw new IllegalStateException(
                    (rc == 124 ? "host2 执行超时（已强杀，检查 rig 脚本/资源是否卡住）"
                            : "host2 rc=" + rc)
                    + (script.isFile() ? "（run_dev.sh）" : "（直接执行 host2）")
                    + tailLog(svc, log));

            byte[] pcm;
            ParcelFileDescriptor pfd = svc.openFile(out);
            if (pfd == null) throw new IllegalStateException(
                    "host2 没产出 PCM：" + out + tailLog(svc, log));
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                pcm = Io.readAll(in);
            }
            if (pcm.length < 64) throw new IllegalStateException(
                    "host2 只产出 " + pcm.length + " B PCM" + tailLog(svc, log));
            return pcm;
        } finally {
            // 成功失败都清掉本次 tag 产物。日志内容已经随异常带回 app 侧。
            try { svc.exec(new String[]{"rm", "-f", out, log}, null, rig, "/dev/null"); }
            catch (Exception ignored) {}
        }
    }

    /** 把 host2 的 stdout/stderr 尾部塞进异常信息，失败时不用再 adb 进去翻文件。 */
    private static String tailLog(IRemoteFileService svc, String log) {
        try {
            ParcelFileDescriptor pfd = svc.openFile(log);
            if (pfd == null) return "（日志 " + log + " 不存在）";
            byte[] all;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                all = Io.readAll(in);
            }
            String s = new String(all, StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return "（日志为空：" + log + "）";
            if (s.length() > 1200) s = "…" + s.substring(s.length() - 1200);
            return "，日志尾部：\n" + s;
        } catch (Exception e) {
            return "（读日志失败：" + e.getMessage() + "，路径 " + log + "）";
        }
    }
}
