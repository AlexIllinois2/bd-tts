# Midu TTS Engine —— 把 `libspeechengine.so` 包成系统级 Android TTS 引擎

参考 `host2rig/` 工装（`src/host2.c` 的假 JNIEnv 桥接宿主），把厂商离线引擎封成一个
`TextToSpeechService`，能在 **系统设置 → 文字转语音（TTS）** 里被选为默认引擎；
在此之上合并 [midu_unified_rule](https://github.com/AlexIllinois2/midu_unified_rule) 的发音规则调整能力，
并增加用户自定义规则。

> **交付状态（0.2.0）**：源码 + debug APK（`app/build/outputs/apk/debug/app-debug.apk`，
> 另存 `dist/midu-tts-debug.apk`）。已在本机用 JDK 17 / SDK Platform 36 / NDK 27.0.12077973 /
> CMake 3.22.1 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.3.10 实际编译通过。
>
> 0.2.0 起把 **28 个发音可调能力做成数据驱动的预设**（Compose + Material 3 界面），并新增
> **Legado 本地 HTTP 朗读引擎**（`/tts` 返回 `audio/wav`，可一键导出 HttpTTS 导入 JSON）。
> 详见 [`docs/LEGADO_AND_KNOBS.md`](docs/LEGADO_AND_KNOBS.md)。
>
> **运行期边界照旧**：MuMu 的 x86_64 + houdini 转译下 ARM64 `LD_PRELOAD` shim 注入不了，
> 进程内 JNI 会在 `libsscronet.so` 崩溃，因此模拟器上仍**出不了声**；Shizuku / UserService
> 链路已实测打通。功能、UI、HTTP 接口可在 MuMu 验证，出声仍需原生 arm64 真机。

---

## 0）一句话架构

```
系统 TTS 客户端 (TalkBack / 阅读器 / 任意 App)
        │  android.intent.action.TTS_SERVICE
        ▼
MiduTtsService.onSynthesizeText            Legado「网络朗读」(HttpTTS)
        │  切句 → 当前声音预设                        │  POST /tts (form / JSON)
        ▼                                            ▼
EngineController ◄────────── LegadoHttpService（前台服务 / ServerSocket）→ WAV
        │  SoundProfile.toOptions() → 任意 tts_* 选项
        ├── ① 进程内 JNI：libmidu_bridge.so → 假 JNIEnv → libspeechengine.so → byte[] PCM
        └── ② 兼容后端：Shizuku 跑 /data/local/tmp/host2rig/run_dev.sh → PCM 文件
        ▲
        ├── RuleManager：vendor + 上游产物 + 用户规则 → 合并 → 原子写进资源根
        └── ResourceRoot：内置 / 自选目录 / Shizuku 直读 MultiTTS → files/midu_effective/
        ▼
SynthesisCallback.start(24000, PCM_16BIT, 1) → audioAvailable(...) → done()
```

---

## 1）目录

```
android/
├── app/
│   ├── build.gradle                     # AGP 8.13.2 / Kotlin 2.3.10 / Compose M3 / minSdk26 / arm64-v8a
│   └── src/main/
│       ├── AndroidManifest.xml          # TTS_SERVICE + CONFIGURE/CHECK/INSTALL/GET_SAMPLE + ShizukuProvider + LegadoHttpService
│       ├── aidl/.../IRemoteFileService.aidl
│       ├── jni/
│       │   ├── CMakeLists.txt
│       │   ├── jni_local.h / jnienv_stub.h   # 从 src/ 原样复制（host2 假 JNIEnv）
│       │   ├── fake_jni.c                    # host2.c 的库化改造：dlopen + 选项 + PCM 缓冲
│       │   ├── midu_bridge.c                # 真 JNI：jstring→UTF-8、byte[] 输出
│       │   └── fake_jni.h                    # 两个 TU 之间的纯 C 接口（隔离两套 JNI 类型）
│       ├── java/com/midu/tts/
│       │   ├── MiduTtsService.java          # TextToSpeechService
│       │   ├── SettingsActivity.java        # 0.1.x 经典设置页（保留入口，不再是启动器）
│       │   ├── TtsIntents.java              # CHECK_TTS_DATA / GET_SAMPLE_TEXT
│       │   ├── engine/                      # NativeEngine / EngineController / Host2ShellBackend / EngineOption
│       │   ├── sound/                       # Knob / Knobs(28 项能力表) / SoundProfile / SoundProfiles
│       │   ├── rules/                       # Rules(解析+合并) / RuleManager / RuleUpdateService
│       │   ├── source/                      # VoiceSource / Bundled / Directory / Shizuku / ResourceRoot
│       │   ├── shizuku/                     # ShizukuClient / RemoteFileService (UserService)
│       │   └── util/                        # Prefs / Io / Diagnostics
│       ├── kotlin/com/midu/tts/
│       │   ├── ui/                          # Compose M3：Theme / Components / Actions / MainActivity
│       │   ├── ui/pages/                    # Home / Sound / Rules / Legado / Source 五页
│       │   └── legado/                      # LegadoHttpServer / LegadoHttpService / LegadoImport / WavEncoder / LegadoState
│       ├── assets/rules/vendor_unified_rule.txt   # 规则种子（纯文本，随工程）
│       ├── assets/midu/                          # prepare_vendor.py 生成，.gitignore 忽略
│       ├── jniLibs/arm64-v8a/                    # prepare_vendor.py 生成，.gitignore 忽略
│       └── res/xml/tts_engine.xml                # settingsActivity 元数据（系统设置里的齿轮）
├── scripts/
│   ├── prepare_vendor.py                # rig → jniLibs/assets，先按 MANIFEST.md5 校验原件
│   ├── verify_rules.py                  # 离线复算三层合并（纯 Python，与 Java 同语义）
│   └── mumu_debug.sh                    # MuMu：连接/ABI 预检/装包/推语音包/设默认引擎/logcat
├── local_rules/user_rules.txt           # 离线校验用的用户规则样例
└── docs/
    ├── PRONUNCIATION_KNOBS.md           # REA 静态盘点：所有可调发音/韵律的口子
    ├── LEGADO_AND_KNOBS.md              # 0.2.0：参数模型、Legado HTTP 接口、导入配置
    ├── RULE_FORMAT.md                   # 规则语法、三层优先级、自动更新
    └── SHIZUKU.md                       # Shizuku 直读 MultiTTS 的原理与排查
```

---

## 2）构建

本机已备齐并实测通过的工具链：**Gradle 8.13 + JDK 17 + SDK Platform 36 / Build-Tools 36
+ NDK 27.0.12077973 + CMake 3.22.1 + AGP 8.13.2 + Kotlin 2.3.10 + Compose BOM 2025.10.00
/ material3 1.4.0**。`gradlew` / `gradle-wrapper.jar` 已随工程带好，
`local.properties` 指向本机 SDK（不入库）。仓库优先走阿里云镜像 —— 本机 `maven.google.com`
直连超时，见 `settings.gradle`。

```bash
# 1) 校验 rig 原件并把厂商 .so / 语音包 / 规则种子同步进工程
python android/scripts/prepare_vendor.py            # 全量（APK 实测约 43 MB）
python android/scripts/prepare_vendor.py --no-data  # 轻量：不打包语音包，运行时用 Shizuku/自选目录
python android/scripts/prepare_vendor.py --verify   # 只按 MANIFEST.md5 对账原件

# 2) 编译（也可用 Android Studio 打开 android/ 后 Build → Make）
./gradlew assembleDebug                            # 产物 app/build/outputs/apk/debug/app-debug.apk
# Windows 构建前设好 JAVA_HOME / ANDROID_HOME；SDK 缺 NDK/CMake 时用：
#   sdkmanager --sdk_root=<SDK> "ndk;27.0.12077973" "cmake;3.22.1"
```

`prepare_vendor.py` 会拒绝在 `MANIFEST.md5` 对不上的情况下同步 —— 保证进 APK 的
`libspeechengine.so` / `ptl.dat` 就是 rig 里那枚原件，一个字节没改。

> 若用 `--no-data`，`assets/rules/vendor_unified_rule.txt` 仍然会带上（规则是纯文本），
> 但 `assets/midu/` 为空：必须设置「自定义目录」或「MultiTTS(Shizuku)」才能出声。

---

## 3）安装与启用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put secure tts_default_engine com.midu.tts
# 或：系统设置 → 辅助功能/无障碍 → 文字转语音输出 → 选「Midu TTS 引擎」
```

App 设置页（`android.speech.tts.engine.CONFIGURE_ENGINE` 也能从系统设置齿轮进入）里：

1. 选离线语音位置 → 刷新音色 → 选音色 → 应用来源并重建；
2. 点「冒烟自检」/「冒烟并播放」确认能出 PCM；
3. 规则页「立即检查上游更新」拿一次 `midu_unified_rule` 产物；
4. 编辑用户规则 → 「保存并应用」。

---

## 4）MuMu 模拟器调试

```bash
PORT=16384 ./android/scripts/mumu_debug.sh          # MuMu 12 本体默认端口
PORT=5555  ./android/scripts/mumu_debug.sh          # MuMu 5.0
PORT=7555  ./android/scripts/mumu_debug.sh          # MuMu 6
PUSH_RIG=1 PORT=16384 ./android/scripts/mumu_debug.sh   # 额外把 host2rig 推给兼容后端
```

脚本会做：

- `adb connect` → `getprop ro.product.cpu.abilist / abilist64 / ro.dalvik.vm.native.bridge` **ABI 预检**；
- 装包 → `pm dump com.midu.tts | grep primaryCpuAbi` 确认选中了 arm64；
- 把 `host2rig/midu/` 推到 `/sdcard/midu_voice`（**免 Shizuku** 的调试路径，设置页选「自定义目录」填这个路径）；
- `settings put secure tts_default_engine com.midu.tts`；
- 打开设置页并抓 `midu-bridge / midu-fakejni / MiduTtsService / MiduRuleUpdate` 的 logcat。

**硬前提**：厂商库只有 arm64-v8a 原件。MuMu 官方支持 `armeabi-v7a / arm64-v8a / x86 / x86_64`，
但默认按 so 数量优先选 x86_64；脚本发现没含 `arm64-v8a` 会直接拒绝。若 `primaryCpuAbi` 不对，
在模拟器里编辑 `/data/system/etc/mumu-configs/abi-select-android12.config` 追加一行：

```
com.midu.tts arm64-v8a
```

保存后重启模拟器（MuMu 官方开发者须知给的 ABI 指定方式）。应用内「环境诊断」按钮也会打印
ABI、native.bridge、12 枚必需 .so 是否齐、资源/规则文件是否就位、Shizuku 状态。

> MuMu 上想验证 RE / 文件布局，可在模拟器里装 RE 文件管理器看 `/data/local/tmp/host2rig`、
> `/sdcard/midu_voice`；本次会话没有连设备，REA 只做了本地 ELF 静态盘点（见 `docs/PRONUNCIATION_KNOBS.md`）。

---

## 5）三份规则与优先级

**用户自定义 > midu_unified_rule 产物（自动定时检查更新）> vendor。**

- vendor：`assets/rules/vendor_unified_rule.txt`（rig 原件种子，只读）
- 上游：`RuleUpdateService` 每 6h 用 ETag 拉 `conf/unified_rule.txt` + `conf/rule_patch.txt`，
  过合法性闸后原子落盘，保留 `.prev` 可回滚；URL/间隔/开关都能在设置页改
- 用户：设置页编辑 / 快速生成表单 / SAF 导入导出

合并按「字词 + 条件」判同键，高层覆盖读音、保留位置与上游排序；
用户可 `-行` / `移除:` / `@删除 字词` 删掉低层规则。
引擎在 **init 时**读规则，内容一变就 `generation+1` 并重建引擎。
细节见 `docs/RULE_FORMAT.md`；离线复算用 `python android/scripts/verify_rules.py`。

---

## 6）与 host2 工装的对应关系

| rig | 本工程 |
|---|---|
| `host2` 可执行 + 假 JNIEnv | `fake_jni.c`（同一套假对象/函数表，改成库入口、假 JavaVM 改 static） |
| `LD_PRELOAD=log_shim:icu_shim:cronet_shim4` | 按绝对路径 `dlopen(RTLD_GLOBAL)` 先入全局符号表；app 里跳过 `log_shim`（系统 liblog 是真的） |
| `--out out.pcm` 落盘 | PCM 攒在 handle 缓冲里，JNI 返回 `byte[]` |
| `tts_off_resource_path` / 选项串 | 逐条照抄 `host2.c` 的 offline/pre_bind 档位 |
| `getPackageName → com.lechuan.mdwz` | 假 env 原样回报，喂的是设备上合法持有的 `speech_license.licbag` |
| `push_rig.sh` 冒烟 | 设置页「冒烟自检」+ `EngineController` 每次重建后自动一发 |
| `run_dev.sh` | `HOST2_SHELL` 兼容后端：Shizuku `exec` 跑同一支脚本（LD_PRELOAD 逐字同参） |

### 为什么要留兼容后端

app 进程里没有 `LD_PRELOAD`：`icu_shim` 对 libc 宽字符符号的拦截、以及
`cronet_shim4` 对 `Cronet_Engine_StartWithParams` 的抢占，在 ART 进程里未必
和命令行进程等价；Android linker namespace 也可能拦住系统 ICU。
所以 `EngineController` 默认 `AUTO`：**进程内 JNI init + 冒烟一发失败 →
自动降级到 Shizuku + host2**（前提：rig 已推到 `/data/local/tmp/host2rig`）。
设置页可强制任一后端，冒烟与诊断会告诉你到底走了哪条。
注意：这条降级只兜得住 Java 异常；native SIGSEGV 会直接杀进程（见 §7.9）。

---

## 7）已知边界（别当承诺）

1. **只有 arm64-v8a**：rig 里没有 32 位库闭包；x86 模拟器必须支持 ARM64 转译。
2. **采样率写死 24000**（`tts_rate/sample_rate/custom_sample_rate`）：换 16k 代音色要同步改 C 层与服务常量。
3. **PCM 整段返回**：合成完才一次性喂 `audioAvailable`（句中延迟 1–2 s）；流式可后续用
   ring buffer + 跨线程 JNI 升级。长文本在 `MiduTtsService.splitText()` 里按句切开再拼。
4. **规则改了必须重建引擎**：已用 generation/广播处理，但重建期间会有一次初始化开销。
5. **不钉种子同文本两发出声不同**：判规则生效请看前端标注日志，不要比 PCM 字节。
6. **STOP 指令码未验**：`onStop()` 只做本地收流，不会真打断引擎正在跑的推理。
7. **Shizuku shell(2000) 在部分 Android 13+ 也可能读不到 `Android/data`**：用 root/Sui 或公共目录；见 `docs/SHIZUKU.md`。
8. **shim 等价性：MuMu 上已实测，进程内 JNI 走不通**。2026-11-09 在 MuMu 12（端口 7555、
   Android 15、`x86_64,arm64-v8a` + houdini 转译）实测：JNI 符号修复后，厂商库能
   `JNI_OnLoad` → `createEngineToNative` → 资源根正确 → **licbag 授权校验通过**
   （`License file check result:0, credential: com.lechuan.mdwz`）→ 开始加载 `ptl.dat`
   字典；随后后台线程 `Thread-2` 在 `libsscronet.so` 里 SIGSEGV（`SEGV_MAPERR`），
   栈顶经 `libspeechengine.so`。同时 `libicui18n.so` 因找不到系统 `libc++.so` 未加载。
   判断是 **x86→ARM 转译 + `cronet_shim4` 未能抢到符号**（app 进程没有 `LD_PRELOAD`，
   只有 `dlopen(RTLD_GLOBAL)`，且受 linker namespace `clns-7` 限制）；真机原生 arm64
   是否同样崩溃仍未验证。
9. **native 崩溃兜不住 AUTO 降级**：SIGSEGV 直接杀进程，Java 层的「init + 冒烟失败 →
   降级 HOST2_SHELL」只在抛 Java 异常时有效。要真正兜底，需把进程内 JNI 放进独立
   `:native` 进程，或直接用 Shizuku 后端；当前在 MuMu 上必须手动选 `HOST2_SHELL`
   （且要 Shizuku/rig），或换真机验证。
10. **在线/克隆/情感等选项只做了静态枚举**，没有实接；清单在 `docs/PRONUNCIATION_KNOBS.md`。
11. **MuMu 上 Shizuku 已启用，但 `HOST2_SHELL` 仍出不了声（转译环境限制）**：用
    `adb root` + Shizuku v13.6.0 官方启动器 `libshizuku.so` 以 root 拉起 server，
    给 `com.midu.tts` 授权后，logcat 实证 `ShizukuProvider: binder received` →
    `UserServiceManager: com.midu.tts:midu-files` → `RemoteFileService` 以 root 启动
    → binder 回传，**整条 Shizuku/UserService 链路打通**。但执行 ARM64 rig 时：
    保留 `LD_PRELOAD` → houdini64 拒绝加载 ARM64 `log_shim.so`；临时去掉
    `LD_PRELOAD` → host2 能启动到 `[setOpt] license_directory`，却因 `cronet_shim4`
    拦截失效而 `StartWithParamsInternal ### NO global context` → `libsscronet.so` SIGSEGV。
    即：**shim 必须在原生 arm64 上靠 `LD_PRELOAD` 注入**，x86_64+houdini 的 MuMu 做不到；
    这不是 Shizuku 或应用代码的问题。换真机/原生 ARM64 镜像即可继续验证。
    完整实操见 `docs/SHIZUKU.md` §6。

---

## 8）合规

- `libspeechengine.so` 与 `ptl.dat` / `licbag` 是你设备上合法持有的厂商原件，本工程
  **不改一个字节、不伪造授权判决、不重新分发**；`prepare_vendor.py` 只在本地复制到构建目录，
  产物已在 `.gitignore` 里。打出来的 APK 仅供自用，请勿对外分发。
- 上游规则项目 `AlexIllinois2/midu_unified_rule` 的产物由用户自己的设备定时拉取，
  本工程只做解析与合并；规则文件是纯文本，随工程只带 vendor 种子。

---

## 9）自检清单（拿到设备后按顺序过）

- [ ] `python android/scripts/prepare_vendor.py --verify` → 全部 ✓
- [x] `./gradlew assembleDebug` 出包；`aapt badging` / v2 签名校验通过（本机已完成）
- [ ] `adb install -r dist/midu-tts-debug.apk` 到 MuMu 或真机
- [ ] `adb shell getprop ro.product.cpu.abilist` 含 `arm64-v8a`；`pm dump | grep primaryCpuAbi` 一致
- [ ] 设置页「环境诊断」12 枚 .so 全 ✓、规则种子 ✓
- [ ] 「冒烟自检」返回非 0 字节；「冒烟并播放」有声
- [ ] 系统 TTS 设置里能看见并选中本引擎，第三方 app 朗读走它
- [ ] 「立即检查上游更新」拿到产物 → 合并报告计数变化 → 重建引擎后仍能出声
- [ ] 改一条用户规则 → 前端日志 `pinyin list after apply rules` 变化
- [ ] Shizuku 模式能读到 MultiTTS 的 `zh-cn/ptl.dat`；换音色能枚举
- [ ] **0.2.0**：「声音调校」页切换预设 → 试听；logcat `midu-fakejni` 能看到逐条 `opt ...` 下发
- [ ] **0.2.0**：「Legado 对接」启动服务 → `/health` 返回 JSON、`/knobs` 列出 28 项
- [ ] **0.2.0**：复制导入 JSON → Legado 朗读引擎导入 → 选发言人/情绪 → 能听到对应韵律变化
- [ ] **0.2.0**：`/tts?text=你好` 用 curl/浏览器下载到可播放的 WAV（44 字节 RIFF 头 + PCM）

---

## 10）0.2.0 快速上手（Legado）

1. 打开 App → 「Legado 对接」→ 启动本地朗读服务（默认 `127.0.0.1:8765`）。
2. 点「复制导入 JSON」→ Legado「我的 → 朗读引擎 → 导入」粘贴；或在「网络朗读」新建后粘 URL 规则。
3. Legado 里选发言人（= 本地声音预设）与情绪（= 韵律微调）即可朗读。
4. 「声音调校」页改参数会**同时**作用于系统 TTS 与 Legado 接口；实验性选项默认不下发，逐项开启后再试听验证。

参数表、证据分级、HTTP 路由与导入配置格式见 [`docs/LEGADO_AND_KNOBS.md`](docs/LEGADO_AND_KNOBS.md)。
