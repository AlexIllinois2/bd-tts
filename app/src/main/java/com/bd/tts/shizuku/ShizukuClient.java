package com.bd.tts.shizuku;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.bd.tts.IRemoteFileService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

/** Shizuku / Sui 的薄封装：权限、binder 生命周期、UserService 绑定。 */
public final class ShizukuClient {
    public static final int REQ_PERMISSION = 8601;
    private static final Object LOCK = new Object();
    private static volatile IRemoteFileService sService;
    private static volatile ServiceConnection sConn;
    private static volatile boolean sBinding;
    private static volatile String sError = "";

    private ShizukuClient() {}

    public static String error() { return sError; }

    public static boolean available() {
        try {
            return Shizuku.getBinder() != null && !Shizuku.isPreV11();
        } catch (Throwable t) {
            sError = "Shizuku binder 不可用：" + t.getMessage();
            return false;
        }
    }

    public static boolean granted() {
        try {
            return available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            sError = "Shizuku 权限检查失败：" + t.getMessage();
            return false;
        }
    }

    public static void requestPermission(Activity a) {
        try {
            if (!available()) { sError = "Shizuku 未运行（或 Sui 未安装）"; return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_PERMISSION);
            }
        } catch (Throwable t) {
            sError = "请求 Shizuku 权限失败：" + t.getMessage();
        }
    }

    public static String describe() {
        try {
            if (!available()) return "未连接（" + (sError.isEmpty() ? "Shizuku 未启动" : sError) + "）";
            int uid = Shizuku.getUid();
            return (uid == 0 ? "root" : uid == 2000 ? "adb/shell" : "uid=" + uid)
                    + (granted() ? " · 已授权" : " · 未授权");
        } catch (Throwable t) {
            return "状态未知：" + t.getMessage();
        }
    }

    /**
     * 同步拿 UserService（最多等 timeoutMs）。必须在工作线程调用。
     * 返回 null 时看 error()：常见原因是 Shizuku 未启动 / 未授权 / 版本 < 10。
     */
    public static IRemoteFileService acquire(Context ctx, long timeoutMs) {
        IRemoteFileService svc = sService;
        if (svc != null && svc.asBinder().isBinderAlive()) return svc;

        if (!available()) {
            // 等 binder（ShizukuProvider 是异步把 binder 送进进程的）。
            final CountDownLatch binderLatch = new CountDownLatch(1);
            Shizuku.OnBinderReceivedListener listener = binderLatch::countDown;
            try {
                Shizuku.addBinderReceivedListenerSticky(listener);
                binderLatch.await(Math.min(timeoutMs, 3000), TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
            } finally {
                try { Shizuku.removeBinderReceivedListener(listener); } catch (Throwable ignored) {}
            }
        }
        if (!available()) { sError = "Shizuku binder 未到（确认 Shizuku/Sui 已启动）"; return null; }
        if (!granted()) { sError = "Shizuku 未授权"; return null; }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<IRemoteFileService> ref = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (LOCK) {
                IRemoteFileService cached = sService;
                if (cached != null && cached.asBinder().isBinderAlive()) {
                    ref.set(cached); latch.countDown(); return;
                }
                if (sBinding) {
                    // 已有一次绑定在飞。原来这里直接 return，调用方的 latch 永远不
                    // countDown，只能白等满 timeoutMs 再拿一个可能仍是 null 的缓存。
                    // 先放行 latch，由 acquire 末尾短暂轮询共享缓存。
                    latch.countDown();
                    return;
                }
                sBinding = true;
            }
            try {
                if (Shizuku.getVersion() < 10) {
                    sError = "Shizuku API 版本过低（需要 >= 10）";
                    latch.countDown(); return;
                }
                Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                        new ComponentName(ctx.getPackageName(), RemoteFileService.class.getName()))
                        .daemon(false)
                        .processNameSuffix("files")
                        .debuggable(false)
                        // v2：AIDL 新增 zipInfo/unzip（用户目录语音包按需解压）。
                        // 升版本号才会杀掉旧 UserService 进程，否则 Shizuku 复用旧 binder。
                        .version(2)
                        .tag("midu-files");
                ServiceConnection conn = new ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                        IRemoteFileService s = IRemoteFileService.Stub.asInterface(binder);
                        synchronized (LOCK) { sService = s; sConn = this; sBinding = false; }
                        ref.set(s);
                        latch.countDown();
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        synchronized (LOCK) { sService = null; sBinding = false; }
                    }
                };
                Shizuku.bindUserService(args, conn);
            } catch (Throwable t) {
                sError = "bindUserService 失败：" + t.getMessage();
                synchronized (LOCK) { sBinding = false; }
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        IRemoteFileService result = ref.get() != null ? ref.get() : sService;
        if (result == null || !result.asBinder().isBinderAlive()) {
            // 并发绑定由另一个调用方发起时，这里稍等共享缓存，而不是立刻返回 null。
            long deadline = android.os.SystemClock.elapsedRealtime() + Math.min(timeoutMs, 3000L);
            do {
                IRemoteFileService s = sService;
                if (s != null && s.asBinder().isBinderAlive()) { result = s; break; }
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
        }
        return result;
    }
}
