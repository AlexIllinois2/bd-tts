package com.bd.tts.source;

import android.content.Context;

import com.bd.tts.util.Io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成「有效资源根」：本 app 自己的 files/midu_effective/。
 * 布局必须和 rig 的 midu/ 一致，因为 tts_off_resource_path 指的就是这里：
 *   speech_license.licbag
 *   zh-cn/{ptl.dat,ptl.idx,frontend/g2p/eng/additional_dict_path.txt,
 *          frontend/g2p/mnc/unified_rule.txt}   ← 规则文件由 RuleManager 写
 *   <VOICE>/{ptl.dat,ptl.idx}
 *   volcano_speech_tracking_log/
 */
public final class ResourceRoot {
    private ResourceRoot() {}

    public static final String BASE = "zh-cn";
    public static final String RULE_REL = BASE + "/frontend/g2p/mnc/unified_rule.txt";
    public static final String ENG_DICT_REL = BASE + "/frontend/g2p/eng/additional_dict_path.txt";

    public static File dir(Context c) {
        return new File(c.getFilesDir(), "midu_effective");
    }

    public static final class Prepare {
        public final File root;
        public final String[] voices;
        public final String sourceId;
        public final List<String> notes = new ArrayList<>();
        Prepare(File r, String[] v, String s) { root = r; voices = v; sourceId = s; }
    }

    /**
     * 按决策树解析出 VoiceSource（失败抛带可读原因的异常）：
     *   MultiTTS + Shizuku → 从安装的 MultiTTS 自动提取 so + 语音包；
     *   其余情况 → 本地来源：用户选的 so（apk/zip/.so）+ 语音包 zip 或文件夹。
     */
    public static VoiceSource resolve(Context c, String voice) throws Exception {
        Provisioner.Provision prov = Provisioner.ensure(c, voice);
        return prov.source;
    }

    /** 把来源里的大件增量镜像到有效根；只镜像当前选中的音色，避免复制一整套语音包。 */
    public static Prepare prepare(Context c, String voice) throws Exception {
        VoiceSource src = resolve(c, voice);
        File root = Io.ensureDir(dir(c));
        Io.ensureDir(new File(root, BASE + "/frontend/g2p/mnc"));
        Io.ensureDir(new File(root, BASE + "/frontend/g2p/eng"));
        Io.ensureDir(new File(root, "volcano_speech_tracking_log"));
        Io.ensureDir(new File(root, voice));

        Prepare out = new Prepare(root, src.listVoices(), src.id());
        if (out.voices.length == 0) out.notes.add("来源里没找到任何音色包（缺 ptl.dat？）");

        File license = new File(root, "speech_license.licbag");
        boolean licOk = copy(c, src, "speech_license.licbag", license, out.notes, false);
        if (!licOk) {
            throw new IllegalStateException("缺少 speech_license.licbag：所选来源里没有。"
                    + "MultiTTS 用户请确认已在 MultiTTS 里下载离线语音包；"
                    + "手动模式请选择包含它的语音包 zip 或文件夹");
        }
        copy(c, src, BASE + "/ptl.dat", new File(root, BASE + "/ptl.dat"), out.notes, true);
        copy(c, src, BASE + "/ptl.idx", new File(root, BASE + "/ptl.idx"), out.notes, true);
        copy(c, src, voice + "/ptl.dat", new File(root, voice + "/ptl.dat"), out.notes, true);
        copy(c, src, voice + "/ptl.idx", new File(root, voice + "/ptl.idx"), out.notes, true);

        // 英文附加词典：来源没有就用 rig 的缺省内容（20 条常见缩写）。
        File dict = new File(root, ENG_DICT_REL);
        boolean copied = copy(c, src, ENG_DICT_REL, dict, out.notes, false);
        if (!copied) Io.atomicWrite(dict, DEFAULT_ENG_DICT);

        src.close();
        return out;
    }

    private static boolean copy(Context c, VoiceSource src, String rel, File dst,
                                List<String> notes, boolean required) throws Exception {
        String fp;
        try { fp = src.fingerprint(rel); }
        catch (Exception e) { fp = "-1:0"; }
        File stamp = new File(dst.getParentFile(), dst.getName() + ".stamp");
        boolean fresh = dst.isFile() && dst.length() > 0 && stamp.isFile()
                && Io.readUtf8(stamp).trim().equals(fp);
        if (fresh) return true;
        if (fp.startsWith("-1:")) {
            if (required) throw new IllegalStateException("语音来源缺少必需文件：" + rel
                    + "\n来源=" + src.id());
            notes.add("缺 " + rel + "，用内置缺省");
            return false;
        }
        File tmp = new File(dst.getParentFile(), dst.getName() + ".dl");
        try (InputStream in = src.open(rel); FileOutputStream os = new FileOutputStream(tmp, false)) {
            Io.copy(in, os);
            os.getFD().sync();
        }
        if (!tmp.renameTo(dst)) throw new IllegalStateException("rename 失败 " + rel);
        Io.atomicWrite(stamp, fp);
        notes.add("同步 " + rel + " (" + dst.length() + " B)");
        return true;
    }

    /** rig 里 zh-cn/frontend/g2p/eng/additional_dict_path.txt 的缺省内容。 */
    private static final String DEFAULT_ENG_DICT =
            "wifi\tW AY1 . F AY0\n" +
            "WiFi\tW AY1 . F AY0\n" +
            "WIFI\tW AY1 . F AY0\n" +
            "i'm\tAY1 M\n" +
            "he's\tHH IY1 Z\n" +
            "she's\tSH IY1 Z\n" +
            "it's\tIH1 T S\n" +
            "they're\tDH EHR1\n" +
            "don't\tD OW1 N T\n" +
            "doesn't\tD AH1 . Z AH0 N T\n" +
            "let's\tL EH1 T S\n" +
            "what's\tW AO1 T S\n" +
            "where's\tW EH1 R Z\n" +
            "who's\tHH UW1 Z\n" +
            "how's\tHH AW1 Z\n" +
            "when's\tW EH1 N Z\n" +
            "can't\tK AA1 N T\n" +
            "best\tB EH1 S T\n" +
            "interest\tIH1 N . T R AX0 S T\n" +
            "us\tAH1 Z\n";
}
