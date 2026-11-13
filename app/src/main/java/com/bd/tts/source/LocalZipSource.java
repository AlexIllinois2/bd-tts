package com.bd.tts.source;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.bd.tts.util.Io;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 「本地 zip / apk 里的语音包」来源（不需要 Shizuku，SAF 文档或普通路径都行）。
 *
 * 首次使用把包里的 midu 资源根解到 files/midu_pkg/：
 *   speech_license.licbag + zh-cn/ 全部 + 各音色 ptl.idx + 当前音色 ptl.dat。
 * 换音色后缺的 ptl.dat 在 open() 时补解（多扫一遍 zip）。
 * 支持包内目录前缀（MultiTTS 离线包的 bytedance/midu/ 布局）；apk 的 lib/ 等条目自动跳过。
 */
public final class LocalZipSource implements VoiceSource {
    private static final String DIR = "midu_pkg";

    private final Context ctx;
    private final String uri;       // content:// 文档 URI 或普通文件路径
    private final File root;        // files/midu_pkg
    private final String voice;     // 构造时指定的当前音色（决定 ptl.dat 是否随核心一起解）
    // rel(去前缀) -> {size, time}
    private final Map<String, long[]> index = new HashMap<>();
    private volatile String prefix = null;   // licbag 所在目录前缀（含尾 /），根布局为 ""
    private volatile boolean ready = false;

    public LocalZipSource(Context c, String u, String voice) {
        ctx = c.getApplicationContext();
        uri = u;
        this.voice = voice == null || voice.isEmpty() ? "" : voice;
        root = new File(ctx.getFilesDir(), DIR);
    }

    public String uri() { return uri; }

    private InputStream raw() throws Exception {
        if (uri.startsWith("content://"))
            return ctx.getContentResolver().openInputStream(Uri.parse(uri));
        return new FileInputStream(uri);
    }

    private String stamp() {
        try {
            if (uri.startsWith("content://")) {
                android.database.Cursor cur = ctx.getContentResolver().query(
                        Uri.parse(uri), new String[]{OpenableColumns.SIZE}, null, null, null);
                long size = -1;
                if (cur != null) {
                    if (cur.moveToFirst() && !cur.isNull(0)) size = cur.getLong(0);
                    cur.close();
                }
                return size + ":doc";
            }
            File f = new File(uri);
            return f.length() + ":" + f.lastModified();
        } catch (Exception e) {
            return String.valueOf(uri.hashCode());
        }
    }

    private static String normalize(String name) {
        while (name.startsWith("/")) name = name.substring(1);
        while (name.startsWith("./")) name = name.substring(2);
        return name;
    }

    /**
     * 解出核心资源并建立索引。必须在 resolve 阶段调用一次：
     * 让「包里没有 licbag」这类错误尽早以可读信息抛出。
     */
    public synchronized void ensureCore() throws Exception {
        if (ready) return;
        Io.ensureDir(root);
        File stamp = new File(root, ".zip_stamp");
        if (stamp.isFile() && Io.readUtf8(stamp).trim().equals(stamp())) {
            scan(null);                // 上一轮已解好：只重建索引（含未解压条目的指纹），免解压
            ready = true;
            return;
        }
        // pass 1：全包扫一遍，只为拿条目清单与 licbag 前缀（ZipInputStream 需读完数据才能前进）。
        scan(null);
        if (prefix == null)
            throw new IllegalStateException("所选 zip/apk 里没有 speech_license.licbag："
                    + "请选择 MultiTTS 离线语音包（bytedance/midu 布局）或解压好的资源根目录");
        // pass 2：抽取核心 + 各音色 ptl.idx + 当前音色 ptl.dat。
        java.util.Set<String> want = new java.util.HashSet<>();
        for (String rel : index.keySet()) {
            if (rel.startsWith("zh-cn/") || rel.equals("speech_license.licbag")
                    || rel.endsWith("/ptl.idx")
                    || (!voice.isEmpty() && rel.equals(voice + "/ptl.dat"))) {
                want.add(rel);
            }
        }
        scan(want);
        Io.atomicWrite(stamp, stamp());
        ready = true;
    }

    private String relOf(String name) {
        if (prefix == null) return null;
        if (prefix.isEmpty()) return name;
        return name.startsWith(prefix) ? name.substring(prefix.length()) : null;
    }

