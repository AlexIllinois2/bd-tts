#!/usr/bin/env bash
# 从 rig/src/host2.c 编出带 --serve 常驻模式的 host2_serve。
#
#   CHECK=1 ./build_host2_serve.sh          # 只做 -fsyntax-only，不产出任何文件
#   ./build_host2_serve.sh                  # 产出 rig/out/host2_serve
#   PUSH=1 SER=<adb serial> ./build_host2_serve.sh   # 顺带推到真机 rig 目录
#   NDK=/path/to/ndk/29.0.13846066 ./build_host2_serve.sh
#
# ★刻意不覆盖 rig 里的 host2：一次性后端（run_dev.sh）仍用原件，
#   app 侧只有检测到 host2_serve 存在才走常驻；推坏了删掉这个文件即自动回退。
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${NDK:-$HOME/Android/Sdk/ndk/29.0.13846066}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
ZIP="$HERE/vendor/midu-host2rig-arm64-20261105.zip"
SRC="$HERE/rig/src/host2.c"
OUT="$HERE/rig/out"

[ -x "$CC" ] || { echo "× 找不到 NDK clang：$CC（用 NDK=<路径> 指定）"; exit 1; }
[ -f "$ZIP" ] || { echo "× 缺 vendor 包：$ZIP（host2.c 吃里面的 jni_local.h / jnienv_stub.h）"; exit 1; }
[ -f "$SRC" ] || { echo "× 缺 $SRC"; exit 1; }

DEPS="$(mktemp -d)"
trap 'rm -rf "$DEPS"' EXIT
python3 - "$ZIP" "$DEPS" <<'PY'
import sys, zipfile, pathlib
z = zipfile.ZipFile(sys.argv[1]); d = pathlib.Path(sys.argv[2])
for n in ("host2rig-arm64-20261105/src/jni_local.h",
          "host2rig-arm64-20261105/src/jnienv_stub.h"):
    (d / pathlib.Path(n).name).write_bytes(z.read(n))
PY

if [ "${CHECK:-0}" = 1 ]; then
    echo "== -fsyntax-only 语法检查（不产出文件）"
    "$CC" -fsyntax-only -I"$DEPS" "$SRC"
    echo "✓ 语法通过"
    exit 0
fi

mkdir -p "$OUT"
# 与工装 build.sh 的 arm64 编译行一致，只多一个 -I（头文件来自 vendor 包）。
"$CC" -o "$OUT/host2_serve" "$SRC" -I"$DEPS"
chmod 755 "$OUT/host2_serve"
echo "✓ 产出 $OUT/host2_serve"
ls -l "$OUT/host2_serve"

if [ "${PUSH:-0}" = 1 ]; then
    ADB=(adb)
    [ -n "${SER:-}" ] && ADB=(adb -s "$SER")
    echo "== 推到 /data/local/tmp/host2rig/host2_serve（不覆盖 host2）"
    "${ADB[@]}" push "$OUT/host2_serve" /data/local/tmp/host2rig/host2_serve
    "${ADB[@]}" shell chmod 755 /data/local/tmp/host2rig/host2_serve
    "${ADB[@]}" shell ls -l /data/local/tmp/host2rig/host2_serve
fi
