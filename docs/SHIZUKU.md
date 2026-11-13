# 离线语音位置 & Shizuku 直读 MultiTTS 字节语音包

## 1）三种来源，用户在设置页自选

| 模式 | 数据来源 | 适用 |
|---|---|---|
| 内置 | APK `assets/midu/`（`prepare_vendor.py` 打包 rig 的 `midu/`） | 装完即用；APK 约 110 MB |
| 自定义目录 / SAF 树 | 用户填普通路径，或 `ACTION_OPEN_DOCUMENT_TREE` 选目录 | 语音包放 `/sdcard/Download/…`、MuMu 调试 |
| MultiTTS（Shizuku） | `/storage/emulated/0/Android/data/org.nobody.multitts/files/voice/bytedance/midu` | 设备上已装 MultiTTS + 字节语音包，不想重复存 40 MB |

三者的文件布局都必须长得像 rig 的 `midu/`：

```
speech_license.licbag
zh-cn/{ptl.dat,ptl.idx,frontend/g2p/eng/additional_dict_path.txt}
<音色码>/{ptl.dat,ptl.idx}          ← 例如 BV001_24k_streaming
```

设置页「刷新音色列表」会枚举所有含 `ptl.dat` 的子目录；当前只把**选中的那一枚音色**
镜像进有效资源根，避免把整套语音包复制一遍。

---

## 2）为什么需要 Shizuku

Android 11+ 的作用域存储把别的 app 的 `Android/data` 挡住了；MultiTTS 的语音包恰好在：

```
/storage/emulated/0/Android/data/org.nobody.multitts/files/voice/bytedance/midu
```

Shizuku（或 Sui）以 shell(uid 2000) / root(uid 0) 身份起一个 **UserService**，
我们的 app 通过 Binder 拿 FD 与目录信息，就能读这些文件。

集成要点（已在工程里实现）：

- `AndroidManifest.xml` 声明 `rikka.shizuku.ShizukuProvider`，authority `${applicationId}.shizuku`；
- Gradle：`dev.rikka.shizuku:api:13.1.5` + `provider:13.1.5`；
- 权限：`Shizuku.checkSelfPermission()` / `Shizuku.requestPermission()`，
  设置页「检测 / 授权 Shizuku」；
- AIDL `IRemoteFileService`：`openFile / list / stat / writeFile / exec`，
  `destroy() = 16777114` 是 Shizuku 约定的保留事务码；UserService 类由 ProGuard 保留；
- `Shizuku.UserServiceArgs(...).version(1).tag("midu-files")`，`Shizuku.getVersion() >= 10`。

---

## 3）「直接用」到底怎么用（重要取舍）

引擎**自己按路径 `open()` 文件**，而通过 Binder 传回来的 FD 没法变成「app 自己有权限 open 的路径」；
硬链接又常常跨挂载（`/data/media` ↔ `/data/user/0`）报 EXDEV。所以做法是：

1. UserService 以 shell/root 读 MultiTTS 的 `ptl.dat` / `ptl.idx` / `licbag` / 英文词典；
2. 用 `size:lastModified` 做指纹，**只在文件变了时**增量镜像到本 app 的
   `files/midu_effective/`（一次性约 40 MB，之后零拷贝）；
3. 合并后的 `unified_rule.txt` 由本 app 写进自己的有效根 —— **从不回写 MultiTTS 的目录**。

这样「直接用 MultiTTS 的字节语音文件」成立：不需要用户手动导出/导入、不需要再下一份语音包，
也不会破坏 MultiTTS 自己的数据。

> 如果 Shizuku 是 root（uid 0）且你愿意改 rig 路径，也可以走兼容后端
> （`HOST2_SHELL`）：把 `host2rig/` 放到 `/data/local/tmp/host2rig`，app 用
> `IRemoteFileService.exec` 以 shell 身份跑 `run_dev.sh`，并把合并规则 `writeFile`
> 到 rig 的资源根。那是与真机工装逐字同参（LD_PRELOAD 三枚 shim）的一条路。

---

## 4）操作步骤

