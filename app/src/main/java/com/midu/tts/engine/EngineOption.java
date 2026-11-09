package com.midu.tts.engine;

/** 一条要下发给引擎的 setOption。type 与 jni/fake_jni.h 的 MIDU_OPT_* 一致。 */
public final class EngineOption {
    public static final int STRING = 0;
    public static final int INT    = 1;
    public static final int BOOL   = 2;
    public static final int DOUBLE = 3;

    public final String key;
    public final String value;
    public final int type;

    public EngineOption(String key, String value, int type) {
        this.key = key;
        this.value = value == null ? "" : value;
        this.type = type;
    }

    public static EngineOption str(String k, String v)  { return new EngineOption(k, v, STRING); }
    public static EngineOption integer(String k, int v) { return new EngineOption(k, String.valueOf(v), INT); }
    public static EngineOption bool(String k, boolean v){ return new EngineOption(k, v ? "1" : "0", BOOL); }
    public static EngineOption dbl(String k, double v)  { return new EngineOption(k, String.valueOf(v), DOUBLE); }

    @Override public String toString() { return key + "=" + value + " (t" + type + ")"; }
}
