package com.midu.tts.shizuku;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.midu.tts.IRemoteFileService;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Shizuku UserService：运行在 shell(2000) 或 root(0) 身份的独立进程。
 * 用途：读 Android/data 下 MultiTTS 的字节语音包；以及以 shell 身份跑 host2 兼容后端。
 * 该类由 Shizuku 按类名反射加载，不需要在 Manifest 里声明为组件，但必须被 ProGuard 保留。
 */
public class RemoteFileService extends IRemoteFileService.Stub {

    private static final String TAG = "MiduRemoteFile";

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

    @Override
    public int exec(String[] cmd, String[] envp, String dir, String logPath) throws RemoteException {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (dir != null && !dir.isEmpty()) pb.directory(new File(dir));
            if (envp != null) {
                for (String kv : envp) {
                    int p = kv.indexOf('=');
                    if (p > 0) pb.environment().put(kv.substring(0, p), kv.substring(p + 1));
                }
            }
            if (logPath != null && !logPath.isEmpty()) {
                pb.redirectErrorStream(true);
                pb.redirectOutput(new File(logPath));
            } else {
                pb.redirectErrorStream(true);
                pb.redirectOutput(new File("/dev/null"));
            }
            return pb.start().waitFor();
        } catch (Exception e) {
            throw new RemoteException("exec: " + e.getMessage());
        }
    }
}
