#!/usr/bin/env bash
# mumu_debug.sh — MuMu 模拟器上的一条龙调试（连接 / ABI 预检 / 装包 / 推语音包 / 设为默认引擎 / 抓日志）
#
#   PORT=16384 ./scripts/mumu_debug.sh            # MuMu 12 本体
#   PORT=5555  ./scripts/mumu_debug.sh            # MuMu 5.0
#   PORT=7555  ./scripts/mumu_debug.sh            # MuMu 6
#   SER=127.0.0.1:16384 APK=.../app-debug.apk ./scripts/mumu_debug.sh
#   SKIP_INSTALL=1 ./scripts/mumu_debug.sh        # 只做预检 + 日志
#
#   本机环境：adb 已在 /usr/bin/adb（也可 ADB=/home/v/Android/Sdk/platform-tools/adb 指定）；
#   APK 默认取工程内 app/build/outputs/apk/debug/app-debug.apk。
#
# ★硬前提：厂商 libspeechengine.so 只有 arm64-v8a 原件。MuMu 必须支持并选中 arm64-v8a，
#   否则 app 进程里 dlopen 会直接失败（改走真机 / 支持 ARM64 的镜像）。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$(cd "$HERE/.." && pwd)"
WORKSPACE="$(cd "$ANDROID/.." && pwd)"
ADB="${ADB:-adb}"
PORT="${PORT:-16384}"
SER="${SER:-127.0.0.1:$PORT}"
PKG="${PKG:-com.bd.tts}"
APK="${APK:-$ANDROID/app/build/outputs/apk/debug/app-debug.apk}"
EMU_VOICE="${EMU_VOICE:-/sdcard/midu_voice}"
EMU_RIG="${EMU_RIG:-/data/local/tmp/host2rig}"

echo "== 连接 $SER"
"$ADB" connect "$SER" || { echo "× adb connect 失败（MuMu 开了吗？端口在『问题诊断』里看）"; exit 1; }
"$ADB" -s "$SER" wait-for-device

echo "== ABI 预检"
ABI_LIST="$("$ADB" -s "$SER" shell getprop ro.product.cpu.abilist | tr -d '\r')"
ABI64="$("$ADB" -s "$SER" shell getprop ro.product.cpu.abilist64 | tr -d '\r')"
BRIDGE="$("$ADB" -s "$SER" shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')"
echo "   abilist      = $ABI_LIST"
echo "   abilist64    = $ABI64"
echo "   native.bridge= $BRIDGE"
case "$ABI_LIST $ABI64" in
  *arm64-v8a*) echo "   ✓ 含 arm64-v8a" ;;
  *) echo "   × 不含 arm64-v8a：厂商库无法运行。"
     echo "     MuMu 可在 /data/system/etc/mumu-configs/abi-select-android12.config 末尾加："
     echo "       $PKG arm64-v8a"
     echo "     保存后重启模拟器；仍不行就换 ARM64 镜像或真机。"
     exit 2 ;;
esac

if [ "${SKIP_INSTALL:-0}" != 1 ]; then
  [ -f "$APK" ] || { echo "× 找不到 APK：$APK（先 ./gradlew assembleDebug）"; exit 1; }
  echo "== 安装 $APK"
  "$ADB" -s "$SER" install -r -t "$APK" || exit 1
  echo "== 应用的 primaryCpuAbi（必须是 arm64-v8a）"
  "$ADB" -s "$SER" shell pm dump "$PKG" | grep -i "primaryCpuAbi" | head -3 || true
  "$ADB" -s "$SER" shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1 || true
fi

if [ "${PUSH_VOICE:-1}" = 1 ] && [ -d "$WORKSPACE/host2rig/midu" ]; then
  echo "== 推离线语音包 → $EMU_VOICE（免 Shizuku 的调试路径）"
  "$ADB" -s "$SER" shell "mkdir -p $EMU_VOICE" >/dev/null
  "$ADB" -s "$SER" push "$WORKSPACE/host2rig/midu/." "$EMU_VOICE" >/dev/null || exit 1
  echo "   设置页里选『自定义目录』并填：$EMU_VOICE"
fi

if [ "${PUSH_RIG:-0}" = 1 ] && [ -d "$WORKSPACE/host2rig" ]; then
  echo "== 推 host2 rig → $EMU_RIG（兼容后端用）"
  "$ADB" -s "$SER" push "$WORKSPACE/host2rig/." "$EMU_RIG" >/dev/null || exit 1
  "$ADB" -s "$SER" shell "chmod -R 755 $EMU_RIG"
fi

if [ "${SET_DEFAULT:-1}" = 1 ]; then
  echo "== 设为系统默认 TTS 引擎"
  "$ADB" -s "$SER" shell settings put secure tts_default_engine "$PKG"
  "$ADB" -s "$SER" shell settings get secure tts_default_engine
fi

echo "== 打开设置页（在里面点『冒烟自检』/『冒烟并播放』）"
"$ADB" -s "$SER" shell am start -a android.speech.tts.engine.CONFIGURE_ENGINE -p "$PKG" >/dev/null || \
"$ADB" -s "$SER" shell monkey -p "$PKG" 1 >/dev/null

echo "== logcat（Ctrl-C 结束）"
echo "   midu-bridge / midu-fakejni / BdTtsService / BdRuleUpdate / BdRemoteFile"
"$ADB" -s "$SER" logcat -v time -s midu-bridge:V midu-fakejni:V BdTtsService:V \
    BdRuleUpdate:V BdRemoteFile:V AndroidRuntime:E
