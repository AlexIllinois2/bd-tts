package com.bd.tts.engine;

/** 与 jni/midu_bridge.c 对应。加载后才由 C 层 dlopen 厂商库闭包。 */
public final class NativeEngine {
    static { System.loadLibrary("midu_bridge"); }

    private static final String[] EMPTY_STRINGS = new String[0];
    private static final int[] EMPTY_INTS = new int[0];

    private NativeEngine() {}

    public static native long init(String libDir, String resRoot, String voice,
                                   String base, int sampleRate);
    /** 返回整段 s16le 单声道 PCM；失败返回 null。 */
    public static native byte[] synth(long handle, String text,
                                      int speed, int pitch, int volume, int timeoutMs);

    /**
     * 通用合成：额外下发任意 tts_* 选项。
     * keys / values / types 是三条平行数组；types 见 EngineOption.Type。
     * C 层会过滤掉钉死的初始化档位，见 fake_jni.c 的 RESERVED_KEYS。
     */
    public static native byte[] synthEx(long handle, String text,
                                        int speed, int pitch, int volume, int timeoutMs,
                                        String[] keys, String[] values, int[] types);

    /** Java 侧便捷封装：把 EngineOption 列表拍平成三条数组。 */
    public static byte[] synthWithOptions(long handle, String text,
                                          int speed, int pitch, int volume, int timeoutMs,
                                          java.util.List<EngineOption> options) {
        if (options == null || options.isEmpty()) {
            return synth(handle, text, speed, pitch, volume, timeoutMs);
        }
        int n = options.size();
        String[] keys = new String[n];
        String[] vals = new String[n];
        int[] types = new int[n];
        for (int i = 0; i < n; i++) {
            EngineOption o = options.get(i);
            keys[i] = o.key;
            vals[i] = o.value == null ? "" : o.value;
            types[i] = o.type;
        }
        return synthEx(handle, text, speed, pitch, volume, timeoutMs, keys, vals, types);
    }

    public static native void stop(long handle);
    public static native void destroy(long handle);
    public static native String lastError();
    public static native String version();
}
