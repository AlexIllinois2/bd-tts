package com.midu.tts.engine;

import android.content.Context;

import com.midu.tts.rules.RuleManager;
import com.midu.tts.sound.SoundProfile;
import com.midu.tts.sound.SoundProfiles;
import com.midu.tts.source.ResourceRoot;
import com.midu.tts.util.Prefs;

import java.util.Collections;
import java.util.List;

/** 引擎生命周期：规则代数 → 资源根 → 引擎实例 → 后端选择 / 自动降级。 */
public final class EngineController {
    private static volatile EngineController sInstance;

    private final Context app;
    private long handle = 0;
    private int appliedGeneration = -1;
    private String[] voices = new String[0];
    private String status = "未初始化";
    private String lastBackend = "";
    private boolean nativeUsable = true;
    private long lastSynthMs = 0;
    private String lastOptions = "";

    private EngineController(Context c) { app = c.getApplicationContext(); }

    public static EngineController get(Context c) {
        if (sInstance == null) {
            synchronized (EngineController.class) {
                if (sInstance == null) sInstance = new EngineController(c);
            }
        }
        return sInstance;
    }

    public synchronized String status() { return status; }
    public synchronized String[] voices() { return voices; }
    public synchronized String lastBackend() { return lastBackend; }
    public synchronized long lastSynthMs() { return lastSynthMs; }
    public synchronized String lastOptions() { return lastOptions; }

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

        String libDir = app.getApplicationInfo().nativeLibraryDir;
        try {
            handle = NativeEngine.init(libDir, prep.root.getAbsolutePath(),
                    p.voice(), ResourceRoot.BASE, 24000);
        } catch (Throwable t) {
            nativeUsable = false;
            throw new IllegalStateException("进程内引擎加载失败（ABI/库闭包？）：" + t.getMessage(), t);
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
    public synchronized byte[] synthesize(String text, int speed, int pitch, int volume)
            throws Exception {
        return synthesizeInternal(text, speed, pitch, volume,
                SoundProfiles.active(app).toOptions());
    }

    /** 全功能入口：指定预设 + 系统速率百分数（Legado HTTP 与系统 TTS 都走这里）。 */
    public synchronized byte[] synthesize(String text, SoundProfile profile,
                                          int ratePercent, int pitchPercent)
            throws Exception {
        SoundProfile p = profile == null ? SoundProfiles.active(app) : profile;
        List<EngineOption> opts = p.toOptions();
        return synthesizeInternal(text, p.speedFor(ratePercent), p.pitchFor(pitchPercent),
                p.volume(), opts);
    }

    private synchronized byte[] synthesizeInternal(String text, int speed, int pitch, int volume,
                                                   List<EngineOption> options) throws Exception {
        Prefs p = Prefs.get(app);
        String mode = p.backend();
        Exception first = null;
        long t0 = System.currentTimeMillis();
        lastOptions = options == null || options.isEmpty() ? "（无扩展选项）" : options.toString();

        if ((mode.equals(Prefs.BACKEND_AUTO) || mode.equals(Prefs.BACKEND_INPROCESS))) {
            try {
                ensureNative();
                byte[] pcm = NativeEngine.synthWithOptions(handle, text, speed, pitch, volume,
                        90000, options == null ? Collections.emptyList() : options);
                if (pcm == null || pcm.length == 0)
                    throw new IllegalStateException("合成无 PCM：" + NativeEngine.lastError());
                lastBackend = "inprocess";
                lastSynthMs = System.currentTimeMillis() - t0;
                return pcm;
            } catch (Exception e) {
                first = e;
                if (mode.equals(Prefs.BACKEND_INPROCESS)) {
                    status = "进程内失败：" + e.getMessage();
                    throw e;
                }
                status = "进程内失败，尝试 host2 兼容后端：" + e.getMessage();
            }
        }

        if (mode.equals(Prefs.BACKEND_AUTO) || mode.equals(Prefs.BACKEND_HOST2)) {
            RuleManager.apply(app);   // 保证 merged 文件是最新的
            byte[] pcm = Host2ShellBackend.synth(app, text, p.voice(), speed, options);
            lastBackend = "host2_shell";
            lastSynthMs = System.currentTimeMillis() - t0;
            status = "host2 兼容后端就绪 · " + p.voice();
            voices = p.voice().isEmpty() ? voices : new String[]{p.voice()};
            return pcm;
        }
        if (first != null) throw first;
        throw new IllegalStateException("未知后端：" + mode);
    }

    public synchronized void stop() {
        if (handle != 0) {
            try { NativeEngine.stop(handle); } catch (Throwable ignored) {}
        }
    }

    /** 给设置页用的自检（不抛异常）。 */
    public synchronized String smoke() {
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
