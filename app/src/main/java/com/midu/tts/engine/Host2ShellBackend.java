package com.midu.tts.engine;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.midu.tts.IRemoteFileService;
import com.midu.tts.rules.RuleManager;
import com.midu.tts.shizuku.ShizukuClient;
import com.midu.tts.source.ResourceRoot;
import com.midu.tts.util.Io;
import com.midu.tts.util.Prefs;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 兼容后端：不进 app 进程，用 Shizuku 以 shell/root 身份跑 rig 里的 run_dev.sh（host2）。
 * 这是与真机工装逐字同参的那条路：LD_PRELOAD 三枚 shim、OMP_NUM_THREADS=1。
 *
 * 参数覆盖能力有限：host2 二进制只读 TTS_SPEED / TTS_SIL / TTS_SUBLEN /
 * TTS_SPEED_RATIO 四个环境变量（见 src/host2.c:658-665），其余 tts_* 选项
 * 在这条后端上会被明确忽略并打日志——进程内 JNI 后端才支持任意选项。
 *
 * 前置：先把 host2rig/ 推到 /data/local/tmp/host2rig（见 scripts/mumu_debug.sh / push_rig.sh）。
 */
public final class Host2ShellBackend {
    private static final String TAG = "MiduHost2Backend";
    private Host2ShellBackend() {}

    /** 旧调用点兼容。 */
    public static byte[] synth(Context c, String text, String voice) throws Exception {
        return synth(c, text, voice, 10, null);
    }

    public static byte[] synth(Context c, String text, String voice,
                               int speed, List<EngineOption> options) throws Exception {
        Prefs p = Prefs.get(c);
        String rig = p.host2Rig().replaceAll("/+$", "");
        IRemoteFileService svc = ShizukuClient.acquire(c, 8000);
        if (svc == null) throw new IllegalStateException("Shizuku 不可用：" + ShizukuClient.error());

        // 规则必须落到 rig 的资源根：host2 的引擎在那里读 unified_rule.txt。
        String merged = RuleManager.mergedText(c);
        if (merged.isEmpty()) RuleManager.apply(c);
        String rulePath = rig + "/midu/" + ResourceRoot.RULE_REL;
        svc.writeFile(rulePath, RuleManager.mergedText(c).getBytes(StandardCharsets.UTF_8));

        String tag = "midu_" + android.os.Process.myPid() + "_" + System.nanoTime();
        String out = rig + "/" + tag + ".pcm";
        String log = rig + "/" + tag + ".log";
        String[] cmd = {"sh", rig + "/run_dev.sh"};
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
                    default:
                        Log.d(TAG, "host2 后端不支持选项 " + o.key + "，已忽略（用进程内 JNI 可下发）");
                        break;
                }
            }
        }
        String[] env = envList.toArray(new String[0]);

        int rc;
        try {
            rc = svc.exec(cmd, env, rig, log);
        } catch (Exception e) {
            throw new IllegalStateException("host2 启动失败（rig 推了吗？" + rig + "）：" + e.getMessage(), e);
        }
        if (rc != 0) throw new IllegalStateException("host2 rc=" + rc + "，日志在 " + log);

        byte[] pcm;
        ParcelFileDescriptor pfd = svc.openFile(out);
        if (pfd == null) throw new IllegalStateException("host2 没产出 PCM：" + out);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            pcm = Io.readAll(in);
        }
        // 清理自己的临时产物（只删本 app 生成的 tag 文件）。
        try { svc.exec(new String[]{"rm", "-f", out, log}, null, rig, "/dev/null"); }
        catch (Exception ignored) {}
        if (pcm.length < 64) throw new IllegalStateException("host2 PCM 太短：" + pcm.length + " B");
        return pcm;
    }
}
