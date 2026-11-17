package com.bd.tts.rules;

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

    public static Result merge(String vendorText, String upstreamText, String userText) {
        return mergeOrdered(Arrays.asList(userText, upstreamText, vendorText));
    }

    /**
     * 任意数量的规则层：第 0 项优先级最高，最后一项优先级最低（vendor 种子）。
     *
     * 低层先当基底；逐层往上：本层的「移除 / @删除」先作用于下面所有层，
     * 本层「新增」同键就地覆盖低层（保序）、新键追加。等价于原来的
     * vendor / 上游 / 用户三层合并，但层数不再写死。
     */
    public static synchronized Result mergeOrdered(List<String> layers) {
        List<Parsed> parsed = new ArrayList<>();
        for (String t : layers) parsed.add(parse(t));
        if (parsed.isEmpty()) {
            return new Result(new ArrayList<>(), new ArrayList<>(), 0, 0, 0, 0);
        }

        List<Rule> kept = new ArrayList<>(parsed.get(parsed.size() - 1).adds);
        List<String> conflicts = new ArrayList<>();
        // 从倒数第二层往上走：每层都先删再加/覆盖。
        for (int i = parsed.size() - 2; i >= 0; i--) {
            Parsed layer = parsed.get(i);
            List<Rule> next = new ArrayList<>();
            for (Rule r : kept) {
                if (layer.removals.contains(r.canonical())) continue;
                if (layer.removeWords.contains(r.word)) continue;
                next.add(r);
            }
            Map<String, Integer> index = new LinkedHashMap<>();
            for (int k = 0; k < next.size(); k++) index.put(next.get(k).key(), k);
            for (Rule u : layer.adds) {
                Integer hit = index.get(u.key());
                if (hit != null) {
                    Rule old = next.get(hit);
                    conflicts.add("第 " + (i + 1) + " 层覆盖 " + u.word
                            + " [" + String.join(" ", u.conds) + "]  "
                            + String.join(" ", old.pron) + " → " + String.join(" ", u.pron));
                    next.set(hit, u);
                } else {
                    index.put(u.key(), next.size());
                    next.add(u);
                }
            }
            kept = next;
        }

        // 按规范化整行去重（保留首次出现 = 高优先级层的位置）。
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        for (Rule r : kept) {
            if (seen.add(r.canonical())) lines.add(r.raw);
        }
        int vendorCount = parsed.get(parsed.size() - 1).adds.size();
        int upstreamCount = parsed.size() >= 2 ? parsed.get(parsed.size() - 2).adds.size() : 0;
        int userCount = 0;
        for (int i = 0; i + 2 < parsed.size(); i++) userCount += parsed.get(i).adds.size();
        return new Result(lines, conflicts, vendorCount, upstreamCount, userCount, lines.size());
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
}
