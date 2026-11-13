package com.bd.tts.engine;

import android.content.Context;

import com.bd.tts.rules.RuleManager;
import com.bd.tts.sound.SoundProfile;
import com.bd.tts.sound.SoundProfiles;
import com.bd.tts.source.Provisioner;
import com.bd.tts.source.ResourceRoot;
import com.bd.tts.util.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 引擎生命周期：规则代数 → 资源根 → 引擎实例 → 后端选择 / 自动降级。 */
public final class EngineController {
    private static volatile EngineController sInstance;

    private final Context app;
    private long handle = 0;
    private int appliedGeneration = -1;
    // 这几个状态会被首页运行状态读取。native 合成可能在同一把锁里卡几十秒，
    // getter 不能再抢锁，否则主页重组会被拖住甚至 ANR；用 volatile 保证可见性。
    private volatile String[] voices = new String[0];
    private volatile String status = "未初始化";
    private volatile String lastBackend = "";
    private volatile long lastSynthMs = 0;
    private volatile String lastOptions = "";
    private volatile boolean nativeUsable = true;
    // host2 是独立进程，但仍串行执行：它和所有调用方共用同一个 rig 目录。
    // 用独立锁而不是 this，是为了让 invalidate() / status() 不被几十秒的合成堵住。
    private final Object host2Lock = new Object();

    private EngineController(Context c) {
        app = c.getApplicationContext();
        // 上次进程死在 native 里时 inflight 来不及清除：据此禁用进程内后端，
        // 避免每次点「合成并播放」都把整个 app 干掉（SIGSEGV 不是 Java 异常，兜不住）。
        Prefs p = Prefs.get(app);
        if (p.inprocessInflight()) {
            p.inprocessInflight(false);
            p.inprocessCrashed(true);
            nativeUsable = false;
            status = "进程内 JNI 上次触发 SIGSEGV，已自动禁用；AUTO 改用 host2 兼容后端";
        }
    }

    public static EngineController get(Context c) {
        if (sInstance == null) {
            synchronized (EngineController.class) {
                if (sInstance == null) sInstance = new EngineController(c);
            }
        }
        return sInstance;
    }

    public String status() { return status; }
    public String[] voices() { return voices; }
    public String lastBackend() { return lastBackend; }
    public long lastSynthMs() { return lastSynthMs; }
    public String lastOptions() { return lastOptions; }

    /** 规则 / 音色 / 语音源变了就调用，下一次合成会重新 materialize + 重建引擎。 */
    public synchronized void invalidate() {
        appliedGeneration = -1;
        destroyNative();
        status = "已失效，等待下一次合成重建";
    }

    private void destroyNative() {
        if (handle != 0) {
            try { NativeEngine.destroy(handle); } catch (Throwable ignored) {}
            handle = 0;
        }
    }

    /** 准备进程内引擎：规则生效 → 资源根 → dlopen → init → 冒烟一发。 */
    private synchronized void ensureNative() throws Exception {
        Prefs p = Prefs.get(app);
        RuleManager.Report rep = RuleManager.apply(app);
        if (rep.changed) RuleManager.generationAfterChange(app, true);
        int gen = p.generation();

        if (handle != 0 && appliedGeneration == gen) return;

        destroyNative();
        ResourceRoot.Prepare prep = ResourceRoot.prepare(app, p.voice());
        voices = prep.voices;
        status = "资源根=" + prep.sourceId + " · " + rep.summary();

        // so 搜索路径：files/midu_native（厂商 so，由 Provisioner 镜像）: nativeLibraryDir（shim）。
        // 厂商 so 来自用户目录，/sdcard 与 /data/local/tmp 都不能 dlopen。
        String libDir = Provisioner.libDir(app);
        try {
            handle = NativeEngine.init(libDir, prep.root.getAbsolutePath(),
                    p.voice(), ResourceRoot.BASE, 24000);
        } catch (Throwable t) {
            nativeUsable = false;
            throw new IllegalStateException("进程内引擎加载失败（厂商 so 未就位？"
                    + "检查诊断页 midu_native 与语音来源目录）：" + t.getMessage(), t);
        }
        if (handle == 0) {
            nativeUsable = false;
            throw new IllegalStateException("进程内引擎 init 返回 0：" + NativeEngine.lastError());
        }

        // 冒烟：证明这套符号/资源组合真能出声，避免把「init 成功但 0 字节」当可用。
        byte[] pcm = NativeEngine.synth(handle, "你好。", 10, 10, 100, 20000);
        if (pcm == null || pcm.length < 64) {
            String e = NativeEngine.lastError();
            destroyNative();
            nativeUsable = false;
            throw new IllegalStateException("进程内引擎冒烟 0 字节：" + e);
        }
        nativeUsable = true;
        appliedGeneration = gen;
        status = "进程内 JNI 就绪 · " + rep.summary() + " · 冒烟 " + pcm.length + " B";
    }

