#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 host2rig/ 里的原件同步进 Android 工程（只在构建机本地执行，不入库）。

  python android/scripts/prepare_vendor.py                 # 全量：so + 语音包 + 规则
  python android/scripts/prepare_vendor.py --no-data      # 轻量：只 so + 规则（用 Shizuku/自选目录取语音包）
  python android/scripts/prepare_vendor.py --verify       # 只按 MANIFEST.md5 校验原件，不复制
  python android/scripts/prepare_vendor.py --clean        # 清掉同步出来的产物（不动 rig 原件）

合规：厂商 .so / ptl.dat / licbag 均为你设备上合法持有的原件，本脚本只做本地复制，
      产物已写入 .gitignore；不要随源码或 APK 对外分发。
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
ASSETS_MIDU = APP / "assets" / "midu"
ASSETS_RULES = APP / "assets" / "rules"

VENDOR_RULE_REL = "zh-cn/frontend/g2p/mnc/unified_rule.txt"
SKIP_NAMES = {"speech_license_padded.licbag"}  # rig 实测引擎不吃，见 README §0.6


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
    ap.add_argument("--no-data", action="store_true", help="不打包大语音包（只保留规则与 so）")
    ap.add_argument("--verify", action="store_true", help="只校验 rig 原件 md5")
    ap.add_argument("--clean", action="store_true", help="删除同步产物")
    args = ap.parse_args()

    if args.verify:
        return verify_manifest()

    if args.clean:
        for d in (LIBS, ASSETS_MIDU, ASSETS_RULES):
            if d.exists():
                shutil.rmtree(d)
                print("已删", d)
        return 0

    if not RIG.is_dir():
        print("× 找不到", RIG)
        return 1

    # 先证明原件没被动过
    rc = verify_manifest()
    if rc != 0:
        print("× rig 原件与 MANIFEST.md5 不符，拒绝同步（避免拿改过的库去构建）")
        return rc

    LIBS.mkdir(parents=True, exist_ok=True)
    n_so = 0
    for so in sorted((RIG / "lib").glob("*.so")):
        copy_file(so, LIBS / so.name)
        n_so += 1
    print(f"== jniLibs/arm64-v8a/ ← {n_so} 枚 .so（厂商 8 + 我方 3 枚 shim）")

    ASSETS_RULES.mkdir(parents=True, exist_ok=True)
    vendor_rule = RIG / "midu" / VENDOR_RULE_REL
    copy_file(vendor_rule, ASSETS_RULES / "vendor_unified_rule.txt")
    print(f"== assets/rules/vendor_unified_rule.txt ← {md5(vendor_rule)} "
          f"({vendor_rule.stat().st_size} B)")

    if args.no_data:
        print("== --no-data：跳过语音包；引擎数据请用 Shizuku / 自选目录提供")
        return 0

    ASSETS_MIDU.mkdir(parents=True, exist_ok=True)
    n = 0
    voices = []
    for f in sorted((RIG / "midu").rglob("*")):
        if not f.is_file() or f.name in SKIP_NAMES:
            continue
        rel = f.relative_to(RIG / "midu")
        copy_file(f, ASSETS_MIDU / rel)
        n += 1
    for d in sorted((RIG / "midu").iterdir()):
        if d.is_dir() and (d / "ptl.dat").is_file() and d.name != "zh-cn":
            voices.append(d.name)
    (ASSETS_MIDU / "voices.txt").write_text("\n".join(voices) + "\n", encoding="utf-8")
    print(f"== assets/midu/ ← {n} 枚资源；音色目录：{voices}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
