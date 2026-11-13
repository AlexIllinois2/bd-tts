package com.bd.tts.source;

import android.content.Context;
import android.net.Uri;

import com.bd.tts.IRemoteFileService;
import com.bd.tts.shizuku.ShizukuClient;
import com.bd.tts.util.Io;
import com.bd.tts.util.Prefs;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

import androidx.documentfile.provider.DocumentFile;

/**
 * 厂商文件供给器：libspeechengine.so 与语音包的获取决策树。
 *
 *   有 MultiTTS + 有 Shizuku：直接从安装的 MultiTTS apk 提取 so（base + arm64 split），
 *       语音包优先取 apk 内嵌包（按需解压 rig/midu），没有则直读其数据目录
 *       Android/data/org.nobody.multitts/.../midu。
 *   有 MultiTTS 无 Shizuku：先尝试直接读安装的 apk（低版本 / 部分 ROM 可行），
 *       读不动让用户手动选 apk / so；语音包由用户本地选 zip 或文件夹。
 *   无 MultiTTS：so 由用户本地选（apk / zip / 一个或多个 .so / 文件夹）；
 *       icu_shim / libc++_shared 等可公开、无版权风险的库已随本 APK 内置；
 *       语音包由用户本地选 zip 或文件夹。
 *
 * 目录形态：文件夹本身（或一层子目录）是 midu 资源根（有 speech_license.licbag）→
 * 直接当来源；Shizuku 在场时还支持文件夹里放 zip/apk 按需解压到 rig。
 *
 * rig/midu 既是 host2 的资源根（ROOT=rig/midu），也是进程内 prepare 的来源。
 * 无 Shizuku 时 host2 不可用，进程内 JNI 直接从 files/midu_native dlopen。
 */
public final class Provisioner {
    private Provisioner() {}

    private static final String TAG = "BdProvision";

    /** 厂商 so 闭包（不含自研 shim 与 libc++_shared：那两个 APK 自带）。 */
    public static final String[] VENDOR_SOS = {
            "libspeechengine.so", "libsscronet.so", "libttboringssl.so", "libttcrypto.so",
            "libicuuc.so", "libicui18n.so", "libspeexdsp.so"
    };

    /** APK 自带、需要推到 rig/lib 的库。 */
    public static final String[] SHIP_LIBS = {
            "icu_shim.so", "cronet_shim4.so", "log_shim.so", "libc++_shared.so"
    };

    private static final String PKG_STAMP = ".pkg_stamp";

    private static volatile long sLastEnsureMs = 0L;
    private static volatile String sLastKey = "";
    private static volatile Provision sLast = null;

    /** 一次供给的结果：来源 + 过程说明。失败抛异常。 */
    public static final class Provision {
        public VoiceSource source;
        public final List<String> notes = new ArrayList<>();
    }

    /** 进程内 JNI 的 so 搜索路径：先 app 数据目录（厂商 so），再 nativeLibraryDir（shim）。 */
    public static String libDir(Context c) {
        return nativeDir(c).getAbsolutePath() + ":"
                + c.getApplicationInfo().nativeLibraryDir;
    }

    public static File nativeDir(Context c) {
        return new File(c.getFilesDir(), "midu_native");
    }

    /** 供给缓存键：音色 + 来源相关偏好，任一变化立即重新供给。 */
    private static String cacheKey(Context c, String voice) {
        Prefs p = Prefs.get(c);
        return voice + "|" + p.customPath() + "|" + p.soPath() + "|" + p.multittsRoot();
    }

