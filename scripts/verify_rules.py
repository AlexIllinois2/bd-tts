#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线校验三层规则合并（与 app/src/main/java/.../Rules.java 同语义，纯 Python 版）。

  python android/scripts/verify_rules.py
      vendor = host2rig/midu/zh-cn/frontend/g2p/mnc/unified_rule.txt
      上游   = android/local_rules/upstream_unified_rule.txt   （若存在）
      补丁   = android/local_rules/upstream_rule_patch.txt     （若存在）
      用户   = android/local_rules/user_rules.txt              （若存在）
  python android/scripts/verify_rules.py --vendor A --upstream B --patch C --user D --out merged.txt
"""
import argparse
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
WORKSPACE = HERE.parent.parent
DEFAULT_VENDOR = WORKSPACE / "host2rig/midu/zh-cn/frontend/g2p/mnc/unified_rule.txt"
LOCAL = HERE.parent / "local_rules"

HEADERS = ("移除:", "新增:", "去重:", "分组:", "排序:", "表单定义:", "页面操作:")
PRON_RE = re.compile(r"^(?:\*\d+|none\d+|[a-zA-ZüÜ]+\d)$")


class Rule:
    def __init__(self, word, pron, conds, raw):
        self.word, self.pron, self.conds, self.raw = word, pron, conds, raw

    def key(self):
        return self.word + "\x01" + "\x02".join(self.conds)

    def canonical(self):
        return "\t".join([self.word] + self.pron + self.conds)


def strip_comment(line):
    depth = 0
    for i, ch in enumerate(line):
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth = max(0, depth - 1)
        elif ch == "#" and depth == 0 and (i == 0 or line[i - 1].isspace()):
            return line[:i]
    return line


def tokens(line):
    body = strip_comment(line).strip()
    if not body:
        return []
    parts = body.split()
    out, pending, depth = [], [], 0
    for p in parts:
        pending.append(p)
        depth += p.count("(") + p.count("[") - p.count(")") - p.count("]")
        if depth <= 0:
            out.append(" ".join(pending))
            pending, depth = [], 0
    if pending:
        out.append(" ".join(pending))
    return out


def parse_rule(raw):
    tk = tokens(raw.rstrip())
    if len(tk) < 2:
        return None
    word = tk[0]
    pron, conds = [], []
    i = 1
    while i < len(tk) and PRON_RE.match(tk[i]):
        pron.append(tk[i]); i += 1
    conds = tk[i:]
    if not pron:
        return None
    return Rule(word, pron, conds, raw.rstrip())


class Parsed:
    def __init__(self):
        self.adds, self.removals, self.remove_words, self.errors = [], set(), set(), []


def parse(text):
    out = Parsed()
    if not text:
        return out
    state = None
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        hit = next((h for h in HEADERS if s == h or s.startswith(h)), None)
        if hit:
            state = "remove" if hit == "移除:" else ("add" if hit == "新增:" else None)
            continue
        if state == "remove":
            r = parse_rule(line)
            out.removals.add(r.canonical() if r else s)
            continue
        if s.startswith("@删除"):
            w = s[3:].strip()
            if w:
                out.remove_words.add(w)
            continue
        if s.startswith("-"):
            rest = s[1:].strip()
            r = parse_rule(rest)
            out.removals.add(r.canonical() if r else rest)
            continue
        r = parse_rule(line)
        if r is None:
            out.errors.append("无法解析：" + s)
        else:
            out.adds.append(r)
    return out


def merge(vendor_text, upstream_text, patch_text, user_text):
    vendor, upstream, patch, user = map(parse, (vendor_text, upstream_text, patch_text, user_text))
    blocked = patch.removals | user.removals

    base = list(upstream.adds) if upstream.adds else list(vendor.adds)
    keys = {r.key() for r in base}
    for r in vendor.adds:
        if r.key() in keys or r.canonical() in blocked:
            continue
        base.append(r); keys.add(r.key())

    kept = [r for r in base
            if r.canonical() not in blocked and r.word not in user.remove_words]

    conflicts = []
    for u in user.adds:
        hit = next((i for i, r in enumerate(kept) if r.key() == u.key()), -1)
        if hit >= 0:
            old = kept[hit]
            conflicts.append(f"用户覆盖 {u.word} [{' '.join(u.conds)}]  "
                             f"{' '.join(old.pron)} → {' '.join(u.pron)}")
            kept[hit] = u
        else:
            kept.append(u)

    seen, lines = set(), []
    for r in kept:
        if r.canonical() not in seen:
            seen.add(r.canonical()); lines.append(r.raw)
    return lines, conflicts, (len(vendor.adds), len(upstream.adds), len(user.adds), len(lines))


def read(p):
    f = Path(p) if p else None
    return f.read_text(encoding="utf-8") if f is not None and f.is_file() else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vendor", default=str(DEFAULT_VENDOR))
    ap.add_argument("--upstream", default=str(LOCAL / "upstream_unified_rule.txt"))
    ap.add_argument("--patch", default=str(LOCAL / "upstream_rule_patch.txt"))
    ap.add_argument("--user", default=str(LOCAL / "user_rules.txt"))
    ap.add_argument("--out", default="")
    a = ap.parse_args()

    lines, conflicts, counts = merge(read(a.vendor), read(a.upstream), read(a.patch), read(a.user))
    print(f"vendor={counts[0]} 上游={counts[1]} 用户={counts[2]} 最终={counts[3]} 冲突={len(conflicts)}")
    for c in conflicts[:30]:
        print("  " + c)
    if conflicts and len(conflicts) > 30:
        print(f"  ... 另有 {len(conflicts)-30} 条")
    text = "\n".join(lines) + "\n"
    if a.out:
        Path(a.out).write_text(text, encoding="utf-8")
        print("已写", a.out)
    else:
        print("-- 前 15 行 --")
        for l in lines[:15]:
            print(l)


if __name__ == "__main__":
    main()
