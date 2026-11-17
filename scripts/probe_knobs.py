#!/usr/bin/env python3
"""
声音页高级参数 A/B 实测：通过 App 本地 HTTP /tts 逐项覆盖参数合成，
比较两段 WAV 的时长 / 能量包络 / 波形差异，判断引擎是否真的响应该参数。

用法：
  1) 先在 App「Legado / 朗读服务」页启动本地 HTTP 服务（默认 127.0.0.1:8765）
  2) adb forward tcp:8765 tcp:8765
  3) python3 scripts/probe_knobs.py [--repeat 2] [--only key1,key2]

输出：
  - /tmp/knob_probe/ 下每项的 A/B WAV
  - 终端表格：每项时长差 / 能量差 / 判定（RESPONDS / NO-EFFECT / 需试听）
注意：引擎不钉种子，同文本两发音色细节不同；这里只看宏观量（时长 / 包络），
      所以必须用「极端值」拉开差距，避免被随机波动淹没。
"""
import argparse
import json
import math
import os
import struct
import sys
import time
import urllib.parse
import urllib.request

HOST = "127.0.0.1:8765"
OUT = "/tmp/knob_probe"
TEXT = "今天天气不错，银行的行长在人行道上行走了很久。小猫在窗台上晒太阳。"
# 每项：(key, A 值, B 值)。A=默认/中性，B=极端；不改基础三项与已验证的展宽。
PROBES = [
    ("tts_silence_duration",      "0",   "2000"),
    ("tts_sub_sentence_length",   "0",   "200"),
    ("tts_speed_ratio",           "100", "300"),
    ("tts_volume_ratio",          "100", "300"),
    ("tts_pitch_ratio",           "100", "300"),
    ("tts_split_sentence",        "0",   "1"),
    ("tts_pure_english_opt",      "0",   "2"),
    ("tts_emotion",               "",    "happy"),
    ("tts_style_name",            "",    "happy"),
    ("tts_voiceclone_style",      "",    "happy"),
    ("tts_frontend_overwrite_label", "", "x"),
    ("tts_fadein_duration",       "0",   "2000"),
    ("tts_fadeout_duration",      "0",   "2000"),
    ("tts_compression_rate",      "0",   "100"),
    ("tts_with_timestamp",        "0",   "1"),
    ("tts_with_intent",           "0",   "1"),
    ("tts_limit_cpu_usage",       "0",   "1"),
    ("tts_concurrency_mode",      "0",   "4"),
    ("tts_use_voiceclone",        "0",   "1"),
    ("voiceconv_pitch",           "50",  "100"),
    ("voiceconv_volume",          "50",  "100"),
    # tts_text_type 单独处理：普通文本 plain vs ssml（无 phoneme 标记时应一致）
    ("tts_text_type",             "plain", "ssml"),
    # 高危项：可能让引擎吐非 s16le / 直接写盘，单列但默认跳过
    # ("tts_encoding", "pcm", "mp3"),
    # ("tts_audio_path", "", "/sdcard/knobtest"),
    # ("tts_audio_file", "", "knobtest.wav"),
]
HIGH_RISK = {"tts_encoding", "tts_audio_path", "tts_audio_file"}


