#!/system/bin/sh
# rig 启动脚本：给 Host2ShellBackend（Shizuku UserService）用。
#
# Java 侧通过 ProcessBuilder 传入这些环境变量：
#   RIG BIN LIB ROOT VOICE TEXT OUT LOG APPEND WAITSEC TTS_SPEED
#   TTS_SIL / TTS_SUBLEN / TTS_SPEED_RATIO（预设里若开启才传）
# 输出 PCM 必须落到 $OUT，Java 侧会再 openFile 读回；stdout/stderr 由 Java 侧重定向到 $LOG。
#
# 与设备上的 dev_const_run.sh 保持同一套注入参数：LD_PRELOAD 只交给 host2 本体，
# 绝不 export 给 shell —— sh 自己被 shim 顶到会直接崩（32 位环境尤其明显）。
RIG="${RIG:-/data/local/tmp/host2rig}"
BIN="${BIN:-$RIG/host2}"
LIB="${LIB:-$RIG/lib}"
ROOT="${ROOT:-$RIG/midu}"
VOICE="${VOICE:-BV001_24k_streaming}"
TEXT="${TEXT:-今天天气不错。}"
OUT="${OUT:-$RIG/midu_out.pcm}"
BASE="${BASE:-zh-cn}"

# 只转发真正设置过的 tts_* 环境变量；空串会让 host2 的 atoi 拿到 0，改变默认韵律。
set --
[ -n "${TTS_SPEED:-}" ]       && set -- "$@" "TTS_SPEED=$TTS_SPEED"
[ -n "${TTS_SIL:-}" ]         && set -- "$@" "TTS_SIL=$TTS_SIL"
[ -n "${TTS_SUBLEN:-}" ]      && set -- "$@" "TTS_SUBLEN=$TTS_SUBLEN"
[ -n "${TTS_SPEED_RATIO:-}" ] && set -- "$@" "TTS_SPEED_RATIO=$TTS_SPEED_RATIO"

exec env LD_LIBRARY_PATH="$LIB" \
         LD_PRELOAD=log_shim.so:icu_shim.so:cronet_shim4.so \
         "$@" \
         "$BIN" --root "$ROOT" --voice "$VOICE" --base "$BASE" \
                --text "$TEXT" --out "$OUT" --synth
