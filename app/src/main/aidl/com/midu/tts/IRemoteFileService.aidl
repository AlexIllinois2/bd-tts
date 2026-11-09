package com.midu.tts;

import android.os.ParcelFileDescriptor;

// aidl.exe 要求：要么所有方法都写事务 id，要么都不写。
// destroy 必须用 Shizuku 约定的保留码 16777114，因此这里给每个方法都显式编号。
interface IRemoteFileService {
    // Shizuku 约定的保留销毁事务码（见 Shizuku-API demo/IUserService.aidl）。
    void destroy() = 16777114;

    // 以 shell/root 身份打开任意路径，Fd 经 Binder 传回 app 进程。
    ParcelFileDescriptor openFile(String path) = 1;

    // 以 shell/root 身份写文件（用于把合并后的规则写到 /data/local/tmp 的 host2 rig）。
    void writeFile(String path, in byte[] data) = 2;

    // 列目录（只返回直接子项名字），用于枚举音色包。
    String[] list(String path) = 3;

    // "size:lastModified"，不存在返回 "-1:0"。用于增量缓存判定。
    String stat(String path) = 4;

    // 以 shell/root 身份执行命令；envp 为 "K=V"；stdout/stderr 合并写入 logPath。
    int exec(in String[] cmd, in String[] envp, String dir, String logPath) = 5;
}