    /**
     * 确保资源就位并返回语音来源。合成路径高频调用：
     * 60 秒内同键且当前音色的 ptl.dat 还在 → 直接用缓存。
     */
    public static Provision ensure(Context c, String voice) throws Exception {
        Provision cached = sLast;
        long now = System.currentTimeMillis();
        if (cached != null && cacheKey(c, voice).equals(sLastKey)
                && now - sLastEnsureMs < 60_000L && cached.source != null) {
            try {
                IRemoteFileService svc = ShizukuClient.acquire(c, 1500);
                if (svc != null && voicePresent(cached.source, svc, voice)) return cached;
                if (svc == null && !(cached.source instanceof ShizukuVoiceSource)) return cached;
            } catch (Exception ignored) { }
        }
        synchronized (Provisioner.class) {
            cached = sLast;
            if (cached != null && cacheKey(c, voice).equals(sLastKey)
                    && System.currentTimeMillis() - sLastEnsureMs < 60_000L) {
                try {
                    IRemoteFileService svc = ShizukuClient.acquire(c, 1500);
                    if (svc != null && voicePresent(cached.source, svc, voice)) return cached;
                    if (svc == null && !(cached.source instanceof ShizukuVoiceSource)) return cached;
                } catch (Exception ignored) { }
            }
            Provision p = ensureSlow(c, voice);
            // 统一在供给入口打印决策树过程：进程内 JNI 与 host2 后端两条路径都调用 ensure，
            // 之前只有 ResourceRoot.resolve 打印，host2 路径的决策过程完全看不见。
            for (String n : p.notes) android.util.Log.i(TAG, n);
            sLast = p;
            sLastKey = cacheKey(c, voice);
            sLastEnsureMs = System.currentTimeMillis();
            return p;
        }
    }

    /** 快速路径的完整性检查：当前音色的 ptl.dat 仍可读。 */
    private static boolean voicePresent(VoiceSource src, IRemoteFileService svc, String voice)
            throws Exception {
        try {
            if (src instanceof ShizukuVoiceSource) {
                String st = svc.stat(((ShizukuVoiceSource) src).root() + "/" + voice + "/ptl.dat");
                return !st.startsWith("-1:");
            }
            return true;   // 本地来源交给 prepare 自己校验
        } catch (Exception e) {
            return false;
        }
    }

    private static Provision ensureSlow(Context c, String voice) throws Exception {
        Provision out = new Provision();
        Prefs p = Prefs.get(c);
        IRemoteFileService svc = ShizukuClient.acquire(c, 8000);
        String rig = p.host2Rig().replaceAll("/+$", "");

        if (svc != null) {
            if (MultiTts.installed(c)) {
                provisionInstalledMultiTts(c, p, svc, rig, voice, out);
            } else {
                provisionFromCustomFolder(c, p, svc, rig, voice, out);
            }
            return out;
        }
        provisionLocal(c, p, voice, out);
        return out;
    }

    // -------------------------------------------------- A) MultiTTS + Shizuku

