package com.bd.tts.source;

import android.os.ParcelFileDescriptor;

import com.bd.tts.IRemoteFileService;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 以 Shizuku shell/root 身份直接读 MultiTTS 安装好的字节语音包。
 *
 * 为什么要「镜像」一份：引擎自己按路径 open() 文件，而 app 进程在 Android 11+
 * 拿不到别的 app 的 Android/data；通过 Shizuku 拿到的 FD 无法变回可 open 的路径。
 * 所以这里把大件按 size:mtime 增量镜像到本 app 自己的 files 目录（一次性 40MB 左右），
 * 规则文件则由本 app 自己写（三层合并结果），永远不回写 MultiTTS 的目录。
 */
public final class ShizukuVoiceSource implements VoiceSource {
    private final String root;
    private final IRemoteFileService svc;

    public ShizukuVoiceSource(String root, IRemoteFileService svc) {
        this.root = root.replaceAll("/+$", "");
        this.svc = svc;
    }

    public String root() { return root; }
    public IRemoteFileService service() { return svc; }

    @Override public String id() { return "shizuku:" + root; }

    @Override public InputStream open(String rel) throws Exception {
        ParcelFileDescriptor pfd = svc.openFile(root + "/" + rel);
        if (pfd == null) throw new java.io.FileNotFoundException(rel);
        return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
    }

    @Override public String fingerprint(String rel) throws Exception {
        return svc.stat(root + "/" + rel);
    }

    @Override public String[] listVoices() throws Exception {
        String[] names = svc.list(root);
        List<String> out = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                if (n == null || n.startsWith(".") || n.equals("zh-cn")
                        || n.endsWith(".txt") || n.endsWith(".licbag")) continue;
                // ptl.idx 就算音色：zip 按需解压时 idx 先落位，dat 只解当前选中的音色。
                boolean has = !svc.stat(root + "/" + n + "/ptl.dat").startsWith("-1:")
                        || !svc.stat(root + "/" + n + "/ptl.idx").startsWith("-1:");
                if (has) out.add(n);
            }
        }
        String[] arr = out.toArray(new String[0]);
        Arrays.sort(arr);
        return arr;
    }

    @Override public void close() {}
}
