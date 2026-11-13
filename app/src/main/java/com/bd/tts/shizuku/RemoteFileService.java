package com.bd.tts.shizuku;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.bd.tts.IRemoteFileService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Shizuku UserService：运行在 shell(2000) 或 root(0) 身份的独立进程。
 * 用途：读 Android/data 下 MultiTTS 的字节语音包；以及以 shell 身份跑 host2 兼容后端。
 * 该类由 Shizuku 按类名反射加载，不需要在 Manifest 里声明为组件，但必须被 ProGuard 保留。
 */
public class RemoteFileService extends IRemoteFileService.Stub {

    private static final String TAG = "BdRemoteFile";

    public RemoteFileService() { Log.i(TAG, "UserService created"); }

    /** Shizuku API v13 会优先尝试带 Context 的构造，保留它以免回退失败。 */
    public RemoteFileService(android.content.Context context) {
        Log.i(TAG, "UserService created with Context");
    }

    @Override
    public void destroy() throws RemoteException {
        Log.i(TAG, "destroy");
        System.exit(0);
    }

    @Override
    public ParcelFileDescriptor openFile(String path) throws RemoteException {
        try {
            return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException e) {
            throw new RemoteException("openFile " + path + " : " + e.getMessage());
        }
    }

    @Override
    public void writeFile(String path, byte[] data) throws RemoteException {
        try {
            File f = new File(path);
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new IOException("mkdirs " + parent);
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(f, false)) {
                os.write(data);
                os.getFD().sync();
            }
        } catch (IOException e) {
            throw new RemoteException("writeFile " + path + " : " + e.getMessage());
        }
    }

    @Override
    public String[] list(String path) throws RemoteException {
        File f = new File(path);
        String[] names = f.list();
        if (names == null) return new String[0];
        ArrayList<String> out = new ArrayList<>();
        for (String n : names) out.add(n);
        return out.toArray(new String[0]);
    }

    @Override
    public String stat(String path) throws RemoteException {
        File f = new File(path);
        if (!f.exists()) return "-1:0";
        return f.length() + ":" + f.lastModified();
    }

    /** 单次 exec 最长等待：host2 卡死时不能让 app 侧的 Binder 调用永远挂住。 */
    private static final long DEFAULT_TIMEOUT_MS = 90_000L;

    /**
     * 探测 zip/apk 内容：shell 进程直接读 /sdcard 不受 scoped storage 限制。
     * 只读中央目录，不解压数据。返回：
     *   licbag_prefix=…   含 speech_license.licbag 的目录前缀；没有则 "-"
     *   libs=a.so,b.so    lib/arm64-v8a 下的 .so 名单
     *   voices=…          语音包前缀下带 ptl.dat/ptl.idx 的音色目录名
     */
    @Override
    public String[] zipInfo(String path) throws RemoteException {
        try {
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(path,
                    java.nio.charset.StandardCharsets.UTF_8);
            String licbagPrefix = "-";
            java.util.LinkedHashSet<String> libs = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<String> voices = new java.util.LinkedHashSet<>();
            try {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (n.endsWith("/speech_license.licbag")) {
                        int slash = n.lastIndexOf('/');
                        licbagPrefix = slash < 0 ? "" : n.substring(0, slash + 1);
                    }
                    if (n.startsWith("lib/arm64-v8a/") && n.endsWith(".so")) {
                        libs.add(n.substring("lib/arm64-v8a/".length()));
                    }
                    if (!licbagPrefix.isEmpty() && !"-".equals(licbagPrefix)
                            && n.startsWith(licbagPrefix) && n.endsWith("/ptl.dat")) {
                        String rel = n.substring(licbagPrefix.length());
                        int slash = rel.indexOf('/');
                        if (slash > 0 && !"zh-cn".equals(rel.substring(0, slash)))
                            voices.add(rel.substring(0, slash));
                    }
                }
            } finally {
                zf.close();
            }
            ArrayList<String> out = new ArrayList<>();
            out.add("licbag_prefix=" + licbagPrefix);
            out.add("libs=" + String.join(",", libs));
            out.add("voices=" + String.join(",", voices));
            return out.toArray(new String[0]);
        } catch (Exception e) {
            Log.w(TAG, "zipInfo " + path + " : " + e.getMessage());
            return new String[0];
        }
    }

    /**
     * 按需解压：只解 prefix 开头的条目；rels 非空时再按相对路径过滤。
     * strip 掉 prefix，让 dest 直接呈现资源根布局。
     */
    @Override
    public int unzip(String zipPath, String destDir, String prefix, String[] rels)
            throws RemoteException {
        java.util.Set<String> want = rels == null ? null : new java.util.HashSet<>(java.util.Arrays.asList(rels));
        File dest = new File(destDir);
        if (!dest.isDirectory() && !dest.mkdirs()) throw new RemoteException("mkdirs " + destDir);
        int n = 0;
        try {
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipPath,
                    java.nio.charset.StandardCharsets.UTF_8);
            try {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || !name.startsWith(prefix)) continue;
                    String rel = name.substring(prefix.length());
                    if (rel.isEmpty() || rel.endsWith("/")) continue;
                    if (want != null && !want.contains(rel)) continue;
                    File out = new File(dest, rel);
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                        throw new IOException("mkdirs " + parent);
                    try (java.io.InputStream in = zf.getInputStream(e);
                         java.io.FileOutputStream os = new java.io.FileOutputStream(out, false)) {
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                        os.getFD().sync();
                    }
                    out.setLastModified(e.getTime() > 0 ? e.getTime() : System.currentTimeMillis());
                    n++;
                }
            } finally {
                zf.close();
            }
            Log.i(TAG, "unzip " + new File(zipPath).getName() + " [" + prefix + "] → "
                    + destDir + " : " + n + " 文件");
            return n;
        } catch (Exception e) {
            throw new RemoteException("unzip " + zipPath + " [" + prefix + "] → "
                    + destDir + " : " + e.getMessage());
        }
    }

    @Override
    public int exec(String[] cmd, String[] envp, String dir, String logPath) throws RemoteException {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            long timeoutMs = DEFAULT_TIMEOUT_MS;
            boolean detached = false;
            if (dir != null && !dir.isEmpty()) pb.directory(new File(dir));
            if (envp != null) {
                for (String kv : envp) {
                    int p = kv.indexOf('=');
                    if (p <= 0) continue;
                    String k = kv.substring(0, p), v = kv.substring(p + 1);
                    if ("EXEC_TIMEOUT_SEC".equals(k)) {
                        // 只给本 UserService 的控制量，不要塞进子进程环境。
                        try { timeoutMs = Math.max(1000L, Long.parseLong(v) * 1000L); }
                        catch (NumberFormatException ignored) {}
                        continue;
                    }
                    if ("EXEC_DETACH".equals(k)) {
                        // 常驻 daemon 永不退出：启动后立刻返回，不能 waitFor，
                        // 也不能被 90 s 超时强杀。这两个键都不会透传给子进程。
                        detached = "1".equals(v);
                        continue;
                    }
                    pb.environment().put(k, v);
                }
            }
            if (logPath != null && !logPath.isEmpty()) {
                pb.redirectErrorStream(true);
                pb.redirectOutput(new File(logPath));
            } else {
                pb.redirectErrorStream(true);
                pb.redirectOutput(new File("/dev/null"));
            }
            proc = pb.start();
            if (detached) {
                Log.i(TAG, "exec detach：子进程常驻，不等退出：" + cmd[0]);
                return 0;
            }
            long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
            while (true) {
                try {
                    return proc.exitValue();
                } catch (IllegalThreadStateException notFinished) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        proc.destroyForcibly();
                        Log.w(TAG, "exec 超时 " + timeoutMs + " ms，已强杀：" + cmd[0]);
                        return 124;   // 与 GNU timeout 一致的「超时」返回码
                    }
                    try { Thread.sleep(100); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        proc.destroyForcibly();
                        return 124;
                    }
                }
            }
        } catch (Exception e) {
            if (proc != null) proc.destroyForcibly();
            throw new RemoteException("exec: " + e.getMessage());
        }
    }
}
