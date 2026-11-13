package com.bd.tts.engine;

import android.content.Context;
import android.util.Log;

import com.bd.tts.IRemoteFileService;
import com.bd.tts.rules.RuleManager;
import com.bd.tts.shizuku.ShizukuClient;
import com.bd.tts.sound.SoundProfiles;
import com.bd.tts.source.Provisioner;
import com.bd.tts.source.ResourceRoot;
import com.bd.tts.util.Prefs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * host2 常驻后端：rig 里的 host2_serve 以 --serve 起一次，模型只加载一次，
 * 之后走 127.0.0.1 TCP 发文本、逐块收 PCM。同一连接可连发多句。
 *
 * 协议见 rig/src/host2.c 顶部注释；P / A / S / Q 请求，D / E / X 响应。
 *
 * 关键设计：这是一条**可选增强**。rig 里没有 host2_serve、Shizuku 没授权、
 * 端口起不来或 daemon 合成失败时，Host2ShellBackend 都会退回原来的一次性 exec
 * （run_dev.sh / host2），已经能出声的那条路不会被破坏。
 *
 * 协议 v2：daemon 支持 V/O 命令后，每句的扩展选项以完整快照下发，逐条
 * setOption 到复用的引擎句柄；旧二进制不认 V，探测失败就按 v1 行为继续
 * （只吃启动时的 3 个 env 档位），不会把出声链路弄坏。
 */
public final class Host2Daemon {
    private static final String TAG = "BdHost2Daemon";
    private static final Object LOCK = new Object();
    private static volatile long sLastStartMs = 0L;
    /** null=未探测；true=协议 v2（支持每句 O 快照）；false=旧 daemon。 */
    private static volatile Boolean sOptionCapable = null;

    private Host2Daemon() {}

    public static boolean enabled(Context c) { return Prefs.get(c).host2DaemonEnabled(); }
    public static int port(Context c) { return Prefs.get(c).host2DaemonPort(); }
    public static String token(Context c) { return Prefs.get(c).host2DaemonToken(); }