    /**
     * 扫 zip：want==null 只建索引；want!=null 命中的条目边扫边解到 root/rel。
     * 跳过的条目也要读完（ZipInputStream 前进 + 拿 data descriptor 里的真实 size）。
     */
    private void scan(java.util.Set<String> want) throws Exception {
        index.clear();
        byte[] disc = new byte[1 << 16];
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw(), 1 << 16))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = normalize(e.getName());
                boolean isDir = e.isDirectory() || name.endsWith("/");
                if (!isDir && prefix == null
                        && (name.equals("speech_license.licbag")
                            || name.endsWith("/speech_license.licbag"))) {
                    int slash = name.lastIndexOf('/');
                    prefix = slash < 0 ? "" : name.substring(0, slash + 1);
                }
                if (want == null) {
                    // 只为前进与计数；拿到 size 后记下全名，稍后统一算 rel。
                    long n = discard(zin, disc);
                    if (!isDir && !name.startsWith("lib/") && !name.startsWith("META-INF/")) {
                        fullNames.put(name, new long[]{e.getSize() >= 0 ? e.getSize() : n,
                                e.getTime()});
                    }
                    continue;
                }
                String rel = relOf(name);
                if (isDir || rel == null || rel.isEmpty() || !want.contains(rel)) {
                    discard(zin, disc);
                    continue;
                }
                long[] st = pump(zin, new File(root, rel), e.getTime());
                index.put(rel, st);
            }
        }
        if (want == null) {
            // 由全名清单算出 rel 索引（licbag 前缀此时已确定）。
            for (Map.Entry<String, long[]> en : fullNames.entrySet()) {
                String rel = relOf(en.getKey());
                if (rel != null && !rel.isEmpty()) index.put(rel, en.getValue());
            }
            fullNames.clear();
        }
    }
    private final Map<String, long[]> fullNames = new HashMap<>();

    private static long discard(ZipInputStream in, byte[] buf) throws Exception {
        long n = 0; int r;
        while ((r = in.read(buf)) > 0) n += r;
        return n;
    }

    /** 解一个条目到 root/rel（tmp + rename + 修正 mtime），返回 {size,time}。 */
    private long[] pump(ZipInputStream in, File out, long time) throws Exception {
        Io.ensureDir(out.getParentFile());
        File tmp = new File(out.getParentFile(), out.getName() + ".dl");
        long n = 0;
        try (FileOutputStream os = new FileOutputStream(tmp, false)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) { os.write(buf, 0, r); n += r; }
            os.getFD().sync();
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new IllegalStateException("rename 失败：" + out.getName());
        }
        out.setLastModified(time > 0 ? time : System.currentTimeMillis());
        return new long[]{n, out.lastModified()};
    }

    @Override public String id() { return "zip:" + uri; }

    @Override public String[] listVoices() throws Exception {
        ensureCore();
        TreeSet<String> out = new TreeSet<>();
        for (String rel : index.keySet()) {
            if (!rel.endsWith("/ptl.dat") && !rel.endsWith("/ptl.idx")) continue;
            int slash = rel.indexOf('/');
            if (slash <= 0) continue;
            String v = rel.substring(0, slash);
            if (v.equals("zh-cn") || v.startsWith(".")) continue;
            out.add(v);
        }
        return out.toArray(new String[0]);
    }

    @Override public InputStream open(String rel) throws Exception {
        ensureCore();
        File f = new File(root, rel);
        if (!f.isFile() || f.length() == 0) {
            synchronized (this) { extractOne(rel); }
            if (!f.isFile() || f.length() == 0) throw new FileNotFoundException(rel);
        }
        return new FileInputStream(f);
    }

    /** 补解单个缺失条目（如换音色后的 ptl.dat）：再扫一遍 zip。 */
    private void extractOne(String rel) throws Exception {
        byte[] disc = new byte[1 << 16];
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw(), 1 << 16))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = normalize(e.getName());
                String r = relOf(name);
                if (!e.isDirectory() && rel.equals(r)) {
                    long[] st = pump(zin, new File(root, rel), e.getTime());
                    index.put(rel, st);
                    return;
                }
                discard(zin, disc);
            }
        }
    }

    @Override public String fingerprint(String rel) {
        File f = new File(root, rel);
        if (f.isFile() && f.length() > 0) return f.length() + ":" + f.lastModified();
        long[] ix = index.get(rel);
        return ix != null ? ix[0] + ":" + ix[1] : "-1:0";
    }

    @Override public void close() {}
}
