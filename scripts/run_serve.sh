#!/system/bin/sh
# 设备侧手动拉起常驻 daemon（调试用）。
# App 侧不依赖这个脚本：它通过 Shizuku UserService 直接 exec host2_serve 并注入同样这批环境变量。
#
#   adb shell "cd /data/local/tmp/host2rig && TOKEN=xxx nohup sh ./run_serve.sh > serve.log 2>&1 &"
#
# LD_PRELOAD 只交给 host2 本体；export 给 sh 会让 shell 自己被 shim 顶崩。
RIG="${RIG:-/data/local/tmp/host2rig}"
LIB="${LIB:-$RIG/lib}"
ROOT="${ROOT:-$RIG/midu}"
VOICE="${VOICE:-BV001_24k_streaming}"
BASE="${BASE:-zh-cn}"
PORT="${PORT:-18945}"
TOKEN="${TOKEN:-}"

exec env LD_LIBRARY_PATH="$LIB" \
         LD_PRELOAD=log_shim.so:icu_shim.so:cronet_shim4.so \
         WAITSEC=120 \
         TTS_SPEED="${TTS_SPEED:-10}" \
         "$RIG/host2_serve" --root "$ROOT" --voice "$VOICE" --base "$BASE" \
                --serve --port "$PORT" --token "$TOKEN"