    /** 旧调用点兼容：三参数已经算好，额外选项取当前预设。 */
    public byte[] synthesize(String text, int speed, int pitch, int volume)
            throws Exception {
        return synthesizeInternal(text, speed, pitch, volume,
                SoundProfiles.active(app).toOptions());
    }

    /** 全功能入口：指定预设 + 系统速率百分数（Legado HTTP 与系统 TTS 都走这里）。 */
    public byte[] synthesize(String text, SoundProfile profile,
                             int ratePercent, int pitchPercent)
            throws Exception {
        SoundProfile p = profile == null ? SoundProfiles.active(app) : profile;
        List<EngineOption> opts = p.toOptions();
        return synthesizeInternal(text, p.speedFor(ratePercent), p.pitchFor(pitchPercent),
                p.volume(), opts);
    }

    /**
     * host2 后端除了 speed 没有别的专用参数通道，基础 pitch / volume 会被丢掉
     * （C 侧 synth_one 把 tts_volume 写死 100、tts_pitch 只留启动时的 10）。
     * 把这两项补进选项列表，常驻 daemon v2 的 O 快照会逐条 setOption 覆盖它们；
     * 进程内 JNI 走自己的专用参数，不要加，避免同一键重复 setOption。
     * 一次性 exec 后端没有对应 env，会静默忽略（见 Host2ShellBackend.synthOnce）。
     */
    private static List<EngineOption> withBaseOptions(List<EngineOption> options,
                                                      int pitch, int volume) {
        List<EngineOption> out = new ArrayList<>(
                options == null ? Collections.<EngineOption>emptyList() : options);
        out.add(new EngineOption("tts_pitch", String.valueOf(pitch), EngineOption.INT));
        out.add(new EngineOption("tts_volume", String.valueOf(volume), EngineOption.INT));
        return out;
    }

    // 注意：这里刻意不是 synchronized。host2 一次合成可能几十秒，而 RulesPage 在
    // 主线程调 applyRules → invalidate()（synchronized(this)）；方法级锁会让主页
    // 在这几十秒里直接 ANR。只把「操作 native handle」的几步锁住即可。
    private byte[] synthesizeInternal(String text, int speed, int pitch, int volume,
                                      List<EngineOption> options) throws Exception {
        // 纯标点 / 空白片段会让离线引擎 frontend 报错且不给结束标记，
        // 常驻 daemon 会一直空等并堵住后续所有请求（Legado 连续朗读实测）。
        if (!Speakable.hasReadable(text)) return Speakable.shortSilence();
        Prefs p = Prefs.get(app);
        String mode = p.backend();
        Exception first = null;
        long t0 = System.currentTimeMillis();
        lastOptions = options == null || options.isEmpty() ? "（无扩展选项）" : options.toString();

        // 崩溃守护：AUTO 下只要上次死在 native，就不再尝试进程内后端。
        // 本次启动以来已确认进程内引擎不可用（加载失败 / 冒烟 0 字节）时同理，
        // 别再进去撞一次 SIGSEGV。用户显式强选 inprocess 时仍可重试。
        boolean guarded = mode.equals(Prefs.BACKEND_AUTO)
                && (p.inprocessCrashed() || !nativeUsable);
        if (guarded) status = "进程内 JNI 已禁用（上次崩溃/初始化失败），AUTO 直接走 host2"
                + "（诊断页可强选 inprocess 重试）";

        if (!guarded && (mode.equals(Prefs.BACKEND_AUTO) || mode.equals(Prefs.BACKEND_INPROCESS))) {
            try {
                p.inprocessInflight(true);      // 同步落盘，崩了下次启动能看见
                byte[] pcm = null;
                synchronized (this) {           // handle 与 appliedGeneration 的临界区
                    ensureNative();
                    pcm = NativeEngine.synthWithOptions(handle, text, speed, pitch, volume,
                            90000, options == null ? Collections.emptyList() : options);
                }
                if (pcm == null || pcm.length == 0)
                    throw new IllegalStateException("合成无 PCM：" + NativeEngine.lastError());
                p.inprocessInflight(false);
                if (p.inprocessCrashed()) p.inprocessCrashed(false);
                lastBackend = "inprocess";
                lastSynthMs = System.currentTimeMillis() - t0;
                return pcm;
            } catch (Exception e) {
                p.inprocessInflight(false);     // 能进 catch 说明没崩，标记必须清掉
                first = e;
                if (mode.equals(Prefs.BACKEND_INPROCESS)) {
                    status = "进程内失败：" + e.getMessage();
                    throw e;
                }
                status = "进程内失败，尝试 host2 兼容后端：" + e.getMessage();
            }
        }

        if (mode.equals(Prefs.BACKEND_AUTO) || mode.equals(Prefs.BACKEND_HOST2)) {
            try {
                synchronized (host2Lock) {
                    RuleManager.apply(app);   // 保证 merged 文件是最新的
                    List<EngineOption> host2Opts = withBaseOptions(options, pitch, volume);
                    lastOptions = host2Opts.toString();
                    byte[] pcm = Host2ShellBackend.synth(app, text, p.voice(), speed, host2Opts);
                    lastBackend = "host2_shell";
                    lastSynthMs = System.currentTimeMillis() - t0;
                    status = "host2 兼容后端就绪 · " + p.voice();
                    voices = p.voice().isEmpty() ? voices : new String[]{p.voice()};
                    return pcm;
                }
            } catch (Exception e) {
                status = "host2 失败：" + e.getMessage();
                if (first != null) e.addSuppressed(first);
                throw e;
            }
        }
        if (first != null) throw first;
        throw new IllegalStateException("未知后端：" + mode);
    }

