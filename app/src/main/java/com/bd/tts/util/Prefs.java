package com.bd.tts.util;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部用户偏好集中在这里；TTS 服务与设置界面读同一份。 */
public final class Prefs {
    public static final String SOURCE_BUNDLED  = "bundled";
    public static final String SOURCE_DIRECTORY = "directory";
    public static final String SOURCE_SHIZUKU  = "multitts_shizuku";

    public static final String BACKEND_AUTO     = "auto";
    public static final String BACKEND_INPROCESS= "inprocess";
    public static final String BACKEND_HOST2    = "host2_shell";

    public static final String DEFAULT_MULTITTS_ROOT =
            "/storage/emulated/0/Android/data/org.nobody.multitts/files/voice/bytedance/midu";
    public static final String DEFAULT_HOST2_RIG = "/data/local/tmp/host2rig";
    public static final String DEFAULT_VOICE = "BV001_24k_streaming";

    private static final String FILE = "bd_prefs";
    private final SharedPreferences sp;

    private Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    public static Prefs get(Context c) { return new Prefs(c); }

    // "bundled" 只是历史值：APK 已不内置厂商资源，解析时按用户目录处理。
    public String source()      { return sp.getString("source", SOURCE_DIRECTORY); }
    public Prefs source(String v){ sp.edit().putString("source", v).apply(); return this; }

    public String customPath()  { return sp.getString("custom_path", ""); }
    public Prefs customPath(String v){ sp.edit().putString("custom_path", v).apply(); return this; }

    /**
     * 用户手动选择的厂商 so 来源（MultiTTS apk / 内含 lib/arm64-v8a 的 zip /
     * 一个或多个 .so 文件 / 包含它们的文件夹）。多个 URI 用 \n 分隔；
     * content:// 文档用 SAF 读，普通路径直接读。
     */
    public String soPath()      { return sp.getString("so_path", ""); }
    public Prefs soPath(String v){ sp.edit().putString("so_path", v == null ? "" : v).apply(); return this; }

    public String multittsRoot(){ return sp.getString("multitts_root", DEFAULT_MULTITTS_ROOT); }
    public Prefs multittsRoot(String v){ sp.edit().putString("multitts_root", v).apply(); return this; }

    public String host2Rig()    { return sp.getString("host2_rig", DEFAULT_HOST2_RIG); }
    public Prefs host2Rig(String v){ sp.edit().putString("host2_rig", v).apply(); return this; }

    // ---- host2 常驻服务（rig/host2_serve --serve） ---------------------------
    // 默认开启但「可选增强」：rig 里没这个二进制时 Host2ShellBackend 会自动回退一次性 exec。
    public boolean host2DaemonEnabled(){ return sp.getBoolean("host2_daemon", true); }
    public Prefs host2DaemonEnabled(boolean v){ sp.edit().putBoolean("host2_daemon", v).apply(); return this; }
    public int host2DaemonPort(){ return sp.getInt("host2_daemon_port", 18945); }
    public Prefs host2DaemonPort(int v){ sp.edit().putInt("host2_daemon_port", v).apply(); return this; }
    /** 空串 = 不校验；连本机 loopback 一般不用设，多用户/调试时可加。 */
    public String host2DaemonToken(){ return sp.getString("host2_daemon_token", ""); }
    public Prefs host2DaemonToken(String v){ sp.edit().putString("host2_daemon_token", v).apply(); return this; }
    /**
     * 常驻 daemon 启动时用的音色（--voice 是启动参数，运行中改不了）。
     * 与 voice() 不一致时 Host2Daemon.ensureStarted 会停掉旧 daemon 再按新音色拉起。
     * 空串表示当前 daemon 不是本 app 拉起的（或已被 stop），下次合成会重新接管。
     */
    public String host2DaemonVoice(){ return sp.getString("host2_daemon_voice", ""); }
    public Prefs host2DaemonVoice(String v){ sp.edit().putString("host2_daemon_voice", v).apply(); return this; }

    public String voice()       { return sp.getString("voice", DEFAULT_VOICE); }
    public Prefs voice(String v){ sp.edit().putString("voice", v).apply(); return this; }

    public String backend()     { return sp.getString("backend", BACKEND_AUTO); }
    public Prefs backend(String v){ sp.edit().putString("backend", v).apply(); return this; }