    /** 全自动：so 与语音包都从安装的 MultiTTS 提取，用户不用选任何东西。 */
    private static void provisionInstalledMultiTts(Context c, Prefs p, IRemoteFileService svc,
                                                   String rig, String voice, Provision out)
            throws Exception {
        String[] apks = MultiTts.apkPaths(c);
        if (apks.length == 0)
            throw new IllegalStateException("MultiTTS 已安装但拿不到 apk 路径（包可见性？）");

        boolean soDone = false;
        String licZip = null, licPrefix = null;
        String[] voices = null;
        String licStamp = null;
        for (String apk : apks) {
            String[] info = svc.zipInfo(apk);
            String lic = "", libs = "", vlist = "";
            for (String kv : info) {
                if (kv.startsWith("licbag_prefix=")) lic = kv.substring(14);
                else if (kv.startsWith("libs=")) libs = kv.substring(5);
                else if (kv.startsWith("voices=")) vlist = kv.substring(7);
            }
            boolean hasLic = !lic.isEmpty() && !lic.equals("-");
            boolean hasLib = libs.contains("libspeechengine.so");
            if (hasLib && !soDone) {
                ensureSoUnzip(svc, apk, libs.split(","), rig, out.notes);
                soDone = true;
            }
            if (hasLic && licZip == null) {
                licZip = apk;
                licPrefix = lic;
                licStamp = svc.stat(apk);
                voices = vlist.isEmpty() ? new String[0] : vlist.split(",");
            }
        }
        if (!soDone) out.notes.add("× MultiTTS apk 里没找到 lib/arm64-v8a/*.so");

        pushShipLibs(c, svc, rig, out.notes);
        mirrorVendorSo(c, svc, rig, out.notes);

        if (licZip != null) {
            ensurePkgUnzip(svc, licZip, licPrefix, licStamp, rig, voice, voices, out.notes);
            out.notes.add("语音包来自 MultiTTS apk [" + licPrefix + "]（按需解压到 rig/midu）");
            out.source = new ShizukuVoiceSource(rig + "/midu", svc);
            return;
        }
        // apk 不内嵌语音包：直读它的数据目录（MultiTTS 自行下载的离线包）。
        String root = p.multittsRoot().replaceAll("/+$", "");
        if (svc.stat(root + "/speech_license.licbag").startsWith("-1:"))
            throw new IllegalStateException("MultiTTS apk 里没有语音包，且 " + root
                    + " 下没有 speech_license.licbag。\n请先在 MultiTTS 里下载离线语音包，"
                    + "或在「语音源 · 诊断」页手动选择语音包 zip / 文件夹");
        out.notes.add("语音包直读 MultiTTS 数据目录 " + root);
        out.source = new ShizukuVoiceSource(root, svc);
    }

    // --------------------------------------- B) Shizuku 在场但无 MultiTTS

