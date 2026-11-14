package com.bd.tts.sound;

/**
 * 本地音频后处理：单声道 s16le PCM → 展宽立体声（逐块流式，块间状态连续）。
 *
 * 引擎（进程内 JNI / host2 daemon）只会输出单声道 PCM，朗读听感「干巴」很大程度
 * 就是因为声音完全集中在一点上。这里用 Haas / 反相侧链的方式在 app 本地造出
 * 「假立体声」宽度：
 *
 *   side = k · lowpass( dry 延迟 ~11ms )
 *   L    = c · dry + side
 *   R    = c · dry − side
 *
 * 侧链是「延迟 + 低通」的去相关信号，左右反相叠加：
 *   - 单声道下混 (L+R)/2 = c·dry，频谱与原声相同（无梳状滤波），手机单声道
 *     扬声器听感几乎不变——副作用为零；
 *   - 双声道输出（耳机 / 立体声喇叭）上则呈现开阔的空间感，人声定位仍在正中
 *     （不像左右各加不同延迟那样会把声音拉偏），朗读场景更稳。
 *
 * 用法：每个 utterance / 每路输出流新建一个实例，从第一块 PCM 喂到最后一块；
 * 延迟线与低通状态跨块保持。非线程安全，不同输出路径各自持有实例。
 * 宽度 0 = 不做任何处理（调用方直接走原单声道路径）。
 */
public final class StereoWiden {

    private static final double DELAY_MS = 11.0;   // Haas 区间（5~25ms）里偏短的档：空间感够、不浑浊
    private static final double LP_HZ = 3200.0;    // 侧链低通：只搬「空间感」频段，避免高频 flanging 染色

    private final int width;
    private final int delay;
    private final double sideGain;      // k
    private final double centerGain;    // c，略压干声补偿侧链能量，整体响度不至于变大
    private final double lpAlpha;
    private final short[] hist;         // 干声延迟线（环形）
    private int histPos = 0;
    private double lpState = 0;
    private byte tail;                  // 奇数字节块：最后半个样本留到下一块拼
    private boolean tailValid = false;

    public StereoWiden(int widthPercent, int sampleRate) {
        width = Math.max(0, Math.min(100, widthPercent));
        if (width <= 0) {
            delay = 0; sideGain = 0; centerGain = 1; lpAlpha = 0; hist = null;
            return;
        }
        double k = 0.85 * width / 100.0;            // 100% 时侧链 ≈ -1.4dB，不会糊
        sideGain = k;
        centerGain = 1.0 - 0.4 * k;
        delay = Math.max(1, (int) Math.round(sampleRate * DELAY_MS / 1000.0));
        lpAlpha = 1.0 - Math.exp(-2.0 * Math.PI * LP_HZ / sampleRate);
        hist = new short[delay];
    }

    /** 宽度为 0 时返回 false，调用方直接走原单声道路径（省一份拷贝与 header 差异）。 */
    public boolean active() { return width > 0; }

    /**
     * 输入一块单声道 s16le PCM（长度可以任意、跨块边界不按帧对齐），
     * 返回交错立体声 s16le PCM（长度恰为输入帧数的 4 字节/帧）。
     * 奇数字节长度的尾字节会自动留到下一块拼——host2 流式分帧偶尔不对齐也不出错。
     */
    public byte[] widen(byte[] buf, int len) {
        int carry = tailValid ? 1 : 0;
        int frames = (len + carry) / 2;
        byte[] out = new byte[frames * 4];
        int o = 0, i = 0;
        while (true) {
            int lo, hi;
            if (tailValid) {
                lo = tail & 0xFF;
                tailValid = false;
                if (i >= len) { tailValid = true; break; }   // 本块只有这半个样本
                hi = buf[i++] & 0xFF;
            } else {
                if (i + 1 >= len) {
                    if (i < len) { tail = buf[i]; tailValid = true; }
                    break;
                }
                lo = buf[i++] & 0xFF;
                hi = buf[i++] & 0xFF;
            }
            int dry = (short) ((hi << 8) | lo);

            double delayed = hist[histPos];
            hist[histPos] = (short) dry;
            histPos = histPos + 1 == delay ? 0 : histPos + 1;
            lpState += lpAlpha * (delayed - lpState);
            double side = sideGain * lpState;

            int l = saturate(centerGain * dry + side);
            int r = saturate(centerGain * dry - side);
            out[o++] = (byte) l;
            out[o++] = (byte) (l >> 8);
            out[o++] = (byte) r;
            out[o++] = (byte) (r >> 8);
        }
        return out;
    }

    private static int saturate(double v) {
        if (v > 32767.0) return 32767;
        if (v < -32768.0) return -32768;
        return (int) v;
    }
}