    // ---- 进程内 JNI 崩溃守护 -------------------------------------------------
    // native SIGSEGV 会直接杀进程，Java 层 catch 不到。进 native 前同步落盘 inflight
    // 标记，正常返回才清除；下次启动若标记还在，就说明上次死在 native 里。
    // 这里必须用 commit()：apply() 是异步的，进程被信号杀掉时可能还没写盘。
    public boolean inprocessInflight(){ return sp.getBoolean("inproc_inflight", false); }
    public Prefs inprocessInflight(boolean v){ sp.edit().putBoolean("inproc_inflight", v).commit(); return this; }

    public boolean inprocessCrashed(){ return sp.getBoolean("inproc_crashed", false); }
    public Prefs inprocessCrashed(boolean v){ sp.edit().putBoolean("inproc_crashed", v).commit(); return this; }

    public int speed()          { return sp.getInt("speed", 10); }
    public Prefs speed(int v)   { sp.edit().putInt("speed", v).apply(); return this; }
    public int pitch()          { return sp.getInt("pitch", 10); }
    public Prefs pitch(int v)   { sp.edit().putInt("pitch", v).apply(); return this; }
    public int volume()         { return sp.getInt("volume", 100); }
    public Prefs volume(int v)  { sp.edit().putInt("volume", v).apply(); return this; }

    // ---- Legado 本地 HTTP 朗读引擎 -------------------------------------------
    public int httpPort()       { return sp.getInt("http_port", 8765); }
    public Prefs httpPort(int v){ sp.edit().putInt("http_port", v).apply(); return this; }
    /** 默认只监听 127.0.0.1；打开后监听 0.0.0.0，供同局域网设备调用。 */
    public boolean httpBindAll(){ return sp.getBoolean("http_bind_all", false); }
    public Prefs httpBindAll(boolean v){ sp.edit().putBoolean("http_bind_all", v).apply(); return this; }
    /** 把 Legado 的发言人（toneID）映射到本地声音预设。 */
    public boolean httpSpeakerEnabled(){ return sp.getBoolean("http_speaker_enabled", true); }
    public Prefs httpSpeakerEnabled(boolean v){ sp.edit().putBoolean("http_speaker_enabled", v).apply(); return this; }
    /** 把 Legado 的情绪标志映射成韵律微调。 */
    public boolean httpEmotionEnabled(){ return sp.getBoolean("http_emotion_enabled", true); }
    public Prefs httpEmotionEnabled(boolean v){ sp.edit().putBoolean("http_emotion_enabled", v).apply(); return this; }
    /** 服务是否应随应用启动自动拉起。 */
    public boolean httpAutoStart(){ return sp.getBoolean("http_auto_start", false); }
    public Prefs httpAutoStart(boolean v){ sp.edit().putBoolean("http_auto_start", v).apply(); return this; }

    public boolean autoUpdate() { return sp.getBoolean("auto_update", true); }
    public Prefs autoUpdate(boolean v){ sp.edit().putBoolean("auto_update", v).apply(); return this; }
    public int intervalHours()  { return sp.getInt("interval_h", 6); }
    public Prefs intervalHours(int v){ sp.edit().putInt("interval_h", Math.max(1, v)).apply(); return this; }

    public String ruleUrl()     { return sp.getString("rule_url", ""); }
    public Prefs ruleUrl(String v){ sp.edit().putString("rule_url", v).apply(); return this; }

    public String etag(String key){ return sp.getString("etag_" + key, ""); }
    public Prefs etag(String key, String v){ sp.edit().putString("etag_" + key, v == null ? "" : v).apply(); return this; }
    public long lastCheck()     { return sp.getLong("last_check", 0); }
    public Prefs lastCheck(long v){ sp.edit().putLong("last_check", v).apply(); return this; }
    public long lastUpdate()    { return sp.getLong("last_update", 0); }
    public Prefs lastUpdate(long v){ sp.edit().putLong("last_update", v).apply(); return this; }

    public int generation()     { return sp.getInt("rule_generation", 0); }
    public int bumpGeneration() {
        int g = generation() + 1;
        sp.edit().putInt("rule_generation", g).apply();
        return g;
    }
}
