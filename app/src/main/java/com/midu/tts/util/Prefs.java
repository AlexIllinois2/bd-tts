package com.midu.tts.util;

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

    private static final String FILE = "midu_prefs";
    private final SharedPreferences sp;

    private Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    public static Prefs get(Context c) { return new Prefs(c); }

    public String source()      { return sp.getString("source", SOURCE_BUNDLED); }
    public Prefs source(String v){ sp.edit().putString("source", v).apply(); return this; }

    public String customPath()  { return sp.getString("custom_path", ""); }
    public Prefs customPath(String v){ sp.edit().putString("custom_path", v).apply(); return this; }

    public String multittsRoot(){ return sp.getString("multitts_root", DEFAULT_MULTITTS_ROOT); }
    public Prefs multittsRoot(String v){ sp.edit().putString("multitts_root", v).apply(); return this; }

    public String host2Rig()    { return sp.getString("host2_rig", DEFAULT_HOST2_RIG); }
    public Prefs host2Rig(String v){ sp.edit().putString("host2_rig", v).apply(); return this; }

    public String voice()       { return sp.getString("voice", DEFAULT_VOICE); }
    public Prefs voice(String v){ sp.edit().putString("voice", v).apply(); return this; }

    public String backend()     { return sp.getString("backend", BACKEND_AUTO); }
    public Prefs backend(String v){ sp.edit().putString("backend", v).apply(); return this; }

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
    /** 允许 HTTP 请求覆盖「仅静态证据」的实验性选项。 */
    public boolean httpAllowExperimental(){ return sp.getBoolean("http_allow_experimental", false); }
    public Prefs httpAllowExperimental(boolean v){ sp.edit().putBoolean("http_allow_experimental", v).apply(); return this; }
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
    public String patchUrl()    { return sp.getString("patch_url", ""); }
    public Prefs patchUrl(String v){ sp.edit().putString("patch_url", v).apply(); return this; }

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
