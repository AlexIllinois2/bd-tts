#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把自研 shim 与规则种子同步进 Android 工程（只在构建机本地执行，不入库）。

APK 不再自带厂商文件（libspeechengine.so / speech_license.licbag / 语音包）：
这些由用户在 app 里选择自己保存的文件夹提供；so 会由 app 复制到自己的
数据目录再 dlopen。本脚本只负责：

  python android/scripts/prepare_vendor.py                 # 同步 shim + 规则种子
  python android/scripts/prepare_vendor.py --verify        # 只按 MANIFEST.md5 校验原件，不复制
  python android/scripts/prepare_vendor.py --clean         # 清掉同步出来的产物（不动 rig 原件）

--no-data 仍被接受但已是默认行为（为旧命令行保留）。

合规：厂商 .so / ptl.dat / licbag 均为你设备上合法持有的原件，app 只做本地镜像；
      厂商原件不入库、不随 APK 分发。
"""
import argparse
import hashlib
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANDROID = HERE.parent
WORKSPACE = ANDROID.parent
RIG = WORKSPACE / "host2rig"
MANIFEST = WORKSPACE / "MANIFEST.md5"
APP = ANDROID / "app" / "src" / "main"
LIBS = APP / "jniLibs" / "arm64-v8a"
ASSETS_RULES = APP / "assets" / "rules"

VENDOR_RULE_REL = "zh-cn/frontend/g2p/mnc/unified_rule.txt"
# 自研 shim + NDK 运行时：APK 需要自带（厂商 so 闭包之外的其余文件都来自用户目录）
SHIM_NAMES = ["cronet_shim4.so", "icu_shim.so", "log_shim.so", "libc++_shared.so"]


def md5(p: Path) -> str:
    h = hashlib.md5()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_manifest() -> int:
    if not MANIFEST.is_file():
        print("× 找不到 MANIFEST.md5")
        return 1
    bad = 0
    for line in MANIFEST.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        want, rel = line.split(None, 1)
        rel = rel.lstrip("./")
        p = WORKSPACE / rel
        if not p.is_file():
            print("× 缺文件", rel)
            bad += 1
            continue
        got = md5(p)
        ok = got == want
        print(("✓ " if ok else "× ") + rel + ("  " + got if not ok else ""))
        if not ok:
            bad += 1
    print("== 原件校验：" + ("全部一致" if bad == 0 else f"{bad} 枚不一致"))
    return 0 if bad == 0 else 1


def copy_file(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-data", action="store_true",
                    help="已废弃：APK 永远不打包厂商数据，保留只为兼容旧命令行")
    ap.add_argument("--verify", action="store_true", help="只校验 rig 原件 md5")
    ap.add_argument("--clean", action="store_true", help="删除同步产物")
    args = ap.parse_args()

    if args.verify:
        return verify_manifest()

    if args.clean:
        if LIBS.exists():
            for so in LIBS.glob("*.so"):
                if so.name in SHIM_NAMES:
                    so.unlink()
                    print("已删", so)
        if ASSETS_RULES.exists():
            shutil.rmtree(ASSETS_RULES)
            print("已删", ASSETS_RULES)
        return 0

    if not RIG.is_dir():
        print("× 找不到", RIG)
        return 1

    LIBS.mkdir(parents=True, exist_ok=True)
    for name in SHIM_NAMES:
        src = RIG / "lib" / name
        if src.is_file():
            copy_file(src, LIBS / name)
            print("== jniLibs ←", name, f"({src.stat().st_size} B)")
        elif (LIBS / name).is_file():
            print("== rig 里没有", name, "，保留工程现有副本")
        else:
            print("× 缺", name, "（rig/lib 与工程都没有，进程内/host2 会缺 shim）")

    ASSETS_RULES.mkdir(parents=True, exist_ok=True)
    vendor_rule = RIG / "midu" / VENDOR_RULE_REL
    copy_file(vendor_rule, ASSETS_RULES / "vendor_unified_rule.txt")
    print(f"== assets/rules/vendor_unified_rule.txt ← {md5(vendor_rule)} "
          f"({vendor_rule.stat().st_size} B)")
    print("== 完成：厂商 so / licbag / 语音包不再打包，运行期由用户目录提供")
    return 0


if __name__ == "__main__":
    sys.exit(main())
