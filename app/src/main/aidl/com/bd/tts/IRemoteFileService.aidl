package com.bd.tts;

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

    // 探测 zip/apk：返回 "licbag_prefix=<含 speech_license.licbag 的目录前缀>"
    // 与 "libs=<lib/arm64-v8a 下的 .so 名单,逗号分隔>"。shell 进程无 scoped storage
    // 限制，可以直接读 /sdcard 上的包文件。解析失败返回空数组。
    String[] zipInfo(String path) = 6;

    // 按需解压：只解 zip 中以 prefix 开头的条目；rels 非空时只解这些
    // （strip prefix 后的相对路径），空 = prefix 下全部。目标目录 shell 可写。
    // 返回解出的文件数；出错抛 RemoteException。
    int unzip(String zipPath, String destDir, String prefix, in String[] rels) = 7;
}