1. 装 [Shizuku](https://shizuku.rikka.app/download/)（root 设备也可装 Sui/Magisk 模块）。
2. 启动 Shizuku：Android 11+ 可用无线调试自启；root 设备直接点「通过 root 启动」。
3. 本 app 设置页 → 「检测 / 授权 Shizuku」→ 允许。
4. 选「MultiTTS 字节语音包」→ 确认/修改 midu 根目录 → 「保存并试读」→
   「刷新音色列表」→ 选音色 → 「应用来源并重建」。
5. 「冒烟并播放」确认出声。

### 常见失败

| 现象 | 原因 / 处理 |
|---|---|
| `读不到 zh-cn/ptl.dat` | 路径不对（不同用户/工作资料、MultiTTS 版本差异）；用 Shizuku 的 `list()` 看目录 |
| shell(2000) 仍读不到 `Android/data` | 部分 Android 13+/14 版本对 shell 也收紧；改用 root 启动 Shizuku / 装 Sui，或把语音包复制到公共目录走「自定义目录」 |
| `bindUserService 失败` | Shizuku 版本 < 10、未授权、binder 还没送到；点「刷新状态」重试 |
| `host2 rc=…` | rig 没推到 `/data/local/tmp/host2rig` 或没 `chmod -R 755` |

---

## 5）MuMu 上不必用 Shizuku 的快捷路径

模拟器通常自带 root，但调试时更省事的是直接推：

```bash
adb -s 127.0.0.1:16384 push host2rig/midu/. /sdcard/midu_voice
```

然后设置页选「自定义目录」填 `/sdcard/midu_voice`。
`android/scripts/mumu_debug.sh` 的 `PUSH_VOICE=1` 就做了这一步。

---

## 6）MuMu 上启用 Shizuku 的实操（2026-11-09 实测）

MuMu 12（端口 `7555`、Android 15、`x86_64,arm64-v8a` + houdini）**可以跑起 Shizuku**，
但它是 x86_64 主 ABI、没有 `su`、`/system` 只读，所以不能走"通过 root 启动"；
可行的是 **`adb root` + Shizuku 官方启动器**。

```bash
SER=127.0.0.1:7555
ADB=<sdk>/platform-tools/adb

# 1) adb root（MuMu 支持；之后 adb shell 是 uid=0）
"$ADB" -s "$SER" root && "$ADB" -s "$SER" shell id      # uid=0(root)

# 2) 装 Shizuku v13.6.0 r1086 并打开一次
"$ADB" -s "$SER" install -r shizuku-v13.6.0.r1086.2650830c-release.apk
"$ADB" -s "$SER" shell monkey -p moe.shizuku.privileged.api 1

# 3) 首页下滑到「通过连接电脑启动（使用 adb）」→ 点「查看指令」，会得到：
#    adb shell /data/app/~~XXXX==/moe.shizuku.privileged.api-YYYY==/lib/x86_64/libshizuku.so
#    这个 libshizuku.so 就是启动器（ELF x86-64 可执行）。以 root adb 执行设备侧那段路径：
"$ADB" -s "$SER" shell '<上面那条命令里的 /data/app/.../lib/x86_64/libshizuku.so>'
#    → info: starting server... / info: shizuku_server pid is NNNN / exit with 0
#    → ps -A | grep shizuku_server   显示 root

# 4) 应用设置页点「检测 / 授权 Shizuku」→ 弹窗点「始终允许」
#    状态栏变 `Shizuku：root · 已授权`；Shizuku app 显示「已授权 1 个应用」。

# 5) 推 rig 并切后端（HOST2_SHELL）
"$ADB" -s "$SER" push host2rig/. /data/local/tmp/host2rig
"$ADB" -s "$SER" shell chmod -R 755 /data/local/tmp/host2rig
#    设置页「② 引擎运行方式」→ 选 host2_shell → 「保存后端设置」
```

> Git Bash 下 `adb shell uiautomator dump /sdcard/x.xml` 要加 `MSYS_NO_PATHCONV=1`，
> 否则 `/sdcard/...` 会被当成本机路径。设备是 1080×1920，设置页「冒烟自检」按钮
> 中心约 (417,683)，「检测 / 授权 Shizuku」约 (294,1671)（页面需滚动）。

### 验证结果

链路完全打通（logcat 实证）：

```
ShizukuProvider: binder received
Service : send binder to user app com.bd.tts in user 0
UserServiceManager: New service record com.bd.tts:midu-files ...
ShizukuServiceStarter: starting service com.bd.tts/...RemoteFileService...
BdRemoteFile: UserService created with Context
UserServiceRecord: Binder received for service record ...
```

**但 MuMu 上仍出不了 PCM**，卡在 ARM64 转译，与 Shizuku 本身无关：

| 尝试 | 结果 |
|---|---|
| 原样 rig（`LD_PRELOAD=log_shim.so:icu_shim.so:cronet_shim4.so`） | `CANNOT LINK EXECUTABLE "/system/bin/houdini64": log_shim.so is for EM_AARCH64 (183) instead of EM_X86_64 (62)` —— x86_64 转译器自己先加载了 ARM shim |
| 设备侧临时去掉 `LD_PRELOAD` 再跑 | host2 能启动到 `[setOpt] license_directory`，但 `cronet_shim4` 拦截失效 → `StartWithParamsInternal ### NO global context` → `libsscronet.so` SIGSEGV，`rc=1 bytes=0` |

结论：**要出声必须让 `LD_PRELOAD` 三枚 shim 真正生效**，只能换原生 arm64
（真机，或原生 ARM64 模拟器镜像）；x86_64 + houdini 的 MuMu 做不到。
Shizuku / UserService 这条链路本身已验证可用，真机上可直接用。