def synth(knob, value, tag):
    # 固定基础参数 + 关掉本地立体声展宽，保证 A/B 只差被测那一项，且输出单声道
    args = {"text": TEXT, "speed": "20", "pitch": "100", "bd_stereo_width": "0"}
    if knob:
        args[knob] = value
    url = "http://" + HOST + "/tts?" + urllib.parse.urlencode(args)
    req = urllib.request.Request(url, headers={"User-Agent": "knob-probe"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    dt = time.time() - t0
    path = os.path.join(OUT, f"{knob or 'base'}_{tag}.wav")
    with open(path, "wb") as f:
        f.write(data)
    return path, dt


def wav_stats(path):
    """返回 (秒数, rms, peak, 过零率, 20 段能量包络)。按 WAV 头读声道/采样率。"""
    with open(path, "rb") as f:
        raw = f.read()
    i = raw.find(b"data")
    if i < 0:
        return None
    n = struct.unpack("<I", raw[i + 4:i + 8])[0]
    channels = struct.unpack("<H", raw[22:24])[0] if len(raw) >= 24 else 1
    rate = struct.unpack("<I", raw[24:28])[0] if len(raw) >= 28 else 24000
    pcm = raw[i + 8:i + 8 + n]
    if len(pcm) < 2:
        return None
    samples = struct.unpack("<" + "h" * (len(pcm) // 2), pcm)
    sec = len(samples) / max(1, channels) / float(rate)
    rms = math.sqrt(sum(s * s for s in samples) / len(samples))
    peak = max(abs(s) for s in samples)
    zc = sum(1 for a, b in zip(samples, samples[1:]) if (a < 0) != (b < 0)) / max(1, len(samples) - 1)
    nseg = 20
    step = max(1, len(samples) // nseg)
    env = []
    for k in range(nseg):
        chunk = samples[k * step:(k + 1) * step]
        env.append(math.sqrt(sum(s * s for s in chunk) / max(1, len(chunk))) if chunk else 0.0)
    return sec, rms, peak, zc, env


def env_diff(e1, e2):
    if not e1 or not e2 or len(e1) != len(e2):
        return 0.0
    # 归一化后逐段绝对差均值（消掉整体音量差，只看包络形状）
    m1 = max(e1) or 1.0
    m2 = max(e2) or 1.0
    return sum(abs(a / m1 - b / m2) for a, b in zip(e1, e2)) / len(e1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repeat", type=int, default=2, help="每个取值合成次数，取中位以抗随机")
    ap.add_argument("--only", default="", help="只测逗号分隔的 key")
    ap.add_argument("--no-base", action="store_true", help="跳过基线")
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    only = set(x.strip() for x in args.only.split(",") if x.strip())
    probes = [p for p in PROBES if (not only or p[0] in only) and p[0] not in HIGH_RISK]

    print(f"HOST={HOST}  repeat={args.repeat}  probes={len(probes)}\n")

    # 基线：不覆盖任何 knob，repeat 次，建立随机波动的量级
    base_stats = []
    if not args.no_base:
        for i in range(args.repeat):
            p, dt = synth(None, "", f"base{i}")
            s = wav_stats(p)
            if s:
                base_stats.append(s)
            print(f"[base {i}] {dt:.1f}s -> {p}")
    if base_stats:
        bsec = [s[0] for s in base_stats]
        brms = [s[1] for s in base_stats]
        benv = [env_diff(base_stats[0][4], s[4]) for s in base_stats[1:]]
        print(f"  baseline: 时长 {min(bsec):.2f}~{max(bsec):.2f}s  "
              f"RMS {min(brms):.0f}~{max(brms):.0f}  "
              f"包络自差 {max(benv) if benv else 0:.3f}\n")

    results = []
    for key, va, vb in probes:
        rows = {}
        for tag, val in (("A", va), ("B", vb)):
            vals = []
            for i in range(args.repeat):
                try:
                    p, dt = synth(key, val, f"{tag}{i}")
                    s = wav_stats(p)
                    if s:
                        vals.append(s)
                    print(f"[{key} {tag}={val!r} #{i}] {dt:.1f}s -> {p}")
                except Exception as e:
                    print(f"[{key} {tag}] 失败：{e}", file=sys.stderr)
            if vals:
                rows[tag] = vals
        if "A" not in rows or "B" not in rows:
            results.append((key, va, vb, None, None, None, "ERROR"))
            continue
        # 取每次合成的中位代表（repeat=1 就是那一次）
        def median(xs):
            xs = sorted(xs); return xs[len(xs) // 2]
        a, b = median(rows["A"]), median(rows["B"])
        dsec = abs(a[0] - b[0])
        drls = abs(a[1] - b[1]) / max(1.0, min(a[1], b[1]))
        denv = env_diff(a[4], b[4])
        # 判定：时长差 > 0.15s 或包络形状差 > 0.10 视为响应
        if dsec > 0.15 or denv > 0.10:
            verdict = "RESPONDS"
        else:
            verdict = "NO-EFFECT"
        results.append((key, va, vb, dsec, drls, denv, verdict))
        print()

    print("=" * 92)
    print(f"{'key':34} {'A':>8} {'B':>8} {'Δ秒':>7} {'Δ能量':>7} {'Δ包络':>7}  判定")
    print("-" * 92)
    for key, va, vb, dsec, drls, denv, verdict in results:
        print(f"{key:34} {va[:8]:>8} {vb[:8]:>8} "
              f"{(f'{dsec:.2f}' if dsec is not None else '-'):>7} "
              f"{(f'{drls:.2f}' if drls is not None else '-'):>7} "
              f"{(f'{denv:.3f}' if denv is not None else '-'):>7}  {verdict}")
    with open(os.path.join(OUT, "result.json"), "w") as f:
        json.dump([{"key": r[0], "a": r[1], "b": r[2], "dsec": r[3],
                    "drls": r[4], "denv": r[5], "verdict": r[6]} for r in results],
                  f, ensure_ascii=False, indent=2)
    print(f"\nWAV 在 {OUT}/，结论在 {OUT}/result.json")


if __name__ == "__main__":
    main()