    /** 端口能连上即认为 daemon 进程在（它在加载模型之前就 bind+listen 了）。 */
    public static boolean reachable(Context c) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(c)), 300);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** daemon 是否已完成初始化（能对 PING 回话）。诊断用，合成路径不必先等它。 */
    public static boolean ready(Context c) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(c)), 500);
            s.setSoTimeout(700);
            writeLine(s.getOutputStream(), "P");
            return "PONG".equals(readAsciiLine(s.getInputStream()));
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 协议能力协商：单独开一条连接发 V。必须在合成连接建立之前做——daemon 是
     * 单线程 accept，正在合成时探测连接只能排在 backlog 里，会误判成超时。
     * 结果缓存住，之后每句不再多一次往返；stop() 会清掉。
     */
    private static boolean supportsOptions(Context c) {
        Boolean cached = sOptionCapable;
        if (cached != null) return cached;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(c)), 2000);
            // 刚拉起的 daemon 要等引擎 init 完才 accept，第一次等久一点是应该的。
            s.setSoTimeout(45_000);
            writeLine(s.getOutputStream(), "V");
            String line = readAsciiLine(s.getInputStream());
            boolean ok = line != null && line.startsWith("VER 2");
            sOptionCapable = ok;
            Log.i(TAG, ok ? "daemon 协议 v2：每句下发完整扩展选项快照"
                    : "daemon 为旧协议：扩展选项仍只取启动时的 3 个 env 档位");
            return ok;
        } catch (Exception e) {
            // 不缓存失败：可能只是 daemon 正在忙 / 正在加载模型，下次再试。
            Log.w(TAG, "协议能力探测失败，本次按旧协议：" + e.getMessage());
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** 发一条完整选项快照（O <n> + n 行 type\tkey\tvalue）。调用方须已发 A 鉴权。 */
    private static void writeOptions(OutputStream out, List<EngineOption> options) throws Exception {
        List<EngineOption> list = options == null ? java.util.Collections.<EngineOption>emptyList() : options;
        int n = Math.min(list.size(), 64);   // 与 host2.c 的 SVO_MAX 对齐
        writeLine(out, "O " + n);
        for (int i = 0; i < n; i++) {
            EngineOption o = list.get(i);
            writeLine(out, o.type + "\t" + safeField(o.key, 63) + "\t" + safeField(o.value, 1023));
        }
    }

    /** 协议用 \t 分列、\n 分行；值里出现这些字符就替换掉，避免把下一条命令顶歪。 */
    private static String safeField(String s, int max) {
        if (s == null) return "";
        String v = s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** 让 daemon 优雅退出；下次 stream 会重新拉起（相当于重建引擎 / 重读选项）。 */
    public static void stop(Context c) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(c)), 500);
            s.setSoTimeout(700);
            writeLine(s.getOutputStream(), "Q");
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
        // Q 是异步退出：等端口真正释放，否则紧接着的合成会连上一个正在退出的 daemon。
        long deadline = System.currentTimeMillis() + 2000L;
        while (reachable(c) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
        Prefs.get(c).host2DaemonVoice("");
        sOptionCapable = null;   // daemon 换了，协议能力要重新协商
        synchronized (LOCK) { sLastStartMs = 0L; }
    }

    /**
     * 没在跑就通过 Shizuku 以 detach 方式拉起一次，并等端口出现。
     *
     * daemon 的 --voice 只在启动时读一次，运行中无法切换。所以这里把「启动时用的音色」
     * 记在 prefs 里，音色一变就先停再起，避免用户改了设置却一直听着旧音色。
     * daemon 是 detach 的独立进程，app 被杀后它仍在跑，标记必须持久化而不是放静态变量。
     */
    public static void ensureStarted(Context c, String voice) throws Exception {
        Prefs p = Prefs.get(c);
        String want = voice == null || voice.isEmpty() ? p.voice() : voice;
        if (reachable(c)) {
            String active = p.host2DaemonVoice();
            if (want.equals(active)) return;
            Log.i(TAG, "常驻引擎音色 " + (active.isEmpty() ? "未知（外部启动）" : active)
                    + " → " + want + "，重启以生效");
            stop(c);
            if (reachable(c))
                throw new IllegalStateException("旧常驻引擎未退出，无法切换音色 " + want
                        + "（请在诊断页点「重启常驻引擎」后重试）");
        }
        synchronized (LOCK) {
            if (reachable(c) && want.equals(p.host2DaemonVoice())) return;
            long now = System.currentTimeMillis();
            // 旧 host2 不认 --serve，会立刻退出；别让它被反复重启刷屏。
            if (now - sLastStartMs < 20_000L)
                throw new IllegalStateException("host2_serve 刚试过启动但没就绪，20 秒内不重试");
            sLastStartMs = now;
            startDetached(c, want);
            long deadline = System.currentTimeMillis() + 6000L;
            while (System.currentTimeMillis() < deadline) {
                if (reachable(c)) { p.host2DaemonVoice(want); return; }
                try { Thread.sleep(200); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            }
            throw new IllegalStateException("host2_serve 没监听 " + port(c)
                    + "（先看 rig/serve_boot.log；推二进制见 scripts/build_host2_serve.sh）");
        }
    }

    private static void startDetached(Context c, String voice) throws Exception {
        Prefs p = Prefs.get(c);
        String rig = p.host2Rig().replaceAll("/+$", "");
        String bin = rig + "/host2_serve";
        IRemoteFileService svc = ShizukuClient.acquire(c, 8000);
        if (svc == null) throw new IllegalStateException("Shizuku 不可用：" + ShizukuClient.error());

        String st = svc.stat(bin);
        if (st == null || st.startsWith("-1:"))
            throw new IllegalStateException("rig 里没有 " + bin
                    + "（跑 scripts/build_host2_serve.sh 后再 PUSH=1 推上去）");

        // 规则落到 rig：daemon 每次合成从磁盘读 unified_rule.txt，改规则立即生效。
        // 先尽力做资源供给（用户目录的 zip/APK → rig）；rig/midu 已有 licbag 的老
        // 配置在供给失败时仍允许拉起，缺什么会在合成错误里体现。
        try {
            Provisioner.ensure(c, voice);
        } catch (Exception e) {
            String lic = svc.stat(rig + "/midu/speech_license.licbag");
            if (lic.startsWith("-1:"))
                throw new IllegalStateException("rig 资源未就位（rig/midu 里没有 licbag）：" + e.getMessage(), e);
            Log.w(TAG, "资源供给失败，按现有 rig/midu 内容继续：" + e.getMessage());
        }
        String merged = RuleManager.mergedText(c);
        if (merged.isEmpty()) { RuleManager.apply(c); merged = RuleManager.mergedText(c); }
        svc.writeFile(rig + "/midu/" + ResourceRoot.RULE_REL,
                merged.getBytes(StandardCharsets.UTF_8));

        String tok = token(c);
        String[] cmd = { bin,
                "--root", rig + "/midu", "--voice", voice, "--base", ResourceRoot.BASE,
                "--serve", "--port", String.valueOf(port(c)), "--token", tok };
        List<String> env = new ArrayList<>(Arrays.asList(
                "LD_LIBRARY_PATH=" + rig + "/lib",
                "LD_PRELOAD=log_shim.so:icu_shim.so:cronet_shim4.so",
                "WAITSEC=120",
                "EXEC_DETACH=1"));
        // 启动 env 只作旧协议 daemon 的兜底（3 个档位）；v2 daemon 每句由 O 快照覆盖，
        // 且 O 在 synth_one 里排在这些 env 之后，调用方参数优先生效。
        for (EngineOption o : SoundProfiles.active(c).toOptions()) {
            switch (o.key) {
                case "tts_silence_duration":    env.add("TTS_SIL=" + o.value); break;
                case "tts_sub_sentence_length": env.add("TTS_SUBLEN=" + o.value); break;
                case "tts_speed_ratio":         env.add("TTS_SPEED_RATIO=" + o.value); break;
                default: break;
            }
        }
        String log = rig + "/serve_boot.log";
        int rc = svc.exec(cmd, env.toArray(new String[0]), rig, log);
        if (rc != 0) throw new IllegalStateException("拉起 host2_serve 失败 rc=" + rc + "，日志：" + log);
        Log.i(TAG, "已 detach 启动 host2_serve，日志 " + log);
    }

    /** 流式合成：每来一帧 PCM 就回调一次，调用方可以边收边播 / 边收边写 HTTP chunk。 */
    public static long stream(Context c, String text, String voice, int speed,
                              List<EngineOption> options, PcmSink sink) throws Exception {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("合成文本为空");
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (body.length > 65536) throw new IllegalStateException("单句超过 64 KB，请先分段");
        int nopt = options == null ? 0 : options.size();

        ensureStarted(c, voice);
        // 协商要在合成连接建立之前：daemon 单线程 accept，忙的时候探测只能排队。
        boolean v2 = supportsOptions(c);
        Log.d(TAG, v2 ? "本次下发 " + nopt + " 项选项快照（协议 v2，含基础 pitch/volume）"
                : "旧协议 daemon：" + nopt + " 项选项未下发，仅启动 env 生效");
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port(c)), 5000);
            // 首次请求可能要等 daemon 把模型加载完；C 侧单句最多等 20 s，
            // 这里留 60 s 余量，避免任何一句异常把朗读永久挂住。
            s.setSoTimeout(60_000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String tok = token(c);
            if (!tok.isEmpty()) writeLine(out, "A " + tok);
            // O 分支在 daemon 侧要求已鉴权，所以必须排在 A 之后、S 之前。
            if (v2) writeOptions(out, options);
            writeLine(out, "S " + speed + " " + body.length);
            out.write(body);
            out.flush();

            long total = 0L;
            byte[] frame = new byte[65536];
            while (true) {
                String line = readAsciiLine(in);
                if (line == null)
                    throw new IllegalStateException("daemon 提前断开（引擎崩了？看 serve_boot.log）");
                if (line.startsWith("D ")) {
                    int n = Integer.parseInt(line.substring(2).trim());
                    if (n <= 0 || n > frame.length)
                        throw new IllegalStateException("daemon PCM 帧长度异常：" + n);
                    readFully(in, frame, n);
                    sink.onPcm(frame, n);
                    total += n;
                } else if (line.startsWith("E ")) {
                    long declared = Long.parseLong(line.substring(2).trim());
                    if (total < 64) throw new IllegalStateException("daemon 只回 " + total + " B PCM");
                    if (declared != total) Log.w(TAG, "PCM 计数不一致：daemon=" + declared + " 收到=" + total);
                    return total;
                } else if (line.startsWith("X ")) {
                    int n = Integer.parseInt(line.substring(2).trim());
                    byte[] err = new byte[Math.max(0, Math.min(n, 4096))];
                    readFully(in, err, err.length);
                    throw new IllegalStateException("daemon 合成失败："
                            + new String(err, StandardCharsets.UTF_8));
                } else {
                    throw new IllegalStateException("daemon 未知响应：" + line);
                }
            }
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** 非流式调用点用：把帧攒成整段 PCM。 */
    public static byte[] synth(Context c, String text, String voice, int speed,
                               List<EngineOption> options) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        stream(c, text, voice, speed, options, (buf, len) -> bos.write(buf, 0, len));
        byte[] pcm = bos.toByteArray();
        if (pcm.length < 64) throw new IllegalStateException("daemon PCM 太短：" + pcm.length + " B");
        return pcm;
    }

    private static void writeLine(OutputStream out, String s) throws Exception {
        // 选项的 value 可能是中文（情感名 / 前端覆写串），用 UTF-8 而不是 ASCII。
        // UTF-8 多字节序列不含 0x0A，daemon 按 '\n' 切行不受影响。
        out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String readAsciiLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder(64);
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 256) throw new IllegalStateException("daemon 响应行超长");
        }
        return null;
    }

    private static void readFully(InputStream in, byte[] b, int n) throws Exception {
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IllegalStateException("daemon 数据流提前结束");
            off += r;
        }
    }
}
