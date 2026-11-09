package com.midu.tts.source;

import android.content.Context;

import com.midu.tts.shizuku.ShizukuClient;
import com.midu.tts.util.Io;
import com.midu.tts.util.Prefs;

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

    /** 按设置解析出 VoiceSource；失败抛带可读原因的异常。 */
    public static VoiceSource resolve(Context c) throws Exception {
        Prefs p = Prefs.get(c);
        switch (p.source()) {
            case Prefs.SOURCE_DIRECTORY:
                String path = p.customPath();
                if (path == null || path.isEmpty())
                    throw new IllegalStateException("未设置离线语音目录（先选目录或填路径）");
                return new VoiceSources.Directory(c, path);
            case Prefs.SOURCE_SHIZUKU:
                com.midu.tts.IRemoteFileService svc = ShizukuClient.acquire(c, 8000);
                if (svc == null)
                    throw new IllegalStateException("Shizuku 不可用：" + ShizukuClient.error()
                            + "\n请在设置里点「检测 / 授权 Shizuku」");
                return new ShizukuVoiceSource(p.multittsRoot(), svc);
            case Prefs.SOURCE_BUNDLED:
            default:
                return new VoiceSources.Bundled(c);
        }
    }

    /** 把来源里的大件增量镜像到有效根；只镜像当前选中的音色，避免复制一整套语音包。 */
    public static Prepare prepare(Context c, String voice) throws Exception {
        VoiceSource src = resolve(c);
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
            // MultiTTS 的目录里未必带licbag；内置包可以作为兜底（--no-data 时就没有）。
            try (InputStream in = c.getAssets().open("midu/speech_license.licbag")) {
                File tmp = new File(root, "speech_license.licbag.dl");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(tmp, false)) {
                    Io.copy(in, os);
                }
                if (!tmp.renameTo(license)) throw new IllegalStateException("rename licbag 失败");
                Io.atomicWrite(new File(root, "speech_license.licbag.stamp"), "bundled-fallback");
                out.notes.add("licbag 用内置兜底");
            } catch (Exception e) {
                throw new IllegalStateException("缺 speech_license.licbag：所选来源没有，内置包也没有"
                        + "（若用 --no-data 构建，请把 rig 的 licbag 放进语音目录）", e);
            }
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