    /** 老链路：用户自选文件夹（解压好的资源根，或放 zip/apk 按需解压）。 */
    private static void provisionFromCustomFolder(Context c, Prefs p, IRemoteFileService svc,
                                                  String rig, String voice, Provision out)
            throws Exception {
        String custom = p.customPath();
        if (custom == null || custom.trim().isEmpty())
            throw new IllegalStateException(
                    "未设置语音来源：请在「语音源 · 诊断」页选择语音包 zip 或文件夹");

        // SAF 树只支持目录形态（现有 Directory 来源），zip 需要普通路径 + Shizuku。
        if (custom.startsWith("content://")) {
            out.notes.add("SAF 树来源：只支持解压好的资源根目录；zip 语音包请放在普通路径");
            out.source = new VoiceSources.Directory(c, custom);
            return;
        }

        String root = custom.replaceAll("/+$", "");

        // ---- 1) 目录形态：文件夹（或一层子目录）本身就是 midu 资源根 ------------
        String dataRoot = null;
        if (!svc.stat(root + "/speech_license.licbag").startsWith("-1:")) {
            dataRoot = root;
        } else {
            String[] kids = svc.list(root);
            if (kids != null) {
                for (String k : kids) {
                    if (k == null || k.startsWith(".")) continue;
                    if (!svc.stat(root + "/" + k + "/speech_license.licbag").startsWith("-1:")) {
                        dataRoot = root + "/" + k;
                        out.notes.add("资源根在子目录 " + k);
                        break;
                    }
                }
            }
        }

        // ---- 2) 包形态：一层里的 zip/apk ----------------------------------------
        String pkgZip = null, pkgPrefix = null, pkgStamp = null;
        String soPkg = null;
        String[] soNames = null;
        String[] voices = null;
        String[] kids = svc.list(root);
        if (kids != null) {
            for (String k : kids) {
                if (k == null) continue;
                String lower = k.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".zip") && !lower.endsWith(".apk")) continue;
                String path = root + "/" + k;
                String[] info = svc.zipInfo(path);
                String lic = "", libs = "", vlist = "";
                for (String kv : info) {
                    if (kv.startsWith("licbag_prefix=")) lic = kv.substring(14);
                    else if (kv.startsWith("libs=")) libs = kv.substring(5);
                    else if (kv.startsWith("voices=")) vlist = kv.substring(7);
                }
                boolean hasLic = !lic.isEmpty() && !lic.equals("-");
                boolean hasLib = libs.contains("libspeechengine.so");
                if (hasLic && pkgZip == null) {
                    pkgZip = path;
                    pkgPrefix = lic;
                    pkgStamp = svc.stat(path);
                    voices = vlist.isEmpty() ? new String[0] : vlist.split(",");
                }
                if (hasLib && soPkg == null) {
                    soPkg = path;
                    soNames = libs.split(",");
                }
            }
        }

        // ---- 3) 语音包按需解压到 rig/midu ---------------------------------------
        if (dataRoot == null && pkgZip != null) {
            ensurePkgUnzip(svc, pkgZip, pkgPrefix, pkgStamp, rig, voice, voices, out.notes);
            dataRoot = rig + "/midu";
            out.notes.add("语音包来自 " + new File(pkgZip).getName()
                    + " [" + pkgPrefix + "]（按需解压到 rig/midu）");
        }

        // ---- 4) 厂商 so：解到 rig/lib（host2 用），再镜像到 midu_native（进程内用）--
        if (soPkg != null) {
            ensureSoUnzip(svc, soPkg, soNames, rig, out.notes);
        } else if (MultiTts.installed(c)) {
            ensureSoFromInstalledApk(c, svc, rig, out.notes);
        } else {
            boolean haveEngine = !svc.stat(rig + "/lib/libspeechengine.so").startsWith("-1:");
            out.notes.add(haveEngine ? "rig/lib 已有厂商 so"
                    : "没找到含 lib/arm64-v8a/*.so 的 apk/zip：可在「语音源 · 诊断」页手动选择 so 来源");
        }
        pushShipLibs(c, svc, rig, out.notes);
        mirrorVendorSo(c, svc, rig, out.notes);

        // ---- 5) 返回来源（rig/midu 或用户目录，都经 svc 读） --------------------
        if (dataRoot == null)
            throw new IllegalStateException("目录里没找到语音包：需要 speech_license.licbag"
                    + "（解压好的目录，或包含它的 zip；布局参考 MultiTTS 离线包 bytedance/midu/）");
        out.source = new ShizukuVoiceSource(dataRoot, svc);
    }

    /** MultiTTS 已安装但用户没选文件夹：so 仍从安装的 apk 提取（rig/lib + midu_native）。 */
    private static void ensureSoFromInstalledApk(Context c, IRemoteFileService svc, String rig,
                                                 List<String> notes) throws Exception {
        if (!svc.stat(rig + "/lib/libspeechengine.so").startsWith("-1:")) return;
        boolean done = false;
        for (String apk : MultiTts.apkPaths(c)) {
            String libs = "";
            for (String kv : svc.zipInfo(apk)) {
                if (kv.startsWith("libs=")) { libs = kv.substring(5); break; }
            }
            if (!libs.contains("libspeechengine.so")) continue;
            ensureSoUnzip(svc, apk, libs.split(","), rig, notes);
            done = true;
            break;
        }
        if (!done) notes.add("× MultiTTS apk 里没找到 lib/arm64-v8a/*.so");
    }

    // --------------------------------------------- C) 无 Shizuku：全本地

    private static void provisionLocal(Context c, Prefs p, String voice, Provision out)
            throws Exception {
        out.notes.add("Shizuku 不可用：" + ShizukuClient.error()
                + " · host2 兼容后端不可用，进程内 JNI 不受影响");
        ensureSoLocal(c, p, out.notes);
        out.source = resolveVoiceLocal(c, p, voice, out.notes);
    }

    /** so 链路（无 Shizuku）：用户选的 so 来源 → files/midu_native；MultiTTS 在场先试直读。 */
    private static void ensureSoLocal(Context c, Prefs p, List<String> notes) throws Exception {
        File dir = nativeDir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("mkdirs " + dir);

        String spec = p.soPath() == null ? "" : p.soPath().trim();
        if (!spec.isEmpty()) {
            for (String part : spec.split("\n")) {
                String u = part.trim();
                if (u.isEmpty()) continue;
                extractSoFromUri(c, u, dir, notes);
            }
        } else if (MultiTts.installed(c)) {
            if (!extractSoFromInstalledApkDirect(c, dir, notes))
                notes.add("× 读不动安装的 MultiTTS apk（Android 11+ 限制）：请手动选择 so 来源");
        }

        if (new File(dir, "libspeechengine.so").isFile()) {
            notes.add("厂商 so 就绪（files/midu_native，进程内可 dlopen）");
        } else {
            throw new IllegalStateException("缺少 libspeechengine.so：请安装 MultiTTS，"
                    + "或在「语音源 · 诊断」页选择 so 来源（MultiTTS apk / 内含 lib/arm64-v8a 的 zip / "
                    + "一个或多个 .so / 文件夹）。icu_shim 等无版权风险的库已随本 APK 内置");
        }
    }

    /** 直接读安装的 MultiTTS apk 提取 so；读不动（Android 11+ 常见）返回 false。 */
    private static boolean extractSoFromInstalledApkDirect(Context c, File dir,
                                                           List<String> notes) throws Exception {
        boolean any = false;
        for (String apk : MultiTts.apkPaths(c)) {
            java.util.zip.ZipFile zf = null;
            try {
                zf = new java.util.zip.ZipFile(apk, java.nio.charset.StandardCharsets.UTF_8);
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.startsWith("lib/arm64-v8a/") || !n.endsWith(".so")) continue;
                    String base = n.substring("lib/arm64-v8a/".length());
                    if (!isVendorSo(base) || base.contains("/")) continue;
                    File dst = new File(dir, base);
                    try (InputStream in = zf.getInputStream(e);
                         java.io.FileOutputStream os = new java.io.FileOutputStream(dst, false)) {
                        Io.copy(in, os);
                        os.getFD().sync();
                    }
                    notes.add("提取 " + base + " ← MultiTTS apk (" + dst.length() + " B)");
                    any = true;
                }
            } catch (Exception e) {
                notes.add("读 " + new File(apk).getName() + " 失败：" + e.getMessage());
            } finally {
                if (zf != null) try { zf.close(); } catch (Exception ignored) {}
            }
        }
        return any;
    }

    private static boolean isVendorSo(String name) {
        for (String v : VENDOR_SOS) if (v.equals(name)) return true;
        return false;
    }

    private static String normalize(String name) {
        while (name.startsWith("/")) name = name.substring(1);
        while (name.startsWith("./")) name = name.substring(2);
        return name;
    }

    /**
     * 用户选的 so 来源：content:// 文档（apk / zip / .so，可多选 \n 分隔）、
     * content:// 树（文件夹）、或普通路径（文件或目录）。
     */
    private static void extractSoFromUri(Context c, String spec, File dir,
                                         List<String> notes) throws Exception {
        if (spec.startsWith("content://")) {
            Uri u = Uri.parse(spec);
            DocumentFile tree = null;
            try { tree = DocumentFile.fromTreeUri(c, u); } catch (Exception ignored) {}
            if (tree != null && tree.canRead() && tree.isDirectory()) {
                for (DocumentFile f : tree.listFiles()) {
                    if (f.getName() == null) continue;
                    handleSoItem(c, f.getUri(), null, f.getName(), dir, notes);
                }
                return;
            }
            String name = queryName(c, u);
            handleSoItem(c, u, null, name, dir, notes);
            return;
        }
        File f = new File(spec);
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) handleSoItem(c, null, k.getAbsolutePath(), k.getName(), dir, notes);
            }
            return;
        }
        handleSoItem(c, null, spec, f.getName(), dir, notes);
    }

    /** 单个 so 来源条目：.so 直接复制；.apk/.zip 扫 lib/arm64-v8a 提取厂商闭包。 */
    private static void handleSoItem(Context c, Uri uri, String path, String name,
                                     File dir, List<String> notes) throws Exception {
        if (name == null || name.startsWith(".")) return;
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".so")) {
                File dst = new File(dir, name);
                try (InputStream in = openItem(c, uri, path);
                     java.io.FileOutputStream os = new java.io.FileOutputStream(dst, false)) {
                    Io.copy(in, os);
                    os.getFD().sync();
                }
                notes.add("复制 " + name + " → midu_native (" + dst.length() + " B)");
                return;
            }
            if (lower.endsWith(".apk") || lower.endsWith(".zip")) {
                int n = 0;
                try (ZipStream zs = openZipStream(c, uri, path)) {
                    java.util.zip.ZipEntry e;
                    while ((e = zs.stream.getNextEntry()) != null) {
                        String en = normalize(e.getName());
                        if (!en.startsWith("lib/arm64-v8a/") || e.isDirectory()) continue;
                        String base = en.substring("lib/arm64-v8a/".length());
                        if (base.contains("/") || !isVendorSo(base)) continue;
                        File dst = new File(dir, base);
                        try (java.io.FileOutputStream os = new java.io.FileOutputStream(dst, false)) {
                            Io.copy(zs.stream, os);
                            os.getFD().sync();
                        }
                        notes.add("提取 " + base + " ← " + name + " (" + dst.length() + " B)");
                        n++;
                    }
                }
                if (n == 0) notes.add("× " + name + " 的 lib/arm64-v8a 里没有厂商 so");
                return;
            }
        } catch (Exception e) {
            notes.add("× 处理 " + name + " 失败：" + e.getMessage());
        }
    }

    private static String queryName(Context c, Uri u) {
        try {
            android.database.Cursor cur = c.getContentResolver().query(
                    u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        String n = cur.getString(0);
                        if (n != null && !n.isEmpty()) return n;
                    }
                } finally { cur.close(); }
            }
        } catch (Exception ignored) {}
        return "选中的文件";
    }

    private static InputStream openItem(Context c, Uri uri, String path) throws Exception {
        if (uri != null) return c.getContentResolver().openInputStream(uri);
        return new java.io.FileInputStream(path);
    }

    /** SAF 流上没有随机访问，zip 一律走 ZipInputStream 流式扫。 */
    private static ZipStream openZipStream(Context c, Uri uri, String path) throws Exception {
        return new ZipStream(new ZipInputStream(new BufferedInputStream(
                openItem(c, uri, path), 1 << 16)));
    }
    private static final class ZipStream implements AutoCloseable {
        final java.util.zip.ZipInputStream stream;
        ZipStream(java.util.zip.ZipInputStream s) { stream = s; }
        @Override public void close() throws Exception { stream.close(); }
    }

    /** 无 Shizuku 的语音包：SAF 树 / 普通目录 / 单个 zip / 普通目录里的 zip。 */
    private static VoiceSource resolveVoiceLocal(Context c, Prefs p, String voice,
                                                 List<String> notes) throws Exception {
        String custom = p.customPath() == null ? "" : p.customPath().trim();
        if (custom.isEmpty())
            throw new IllegalStateException("未选择语音包：请在「语音源 · 诊断」页"
                    + "选择语音包 zip 或解压好的文件夹（MultiTTS 离线包 / rig 语音包都行）");

        if (custom.startsWith("content://")) {
            Uri u = Uri.parse(custom);
            DocumentFile tree = null;
            try { tree = DocumentFile.fromTreeUri(c, u); } catch (Exception ignored) {}
            if (tree != null && tree.canRead() && tree.isDirectory()) {
                notes.add("语音包 = SAF 目录树");
                return new VoiceSources.Directory(c, custom);
            }
            LocalZipSource z = new LocalZipSource(c, custom, voice);
            z.ensureCore();
            notes.add("语音包 = 本地 zip/apk（核心资源已解到 files/midu_pkg）");
            return z;
        }

        File f = new File(custom);
        if (f.isDirectory()) {
            if (new File(f, "speech_license.licbag").isFile()) {
                notes.add("语音包 = 本地资源根目录 " + f.getName());
                return new VoiceSources.Directory(c, custom);
            }
            File[] zips = f.listFiles((d, n) -> {
                String l = n.toLowerCase(Locale.ROOT);
                return l.endsWith(".zip") || l.endsWith(".apk");
            });
            if (zips != null && zips.length > 0) {
                LocalZipSource z = new LocalZipSource(c, zips[0].getAbsolutePath(), voice);
                z.ensureCore();
                notes.add("语音包 = " + zips[0].getName() + "（核心资源已解到 files/midu_pkg）");
                return z;
            }
            throw new IllegalStateException("目录里没找到语音包：需要 speech_license.licbag"
                    + "（解压好的资源根）或 zip / apk 语音包：" + custom);
        }
        if (f.isFile()) {
            LocalZipSource z = new LocalZipSource(c, custom, voice);
            z.ensureCore();
            notes.add("语音包 = " + f.getName() + "（核心资源已解到 files/midu_pkg）");
            return z;
        }
        throw new IllegalStateException("语音包路径无效：" + custom);
    }

    /** 诊断 / 首页状态用：当前走的是哪条获取链路。 */
    public static String describeSource(Context c) {
        boolean mt = MultiTts.installed(c);
        boolean sz = ShizukuClient.granted();
        if (mt && sz) return "MultiTTS + Shizuku（自动提取）";
        if (mt) return "MultiTTS（apk 直读 / 手动补充）";
        return "本地选择 so + 语音包";
    }

    // -------------------------------------------------------------- 通用件

    /** zip 语音包：核心条目按包 stamp 增量解压；当前音色按需补解。 */
    private static void ensurePkgUnzip(IRemoteFileService svc, String zip, String prefix,
                                       String zipStamp, String rig, String voice,
                                       String[] voices, List<String> notes) throws Exception {
        String dest = rig + "/midu";
        String stampNow = readRemote(svc, dest + "/" + PKG_STAMP);
        boolean coreOk = !svc.stat(dest + "/speech_license.licbag").startsWith("-1:");
        List<String> rels = new ArrayList<>(java.util.Arrays.asList(
                "speech_license.licbag",
                "zh-cn/ptl.dat", "zh-cn/ptl.idx",
                "zh-cn/frontend/g2p/eng/additional_dict_path.txt",
                "zh-cn/frontend/g2p/mnc/unified_rule.txt"));
        if (voices != null) {
            for (String v : voices) {
                if (!v.trim().isEmpty()) rels.add(v.trim() + "/ptl.idx");
            }
        }
        if (!coreOk || !stampNow.equals(zipStamp)) {
            int n = svc.unzip(zip, dest, prefix, rels.toArray(new String[0]));
            notes.add("解压核心资源 " + n + " 个文件 → rig/midu");
            writeRemote(svc, dest + "/" + PKG_STAMP, zipStamp);
        }
        // 当前音色的 ptl.dat 缺才补解（换音色时）。
        if (svc.stat(dest + "/" + voice + "/ptl.dat").startsWith("-1:")) {
            int n = svc.unzip(zip, dest, prefix, new String[]{
                    voice + "/ptl.dat", voice + "/ptl.idx"});
            notes.add("补解音色 " + voice + "（" + n + " 个文件）");
        }
    }

    /** MultiTTS APK / zip 里的厂商 so 解到 rig/lib；只解厂商闭包，libc++_shared 走推送。 */
    private static void ensureSoUnzip(IRemoteFileService svc, String pkg, String[] libs,
                                      String rig, List<String> notes) throws Exception {
        String dest = rig + "/lib";
        List<String> rels = new ArrayList<>();
        for (String n : libs) {
            for (String want : VENDOR_SOS) if (want.equals(n)) rels.add(n);
        }
        boolean already = !svc.stat(dest + "/libspeechengine.so").startsWith("-1:")
                && !svc.stat(dest + "/libsscronet.so").startsWith("-1:");
        if (already) {
            notes.add("rig/lib 已有厂商 so，跳过 " + new File(pkg).getName());
            return;
        }
        if (rels.isEmpty()) {
            notes.add(new File(pkg).getName() + " 里没有厂商 so（lib/arm64-v8a）");
            return;
        }
        int n = svc.unzip(pkg, dest, "lib/arm64-v8a/", rels.toArray(new String[0]));
        notes.add("解压厂商 so " + n + " 个 → rig/lib（来自 " + new File(pkg).getName() + "）");
    }

    /** APK 自带的 shim / libc++_shared：rig/lib 缺哪个推哪个。 */
    private static void pushShipLibs(Context c, IRemoteFileService svc, String rig,
                                     List<String> notes) throws Exception {
        String libDir = c.getApplicationInfo().nativeLibraryDir;
        for (String name : SHIP_LIBS) {
            String st = svc.stat(rig + "/lib/" + name);
            if (!st.startsWith("-1:")) continue;
            File f = new File(libDir, name);
            if (!f.isFile()) {
                notes.add("× 本机缺 " + name + "（rig/lib 也没有）");
                continue;
            }
            svc.writeFile(rig + "/lib/" + name, Io.readAll(new java.io.FileInputStream(f)));
            notes.add("推 " + name + " → rig/lib");
        }
    }

    /** rig/lib 的厂商 so → files/midu_native/（app 数据目录可 dlopen）。 */
    private static void mirrorVendorSo(Context c, IRemoteFileService svc, String rig,
                                       List<String> notes) throws Exception {
        File dir = nativeDir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("mkdirs " + dir);
        int have = 0;
        for (String name : VENDOR_SOS) {
            String remote = rig + "/lib/" + name;
            String st = svc.stat(remote);
            if (st.startsWith("-1:")) { notes.add("× rig/lib 缺 " + name); continue; }
            File dst = new File(dir, name);
            File stamp = new File(dir, name + ".stamp");
            if (dst.isFile() && stamp.isFile()
                    && Io.readUtf8(stamp).trim().equals(st)) { have++; continue; }
            File tmp = new File(dir, name + ".dl");
            try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                         svc.openFile(remote));
                 java.io.FileOutputStream os = new java.io.FileOutputStream(tmp, false)) {
                Io.copy(in, os);
                os.getFD().sync();
            }
            if (!tmp.renameTo(dst)) throw new IllegalStateException("rename " + name);
            Io.atomicWrite(stamp, st);
            have++;
            notes.add("镜像 " + name + " → midu_native (" + dst.length() + " B)");
        }
        if (have == VENDOR_SOS.length) {
            notes.add("厂商 so 就绪：" + have + "/" + VENDOR_SOS.length
                    + "（midu_native 可 dlopen）");
        }
    }

    /** 诊断页展示：midu_native 里厂商 so 的就位情况。 */
    public static String describeNative(Context c) {
        StringBuilder sb = new StringBuilder();
        File dir = nativeDir(c);
        for (String name : VENDOR_SOS) {
            File f = new File(dir, name);
            sb.append(f.isFile() ? "✓ " : "× ").append(name)
                    .append(f.isFile() ? "  " + f.length() + " B" : "  (缺)").append('\n');
        }
        return sb.toString();
    }

    private static String readRemote(IRemoteFileService svc, String path) {
        try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                     svc.openFile(path))) {
            return new String(Io.readAll(in), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeRemote(IRemoteFileService svc, String path, String text)
            throws Exception {
        svc.writeFile(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