    /**
     * 流式合成入口：host2 后端每来一帧 PCM 就回调一次，系统 TTS / Legado 可以
     * 边收边播，不必等整章合成完。进程内 JNI 后端暂不支持流式，退化为整段回调。
     */
    public void synthesizeStream(String text, SoundProfile profile,
                                 int ratePercent, int pitchPercent,
                                 PcmSink sink) throws Exception {
        if (!Speakable.hasReadable(text)) {
            byte[] silence = Speakable.shortSilence();
            sink.onPcm(silence, silence.length);
            return;
        }
        SoundProfile p = profile == null ? SoundProfiles.active(app) : profile;
        List<EngineOption> opts = p.toOptions();
        int speed = p.speedFor(ratePercent);
        Prefs prefs = Prefs.get(app);
        String mode = prefs.backend();
        lastOptions = opts == null || opts.isEmpty() ? "（无扩展选项）" : opts.toString();
        boolean guarded = mode.equals(Prefs.BACKEND_AUTO)
                && (prefs.inprocessCrashed() || !nativeUsable);
        boolean host2Path = mode.equals(Prefs.BACKEND_HOST2)
                || (mode.equals(Prefs.BACKEND_AUTO) && guarded);
        if (host2Path) {
            long t0 = System.currentTimeMillis();
            synchronized (host2Lock) {
                RuleManager.apply(app);
                List<EngineOption> host2Opts = withBaseOptions(opts,
                        p.pitchFor(pitchPercent), p.volume());
                lastOptions = host2Opts.toString();
                Host2ShellBackend.stream(app, text, prefs.voice(), speed, host2Opts, sink);
                lastBackend = Host2Daemon.reachable(app) ? "host2_daemon" : "host2_shell";
                lastSynthMs = System.currentTimeMillis() - t0;
                status = "host2 流式就绪 · " + prefs.voice();
                return;
            }
        }
        byte[] pcm = synthesizeInternal(text, speed, p.pitchFor(pitchPercent), p.volume(), opts);
        sink.onPcm(pcm, pcm.length);
    }

    /** 给首页「运行状态」用：进程内 JNI 是否已被崩溃守护挡住。 */
    public String inprocessGuard() {
        if (Prefs.get(app).inprocessCrashed()) return "已禁用（上次 SIGSEGV）";
        return nativeUsable ? "正常" : "已禁用（本次初始化失败）";
    }

    /** 手动解除守护：只在换设备 / 修好 shim 注入后才该用，否则下次合成还会崩。 */
    public synchronized void resetInprocessGuard() {
        Prefs p = Prefs.get(app);
        p.inprocessInflight(false);
        p.inprocessCrashed(false);
        nativeUsable = true;
        destroyNative();
        appliedGeneration = -1;
        status = "已清除进程内引擎崩溃守护，下次合成会重新尝试 JNI";
    }

    public synchronized void stop() {
        if (handle != 0) {
            try { NativeEngine.stop(handle); } catch (Throwable ignored) {}
        }
    }

    /** 给设置页用的自检（不抛异常）。 */
    public String smoke() {
        try {
            SoundProfile prof = SoundProfiles.active(app);
            byte[] pcm = synthesize("今天天气不错。", prof, 100, 100);
            return "冒烟成功：" + pcm.length + " B PCM（" + (pcm.length / 2 / 24000.0)
                    + " s @24k）· 后端=" + lastBackend + " · " + prof.describe();
        } catch (Throwable t) {
            return "冒烟失败：" + t.getMessage();
        }
    }
}
