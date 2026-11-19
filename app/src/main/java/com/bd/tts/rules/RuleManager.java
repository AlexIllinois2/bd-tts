package com.bd.tts.rules;

import android.content.Context;

import com.bd.tts.source.ResourceRoot;
import com.bd.tts.util.Io;
import com.bd.tts.util.Prefs;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 三份规则文件的落点、合并、原子生效与回滚。 */
public final class RuleManager {
    private RuleManager() {}

    public static final String ACTION_RULES_UPDATED = "com.bd.tts.RULES_UPDATED";

    public static File dir(Context c) { return new File(c.getFilesDir(), "rules"); }
    public static File vendorFile(Context c)   { return new File(dir(c), "vendor_unified_rule.txt"); }
    public static File upstreamFile(Context c) { return new File(dir(c), "upstream_unified_rule.txt"); }
    public static File userFile(Context c)     { return new File(dir(c), "user_rules.txt"); }
    public static File mergedFile(Context c)   { return new File(dir(c), "merged_unified_rule.txt"); }

    private static final String USER_HEADER =
            "# 用户自定义发音规则（优先级最高）\n" +
            "# 格式：字词<Tab>读音<Tab>条件...<Tab># 注释    例：行\thang2\tTEXT(雪中悍刀(行))\n" +
            "# 读音支持 hua4 / *5（只改声调） / none5（儿化占位）\n" +
            "# 删除低层规则：-<空格>规则行  或 用 移除: 区块；整词清空：@删除 字词\n\n";

    public static void ensureSeed(Context c) throws Exception {
        File d = Io.ensureDir(dir(c));
        File v = new File(d, "vendor_unified_rule.txt");
        if (!v.isFile() || v.length() == 0) {
            byte[] seed = Io.readAll(c.getAssets().open("rules/vendor_unified_rule.txt"));
            Io.atomicWrite(v, seed);
        }
        File u = new File(d, "user_rules.txt");
        if (!u.isFile()) Io.atomicWrite(u, USER_HEADER);
    }

    private static String textOrEmpty(File f) throws Exception {
        return f.isFile() ? Io.readUtf8(f) : "";
    }

    public static String userText(Context c) throws Exception {
        ensureSeed(c);
        return textOrEmpty(userFile(c));
    }
    public static void setUserText(Context c, String text) throws Exception {
        ensureSeed(c);
        Io.atomicWrite(userFile(c), text == null ? "" : text);
    }
    public static String vendorText(Context c) throws Exception {
        ensureSeed(c);
        return textOrEmpty(vendorFile(c));
    }
    public static String mergedText(Context c) throws Exception {
        return textOrEmpty(mergedFile(c));
    }

    /** 下载产物的唯一写入口：保留上一版用于回滚。 */
    public static void installUpstream(Context c, String rules) throws Exception {
        ensureSeed(c);
        File r = upstreamFile(c);
        if (r.isFile()) Io.atomicWrite(new File(dir(c), "upstream_unified_rule.prev"), Io.readUtf8(r));
        if (rules != null) Io.atomicWrite(r, rules);
    }

    public static boolean rollbackUpstream(Context c) throws Exception {
        File prev = new File(dir(c), "upstream_unified_rule.prev");
        if (!prev.isFile()) return false;
        Io.atomicWrite(upstreamFile(c), Io.readUtf8(prev));
        return true;
    }

    public static final String NO_RULES_SHA = "audiobook-no-rules";
    /** 听书专用：注释-only 的规则文件，引擎加载后等于没有任何发音规则。 */
    private static final String NO_RULES_TEXT =
            "# 听书模式：不使用任何规则文件，发音完全由 .tts 中的 [[字词|拼音]] 决定\n";

    public static final class Report {
        public final Rules.Result result;
        public final String sha;
        public final boolean changed;
        public final String enginePath;
        public final boolean disabled;
        public final int sourceCount;
        Report(Rules.Result r, String sha, boolean changed, String enginePath,
               boolean disabled, int sourceCount) {
            result = r; this.sha = sha; this.changed = changed; this.enginePath = enginePath;
            this.disabled = disabled; this.sourceCount = sourceCount;
        }
        public String summary() {
            if (disabled) return "规则已整体禁用（短路）· 各规则文件开关保持不变";
            return Rules.summary(result) + " · 规则文件 " + sourceCount + " 份"
                    + "  sha=" + sha.substring(0, Math.min(12, sha.length()));
        }
    }

    /** 合并「N 份规则文件 + 上游 + vendor」→ 原子写到引擎真正读的路径。 */
    public static synchronized Report apply(Context c) throws Exception {
        return apply(c, false);
    }

    /**
     * noRules=true 时只写一份「无规则」内容到引擎规则路径，**不读取、不改动**用户的
     * 三份规则文件及其开关；听书用它保证发音只来自 .tts 里的内联注音。
     */
    public static synchronized Report apply(Context c, boolean noRules) throws Exception {
        ensureSeed(c);
        Prefs prefs = Prefs.get(c);
        boolean disabled = !prefs.rulesEnabled();
        Rules.Result r;
        byte[] bytes;
        String sha;
        int sourceCount = 0;

        if (noRules) {
            r = Rules.mergeOrdered(Collections.<String>emptyList());
            bytes = NO_RULES_TEXT.getBytes(StandardCharsets.UTF_8);
            sha = NO_RULES_SHA;
        } else if (disabled) {
            // 一键短路：不碰任何规则文件自己的 enabled，只把引擎规则写成注释。
            r = Rules.mergeOrdered(Collections.<String>emptyList());
            bytes = "# 发音规则已整体禁用（短路），各规则文件开关保持不变\n"
                    .getBytes(StandardCharsets.UTF_8);
            sha = "rules-disabled";
        } else {
            List<RuleFiles.Source> sources = RuleFiles.list(c);
            List<String> layers = new ArrayList<>();   // 越靠前优先级越高
            for (RuleFiles.Source s : sources) {
                if (s.getEnabled()) layers.add(RuleFiles.text(c, s));
            }
            sourceCount = layers.size();
            layers.add(textOrEmpty(upstreamFile(c)));
            layers.add(textOrEmpty(vendorFile(c)));
            r = Rules.mergeOrdered(layers);
            bytes = r.text().getBytes(StandardCharsets.UTF_8);
            sha = Io.sha256(bytes);
        }

        Io.atomicWrite(mergedFile(c), bytes);
        Io.atomicWrite(new File(dir(c), "merge_report.txt"),
                (noRules ? "听书模式：不使用规则文件（发音只来自 .tts 注音）"
                        : disabled ? "规则已整体禁用（短路）" : Rules.summary(r))
                        + "\n文件数=" + sourceCount + "\nsha=" + sha + "\n\n"
                        + String.join("\n", r.conflicts) + "\n");

        File engine = new File(ResourceRoot.dir(c), ResourceRoot.RULE_REL);
        Io.ensureDir(engine.getParentFile());
        Io.atomicWrite(engine, bytes);

        File shaFile = new File(dir(c), "applied.sha");
        String old = shaFile.isFile() ? Io.readUtf8(shaFile).trim() : "";
        boolean changed = !old.equals(sha);
        Io.atomicWrite(shaFile, sha);
        return new Report(r, sha, changed, engine.getAbsolutePath(), disabled, sourceCount);
    }

    /** 更新任务/用户保存后调用：规则变了就换代数，EngineController 据此重建引擎。 */
    public static int generationAfterChange(Context c, boolean changed) {
        Prefs p = Prefs.get(c);
        return changed ? p.bumpGeneration() : p.generation();
    }
}
