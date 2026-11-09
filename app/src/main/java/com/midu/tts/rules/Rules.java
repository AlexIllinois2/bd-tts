package com.midu.tts.rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * unified_rule.txt 的解析 / 三层合并。
 *
 * 行格式（与 rig 的 vendor 文件、midu_unified_rule 的 conf/unified_rule.txt 兼容）：
 *   <字词>\t<读音1> [<读音2> ...]\t<条件1>\t<条件2>...\t[# 注释]
 * 读音：hua4 / *5 / none5；条件：TEXT(..) PINYIN(..) POS(..) WORD_INSIDE(..) !WORD_INSIDE(..)
 *
 * 冲突键 = 字词 + 条件集合（不含读音）：同一语境下的读音以高优先级为准。
 * 优先级：用户自定义 > midu_unified_rule 产物 > vendor。
 */
public final class Rules {
    private Rules() {}

    private static final String[] HEADERS = {
            "移除:", "新增:", "去重:", "分组:", "排序:", "表单定义:", "页面操作:"
    };

    public static final class Rule {
        public final String word;
        public final List<String> pron;
        public final List<String> conds;
        public final String comment;
        public final String raw;

        Rule(String w, List<String> p, List<String> c, String cm, String r) {
            word = w; pron = p; conds = c; comment = cm; raw = r;
        }
        /** 冲突键：同字词 + 同条件 = 同一条，读音可被高层覆盖。 */
        public String key() {
            return word + "\u0001" + String.join("\u0002", conds);
        }
        /** 规范化整行（tab/空格等价），用于「移除」精确匹配与去重。 */
        public String canonical() {
            StringBuilder sb = new StringBuilder(word);
            for (String p : pron) sb.append('\t').append(p);
            for (String c : conds) sb.append('\t').append(c);
            return sb.toString();
        }
    }

    public static final class Parsed {
        public final List<Rule> adds = new ArrayList<>();
        public final Set<String> removals = new LinkedHashSet<>();  // canonical 行
        public final Set<String> removeWords = new LinkedHashSet<>();
        public final List<String> errors = new ArrayList<>();
    }

    public static final class Result {
        public final List<String> lines;
        public final List<String> conflicts;
        public final int vendorCount, upstreamCount, userCount, finalCount;
        Result(List<String> l, List<String> c, int v, int u, int us, int f) {
            lines = l; conflicts = c; vendorCount = v; upstreamCount = u;
            userCount = us; finalCount = f;
        }
        public String text() { return String.join("\n", lines) + "\n"; }
    }

    // ---------------------------------------------------------------- 解析

    private static int balance(String s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '[') d++;
            else if (ch == ')' || ch == ']') d--;
        }
        return d;
    }

    /** 去掉行内注释（括号/方括号内的 # 不算注释）。 */
    private static String stripComment(String line) {
        int depth = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '(' || ch == '[') depth++;
            else if (ch == ')' || ch == ']') depth = Math.max(0, depth - 1);
            else if (ch == '#' && depth == 0 && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static List<String> tokens(String line) {
        String body = stripComment(line).trim();
        if (body.isEmpty()) return new ArrayList<>();
        String[] parts = body.split("[ \\t]+");
        List<String> out = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        int depth = 0;
        for (String p : parts) {
            if (pending.length() > 0) pending.append(' ');
            pending.append(p);
            depth += balance(p);
            if (depth <= 0) {
                out.add(pending.toString());
                pending.setLength(0);
                depth = 0;
            }
        }
        if (pending.length() > 0) out.add(pending.toString());
        return out;
    }

    private static boolean isPron(String t) {
        if (t == null || t.isEmpty()) return false;
        if (t.matches("\\*\\d+|none\\d+")) return true;
        if (t.matches("[a-zA-ZüÜ]+[0-9]")) return true;
        return false;
    }

    private static Rule parseRule(String rawLine, Parsed errs) {
        String raw = rawLine.replaceAll("\\s+$", "");
        List<String> tk = tokens(raw);
        if (tk.size() < 2) {
            errs.errors.add("无法解析（至少两个字串）：" + raw);
            return null;
        }
        String word = tk.get(0);
        List<String> pron = new ArrayList<>();
        List<String> conds = new ArrayList<>();
        int i = 1;
        while (i < tk.size() && isPron(tk.get(i))) { pron.add(tk.get(i)); i++; }
        for (; i < tk.size(); i++) conds.add(tk.get(i));
        if (pron.isEmpty()) {
            errs.errors.add("缺读音列：" + raw);
            return null;
        }
        String comment = "";
        int hash = -1;
        {
            int depth = 0;
            for (int k = 0; k < raw.length(); k++) {
                char ch = raw.charAt(k);
                if (ch == '(' || ch == '[') depth++;
                else if (ch == ')' || ch == ']') depth = Math.max(0, depth - 1);
                else if (ch == '#' && depth == 0) { hash = k; break; }
            }
        }
        if (hash >= 0) comment = raw.substring(hash).trim();
        return new Rule(word, pron, conds, comment, raw);
    }

    private static String headerOf(String s) {
        for (String h : HEADERS) if (s.equals(h) || s.startsWith(h)) return h;
        return null;
    }

    public static Parsed parse(String text) {
        Parsed out = new Parsed();
        if (text == null) return out;
        String state = null;   // "remove" / "add" / null
        for (String line : text.split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String h = headerOf(s);
            if (h != null) {
                if (h.equals("移除:")) state = "remove";
                else if (h.equals("新增:")) state = "add";
                else state = null;
                continue;
            }
            if (state != null && state.equals("remove")) {
                Rule r = parseRule(line, new Parsed());
                out.removals.add(r != null ? r.canonical() : s);
                continue;
            }
            if (s.startsWith("@删除")) {
                String w = s.substring(3).trim();
                if (!w.isEmpty()) out.removeWords.add(w);
                continue;
            }
            if (s.startsWith("-")) {
                String rest = s.substring(1).trim();
                Rule r = parseRule(rest, new Parsed());
                out.removals.add(r != null ? r.canonical() : rest);
                continue;
            }
            Rule r = parseRule(line, out);
            if (r != null) out.adds.add(r);
        }
        return out;
    }

    // ---------------------------------------------------------------- 合并

    public static Result merge(String vendorText, String upstreamText,
                               String patchText, String userText) {
        Parsed vendor = parse(vendorText);
        Parsed upstream = parse(upstreamText);
        Parsed patch = parse(patchText);
        Parsed user = parse(userText);

        Set<String> blocked = new HashSet<>();
        blocked.addAll(patch.removals);
        blocked.addAll(user.removals);

        List<Rule> base = new ArrayList<>();
        // 1) 上游产物是「vendor + 补丁」整理后的完整有序结果，优先作为基底，
        //    这样能保留上游 organizer.ts 的分组/排序，也天然继承它的「移除」语义。
        if (!upstream.adds.isEmpty()) base.addAll(upstream.adds);
        else base.addAll(vendor.adds);

        // 2) 补回 vendor 里上游没有的条目（换过厂商语音包时有用），但上游移除过的不补。
        Map<String, Rule> keys = new LinkedHashMap<>();
        for (Rule r : base) keys.put(r.key(), r);
        for (Rule r : vendor.adds) {
            if (keys.containsKey(r.key())) continue;
            if (blocked.contains(r.canonical())) continue;
            base.add(r);
            keys.put(r.key(), r);
        }

        // 3) 用户移除：精确行 + 整词删除，作用于所有低层。
        List<Rule> kept = new ArrayList<>();
        for (Rule r : base) {
            if (blocked.contains(r.canonical())) continue;
            if (user.removeWords.contains(r.word)) continue;
            kept.add(r);
        }

        // 4) 用户自定义：同键就地覆盖（保序），新键追加。
        List<String> conflicts = new ArrayList<>();
        for (Rule u : user.adds) {
            int hit = -1;
            for (int i = 0; i < kept.size(); i++) {
                if (kept.get(i).key().equals(u.key())) { hit = i; break; }
            }
            if (hit >= 0) {
                Rule old = kept.get(hit);
                conflicts.add("用户覆盖 " + u.word + " [" + String.join(" ", u.conds) + "]  "
                        + String.join(" ", old.pron) + " → " + String.join(" ", u.pron));
                kept.set(hit, u);
            } else {
                kept.add(u);
            }
        }

        // 5) 按规范化整行去重（保留首次出现）。
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        for (Rule r : kept) {
            if (seen.add(r.canonical())) lines.add(r.raw);
        }
        return new Result(lines, conflicts,
                vendor.adds.size(), upstream.adds.size(), user.adds.size(), lines.size());
    }

    /** 下载产物落盘前的合法性闸：不能把坏文件当成规则集。 */
    public static boolean looksValid(String text, int minRules) {
        if (text == null || text.length() < 64 || text.length() > 4 * 1024 * 1024) return false;
        if (text.indexOf('\0') >= 0) return false;
        Parsed p = parse(text);
        return p.adds.size() >= minRules && p.errors.size() < p.adds.size() / 4;
    }

    public static String summary(Result r) {
        return "vendor=" + r.vendorCount + " 上游=" + r.upstreamCount
                + " 用户=" + r.userCount + " 最终=" + r.finalCount
                + " 冲突=" + r.conflicts.size();
    }

    /** 上游 conf/rule_patch.txt 的移除区，单独抽出来看（诊断用）。 */
    public static List<String> removalLines(String patchText) {
        return new ArrayList<>(parse(patchText).removals);
    }
}
