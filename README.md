# BD TTS Engine —— 把 `libspeechengine.so` 包成系统级 Android TTS 引擎

参考 `host2rig/` 工装（`src/host2.c` 的假 JNIEnv 桥接宿主），把厂商离线引擎封成一个
`TextToSpeechService`，能在 **系统设置 → 文字转语音（TTS）** 里被选为默认引擎；
在此之上合并 [midu_unified_rule](https://github.com/AlexIllinois2/midu_unified_rule) 的发音规则调整能力，
并增加用户自定义规则。

> **交付状态（0.2.0）**：源码 + debug APK（`app/build/outputs/apk/debug/app-debug.apk`，
> 另存 `dist/midu-tts-debug.apk`）。已在**本机 Linux 环境**用 JDK 17 (Temurin) /
> SDK Platform 36 / NDK 29.0.13846066 / CMake 3.22.1 / Gradle 8.13 / AGP 8.13.2 /
> Kotlin 2.3.10 实际编译通过。环境明细见 [§2 构建](#2构建)。
>
> 0.2.0 起把 **28 个发音可调能力做成数据驱动的预设**（Compose + Material 3 界面），并新增
> **Legado 本地 HTTP 朗读引擎**（`/tts` 返回 `audio/wav`，可一键导出 HttpTTS 导入 JSON）。
> 详见 [`docs/LEGADO_AND_KNOBS.md`](docs/LEGADO_AND_KNOBS.md)。
>
> 另有**听书模式**：导入自定义 `.tts` 文本，按行播放、记忆续播、拖动进度、按行号跳转，
> 状态栏常驻播放控件。格式与用法见 [`docs/AUDIOBOOK_TTS_FORMAT.md`](docs/AUDIOBOOK_TTS_FORMAT.md)。
>
> **运行期边界（2026-11-12 真机实测后更新）**：`cronet_shim4` 要靠 `LD_PRELOAD` 才抢得到
> `Cronet_Engine_StartWithParams`，而 app 进程里只有 `dlopen(RTLD_GLOBAL)`。MuMu（x86_64 +
> houdini）**和**原生 arm64 真机（Redmi Note 8 Pro）都会在 `initEngineToNative` 里报
> `Failed to prepare environment: -300`，约 30 s 后 SIGSEGV 杀掉整个 app 进程 —— 界面上的表现
> 就是「点合成并播放没反应」。出声请走 `host2_shell`（Shizuku + rig，由启动脚本 /
> ProcessBuilder 注入 `LD_PRELOAD`）；Shizuku / UserService 链路已实测打通。App 侧加了崩溃守护：
> 上次死在 native 后，AUTO 会直接跳过进程内后端。

---

## 0）一句话架构

```
系统 TTS 客户端 (TalkBack / 阅读器 / 任意 App)
        │  android.intent.action.TTS_SERVICE
        ▼
BdTtsService.onSynthesizeText            Legado「网络朗读」(HttpTTS)
        │  切句 → 当前声音预设                        │  POST /tts (form / JSON)
        ▼                                            ▼
EngineController ◄────────── LegadoHttpService（前台服务 / ServerSocket）→ WAV
        │  SoundProfile.toOptions() → 任意 tts_* 选项
        ├── ① 进程内 JNI：libmidu_bridge.so → 假 JNIEnv → libspeechengine.so → byte[] PCM
        └── ② 兼容后端：Shizuku 跑 /data/local/tmp/host2rig/run_dev.sh → PCM 文件
        ▲
        ├── RuleManager：vendor + 上游产物 + 用户规则 → 合并 → 原子写进资源根
        └── ResourceRoot：决策树供给 → files/midu_effective/
            （MultiTTS+Shizuku 自动从其 apk 提取 so / 语音包；否则用户本地选 so + 语音包 zip/文件夹）
        ▼
SynthesisCallback.start(24000, PCM_16BIT, 1) → audioAvailable(...) → done()
```

---

## 1）目录

```
midu-tts/
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
│       ├── java/com/bd/tts/
│       │   ├── BdTtsService.java          # TextToSpeechService
│       │   ├── SettingsActivity.java        # 0.1.x 经典设置页（保留入口，不再是启动器）
│       │   ├── TtsIntents.java              # CHECK_TTS_DATA / GET_SAMPLE_TEXT
│       │   ├── engine/                      # NativeEngine / EngineController / Host2ShellBackend / EngineOption / PinyinInline（内联拼音）
│       │   ├── sound/                       # Knob / Knobs(28 项能力表) / SoundProfile / SoundProfiles
│       │   ├── rules/                       # Rules(解析+合并) / RuleManager / RuleUpdateService
│       │   ├── source/                      # VoiceSource / Bundled / Directory / Shizuku / ResourceRoot
│       │   ├── shizuku/                     # ShizukuClient / RemoteFileService (UserService)
│       │   └── util/                        # Prefs / Io / Diagnostics
│       ├── kotlin/com/bd/tts/
│       │   ├── ui/                          # Compose M3：Theme / Components / Actions / MainActivity
│       │   ├── ui/pages/                    # Home / 听书 / Sound / Rules / 读音 / Legado / Source 页
│       │   ├── audiobook/                   # 听书：TtsBooks / BookPrefs / AudiobookService / State
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

以下按**本机 Linux 环境**实测填好，配置都在工程里，换机器只需改 `local.properties`
和 `app/build.gradle` 的 `ndkVersion`：

| 组件 | 本机实际值 |
|---|---|
| JDK | Temurin 17.0.20，`/usr/lib/jvm/java-17-temurin-jdk`（`java` 指向 17；PATH 里的 `javac` 是 25，Gradle 用 JVM 内置 compiler，不受影响） |
| Android SDK | `/home/v/Android/Sdk` —— `local.properties` 的 `sdk.dir`，该文件不入库 |
| Platform / Build-Tools | `android-36` / `36.0.0`（另装了 34 / 35 / 37.0、30.0.3 / 34 / 35） |
| NDK | `29.0.13846066` (r29-beta3)。**`ndk/27.0.12077973` 只有 `.installer` 空壳，不能用** |
| CMake | `3.22.1`（含 ninja） |
| Gradle / AGP / Kotlin | 8.13 / 8.13.2 / 2.3.10 |
| Compose | BOM 2025.10.00 / material3 1.4.0 |

`gradlew` 是官方 LF 版且带执行位，`gradle-wrapper.jar` 随工程带好，可直接 `./gradlew`；
仓库依赖优先走阿里云镜像 —— 本机 `maven.google.com` 直连超时，见 `settings.gradle`。

```bash
# 0) 可选：显式钉住工具链（不设也能构建，SDK 已由 local.properties 指定）
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
export ANDROID_HOME=/home/v/Android/Sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"   # adb

# 1) 校验 rig 原件并把厂商 .so / 语音包 / 规则种子同步进工程
python3 scripts/prepare_vendor.py            # 全量（APK 实测约 43 MB）
python3 scripts/prepare_vendor.py --no-data  # 轻量：不打包语音包，运行时用 Shizuku/自选目录
python3 scripts/prepare_vendor.py --verify   # 只按 MANIFEST.md5 对账原件

# 2) 编译（也可用 Android Studio 打开工程根目录后 Build → Make）
./gradlew assembleDebug                     # 产物 app/build/outputs/apk/debug/app-debug.apk
# 只改 Kotlin/Java 时的快速校验：./gradlew :app:compileDebugKotlin
```

SDK 组件缺失时用本机 cmdline-tools 补装（版本要与上表一致）：

```bash
"$ANDROID_HOME/cmdline-tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
    "platforms;android-36" "build-tools;36.0.0" "ndk;29.0.13846066" "cmake;3.22.1"
```

> 构建时可能打印 `[CXX5304] This version only understands SDK XML versions up to 3 ...`：
> 本机 cmdline-tools / platform-tools 比 AGP 8.13.2 新，SDK XML 已是 v4。只是兼容性提示，
> CMake 与打包都正常，可忽略。

`prepare_vendor.py` 会拒绝在 `MANIFEST.md5` 对不上的情况下同步 —— 保证进 APK 的
`libspeechengine.so` / `ptl.dat` 就是 rig 里那枚原件，一个字节没改。

> 若用 `--no-data`，`assets/rules/vendor_unified_rule.txt` 仍然会带上（规则是纯文本），
> 但 `assets/midu/` 为空：必须设置「自定义目录」或「MultiTTS(Shizuku)」才能出声。

### 2.1）自动发布（GitHub CI）

**推 `v*` tag 即自动构建并发布**（`.github/workflows/release.yml`）：

```bash
git tag v1.0.0 && git push github v1.0.0     # 构建完在 Releases 页出现同名 Release + APK
```

流程：JDK 17 → `assembleRelease` → `apksigner verify` 签名自检 →
`softprops/action-gh-release` 发布，APK 命名 `bd-tts-<tag>.apk`，Release 标题 = tag 名，
release notes 自动生成。也可在 Actions 页手动 `workflow_dispatch` 触发（发布当前默认分支快照）。

**签名：release APK 用 debug key 签（刻意为之）。** 密钥就是本机
`~/.android/debug.keystore`（标准口令 `android` / `androiddebugkey`），经 GitHub Actions
secret `ANDROID_KEYSTORE_BASE64` 注入（另有 `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` /
`ANDROID_KEY_PASSWORD` 三个 secret 固定填 `android` / `androiddebugkey` / `android`）。
这样选的好处：

- CI 产物与本地 `assembleDebug` 同签名，可直接 `adb install -r` 覆盖安装，不用先卸载；
- 不用维护独立 release keystore，密钥丢了/换机器也只是重建 debug key + 重装一次。

代价：public 仓库下 debug key 无保密强度（口令固定公开），**别把 CI 产物当可信任分发渠道**，
只自用。换机器后想保持签名一致，把 `~/.android/debug.keystore` 带过去即可。

本地构建 release 不设 `ANDROID_KEYSTORE_FILE` 等环境变量时自动跳过签名（出未签名包），
设置后（如 CI 一样指向某个 keystore 文件）则按配置签名。

---

## 3）安装与启用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put secure tts_default_engine com.bd.tts
# 或：系统设置 → 辅助功能/无障碍 → 文字转语音输出 → 选「BD TTS 引擎」
```

App 设置页（`android.speech.tts.engine.CONFIGURE_ENGINE` 也能从系统设置齿轮进入）里：

1. 选离线语音位置 → 刷新音色 → 选音色 → 应用来源并重建；
2. 点「冒烟自检」/「冒烟并播放」确认能出 PCM；
3. 规则页「立即检查上游更新」拿一次 `midu_unified_rule` 产物；
4. 「读音」页表单式调整词语拼音（推荐），或在规则页整份编辑用户规则 → 「保存并应用」。

> **真机要出声**：Shizuku 需已启动并给 `com.bd.tts` 授权；rig 推到
> `/data/local/tmp/host2rig`（含 `host2`、`lib/`、`midu/`）；诊断页把「运行后端」选成
> `host2_shell`。rig 里没有 `run_dev.sh` 时，把 [`scripts/run_dev.sh`](scripts/run_dev.sh)
> 推上去即可；新版本代码在脚本缺失时也会直接执行 `host2` 本体并注入 `LD_PRELOAD`。

---

## 3.1）词语读音调整（表单式，推荐入口）

底部「读音」页把 `midu_unified_rule` 工具页的表单交互搬进 App，操作与网页一致，且省掉 adb 推送：

| 网页工具 | 本 App |
|---|---|
| 中文字词 / 读音 / 匹配规则 / 匹配值 / 注释 | 同名字段；匹配值留空或 `()` 时自动用字词圈定目标 |
| 「添加」→ 内容区 | 任意输入框按回车即添加；内容区可删除、点一条取回表单重编辑 |
| 「应用」写 `conf/rule_patch.txt` 并整理 | 写进 `files/rules/user_rules.txt` 的 `新增:` 区块（整行去重），立即合并三份规则并重建引擎 |
| 状态条：行数 / 字节 / mtime、外部改动高亮 | 用户规则文件同样显示，2 秒心跳检测外部编辑 |
| 「推送」= adb push + 强停 app | 不需要：本 App 就是规则的消费者 |

额外补了 App 内才方便做的事：快捷示例一点填表、自定义规则名与「无（全局生效）」、
切换规则类型自动维护匹配值、「查找 / 调整已有读音」（查用户层与合并结果，点一条回填表单，
改完读音添加并应用即覆盖同字词 + 同条件的低层规则）、应用后直接试听。
表单与待应用列表会跨 tab / 转屏保留。

生成的行格式（Tab 分隔）：

```
行	hang2	TEXT(雪中悍刀(行))	# 书名末字
```

核心逻辑在 `rules/PronunciationEditor.kt`（纯 Kotlin、无 Android 依赖），
页面在 `ui/pages/PronunciationPage.kt`，落盘与重建引擎走 `ui/Actions.kt` → `RuleManager.apply()`。

## 3.2）调用方内联拼音（`[[字词|pin1 yin1]]`，不走规则层）

任何经 `EngineController` 的文本（系统 TTS / Legado HTTP / App 试听框）都支持**临时注音**：

```
[[行人|xing2 ren2]]如织，脚步匆匆。      # 逐字覆写，声调 1-5，拼音数=汉字数
```

- 实现：`engine/PinyinInline.java` 把标签转成 `<speak>…<phoneme alphabet="py" ph="…">…</speak>`，
  命中的句子自动切 `tts_text_type=ssml`（该键不在保留名单里，每句可切，已真机实测）；
- 引擎侧日志证据：`overwrite pinyin to xing2, word_index:0, text:行`（详见
  [`docs/PRONUNCIATION_KNOBS.md`](docs/PRONUNCIATION_KNOBS.md) §4）；
- **拼音数 ≠ 汉字数时引擎会整句近乎静音**，所以任一标签校验不过就只摘掉标记
  按普通文本读；直接传整段 `<speak>` SSML 也原样支持。规则层（§3.1）管持久读音，
  内联拼音管单次合成的临时读音，两者互不影响。
- App「读音」页提供**注音格式说明与示例提示词**（一键复制）：本 App 不生成注音文本，
  只消费 `[[字词|pin1 yin1]]` 格式；示例提示词供你发给任意 AI 自行生成文本，
  生成结果导入「听书」页即可朗读。

### 3.3）立体声展宽（本地后处理，缓解单声道「干」）

引擎只出单声道 PCM，听感偏干。声音预设里新增 `bd_stereo_width`（0–100%，默认 35）：
app 在 PCM 出口用 Haas 反相侧链（`sound/StereoWiden.java`：干声延迟 ~11ms + 3.2kHz 低通
做侧链，左右反相叠加）实时展宽成假立体声。

- **单声道下混严格兼容**：(L+R)/2 里侧链完全抵消、无梳状滤波，手机单声道扬声器几乎无感，
  耳机 / 立体声喇叭上空间感明显，人声定位仍在正中；
- 三条出口（系统 TTS / Legado HTTP / 试听播放）行为一致，系统 TTS 出口会把
  `callback.start` 声明为双声道，Legado HTTP 的 WAV 头同步改双声道；
- 不下发引擎（`bd_` 前缀键被 `toOptions()` 跳过），改完下次合成即生效，无需重建；
  Legado 可按次覆盖：`/tts?bd_stereo_width=0` 关掉。

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
- 装包 → `pm dump com.bd.tts | grep primaryCpuAbi` 确认选中了 arm64；
- 把 `host2rig/midu/` 推到 `/sdcard/midu_voice`（**免 Shizuku** 的调试路径，设置页选「自定义目录」填这个路径）；
- `settings put secure tts_default_engine com.bd.tts`；
- 打开设置页并抓 `midu-bridge / midu-fakejni / BdTtsService / BdRuleUpdate` 的 logcat。

**硬前提**：厂商库只有 arm64-v8a 原件。MuMu 官方支持 `armeabi-v7a / arm64-v8a / x86 / x86_64`，
但默认按 so 数量优先选 x86_64；脚本发现没含 `arm64-v8a` 会直接拒绝。若 `primaryCpuAbi` 不对，
在模拟器里编辑 `/data/system/etc/mumu-configs/abi-select-android12.config` 追加一行：

```
com.bd.tts arm64-v8a
```

保存后重启模拟器（MuMu 官方开发者须知给的 ABI 指定方式）。应用内「环境诊断」按钮也会打印
ABI、native.bridge、12 枚必需 .so 是否齐、资源/规则文件是否就位、Shizuku 状态。

> MuMu 上想验证 RE / 文件布局，可在模拟器里装 RE 文件管理器看 `/data/local/tmp/host2rig`、
> `/sdcard/midu_voice`；本次会话没有连设备，REA 只做了本地 ELF 静态盘点（见 `docs/PRONUNCIATION_KNOBS.md`）。

---

## 5）三份规则与优先级

**用户自定义 > midu_unified_rule 产物（自动定时检查更新）> vendor。**

- vendor：`assets/rules/vendor_unified_rule.txt`（rig 原件种子，只读）
- 上游：`RuleUpdateService` 每 6h 用 ETag 拉 `conf/unified_rule.txt`，
  过合法性闸后原子落盘，保留 `.prev` 可回滚；URL/间隔/开关都能在设置页改。
  源站（raw.githubusercontent.com）被网络重置时自动回退代理前缀镜像
  （gh-proxy.com → ghproxy.net），详见 `docs/RULE_FORMAT.md` §4
- 用户：规则页可添加任意数量本地 / 网络规则文件，拖动排序（越上越优先），
  单文件启停、移除、文本编辑（系统编辑器）或表单编辑（弹发音页，应用时追加到该文件）；
  旧 `user_rules.txt` 首次启动自动进列表。发音页已从底部导航移除，只作为表单编辑弹窗。
- 总开关：规则页「一键短路」关掉全部规则，不改各文件自己的开关；
  第三方 `GET/POST /tts?rules=off|on` 也能覆盖这个总开关。

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

## 6.1）host2 常驻后端与流式回传

一次性 exec 每句都要重新 `dlopen` + 加载字典/模型，长文朗读的开销主要在这里。
`rig/src/host2.c` 在工装原版基础上派生出一个 `--serve` 模式：**引擎只 init 一次**，
监听 `127.0.0.1:18945`，PCM 每来一个回调块就发一帧，app 侧边收边播。

```bash
# 1) 编译 + 推送（需要 NDK 29.0.13846066；SER 用 adb devices 查到的真机 serial）
CHECK=1 ./scripts/build_host2_serve.sh                       # 只做语法检查，不产出文件
PUSH=1 SER=zlorvcv8zpf6zdgq ./scripts/build_host2_serve.sh  # 产出并推到 rig

# 2) 重新安装本工程 APK（Shizuku UserService 也从 APK 加载，必须一起换）
# 3) 诊断页打开「host2 常驻（--serve / socket）」；首次合成会自动拉起 daemon
```

`host2_serve` 是**独立文件名，不覆盖 `host2`**。`run_dev.sh` 那条一次性路径原封不动；
rig 里没有 `host2_serve`、Shizuku 没授权、daemon 起不来或中途失败，`Host2ShellBackend`
都会自动回退一次性 exec。想彻底关掉：诊断页关开关，或删掉设备上的 `host2_serve`。

协议（TCP loopback，行是 ASCII，PCM 是二进制，同一连接可连发多句）：

| 方向 | 格式 | 说明 |
|---|---|---|
| C→S | `P\n` / `Q\n` | 探活（回 `PONG\n`）/ 让 daemon 退出 |
| C→S | `V\n` → `VER 2\n` | 能力协商：v2 才支持 `O`；旧二进制回 `X` 并断开探测连接 |
| C→S | `A <token>\n` | 仅当 `--token` 非空；默认空串不校验 |
| C→S | `O <n>\n` + n 行 `<type>\t<key>\t<value>\n` | **本句完整选项快照**，作用于紧随其后的一条 `S`；`type` 0=string 1=int 2=bool 3=double |
| C→S | `S <speed> <text_len>\n` + UTF-8 文本 | 单句 ≤ 64 KB |
| S→C | `D <n>\n` + n 字节 PCM | **每块回调立刻发一帧**（流式的关键） |
| S→C | `E <total>\n` / `X <len>\n` + 文本 | 本句结束 / 失败 |

`O` 让每句选项真正下发到复用的引擎句柄：19 项扩展选项 + 基础 `tts_pitch` /
`tts_volume` 共 21 项（基础 `tts_speed` 仍走 `S` 行），而不是只吃启动时的 3 个
env 档位。一次性 exec 后端没有 pitch/volume 通道，会静默跳过这两项。
引擎没有 `clearOption`，daemon 会把「上一句有、本句没有」的键回填中性值，
避免情感 / 风格跨句残留；`tts_*` / `voiceconv_*` 之外、以及初始化钉死的保留键一律拒收。
app 侧在建合成连接之前用独立连接做一次 `V` 协商并缓存结果，旧二进制自动按 v1 行为继续。

daemon 在加载模型**之前**就 `bind + listen`：app 侧「端口能连上」即视为已启动，
首次请求会在内核 backlog 里排队，等 init 完成后才被 accept，所以首次延迟只付一次。

### 真机联调结果（2026-11-12，Redmi Note 8 Pro / Shizuku root）

`PUSH=1 ./scripts/build_host2_serve.sh` → `./gradlew assembleDebug` → `adb install -r`，
诊断页开关默认开。实测：

| 项 | 结果 |
|---|---|
| 首次拉起 daemon → 出声 | 约 2 s（含引擎 init）；`host2_serve` 以 root detach 常驻 |
| 第二句（同一 PID 复用） | 约 1.1 s 返回 157920 B PCM |
| 连续 10 句（Legado 5 句 × 2 次） | 全部由同一 daemon PID 完成，无失败、无重启 |
| 直连 socket 协议 | `PING→PONG`；同一连接连发 2 句，`E` 计数一致；首帧 0.41 s、末帧 1.09 s（10 帧） |
| `/tts` vs `/tts/stream` 首字节 | 单句 1.42 s → 0.019 s；5 句长文 6.80 s → 0.044 s |
| 回退 | 移走 `host2_serve` + 发 `Q` 停 daemon 后，明确报出原因并自动用一次性 `run_dev.sh` 播完 |
| 自愈 | 恢复二进制后下次合成自动拉起新 PID，约 2 s 出声 |
| 纯标点 / 空白片段 | app 侧直接拦掉（补 80 ms 静音）；直连 daemon 兜底 0.15 s 返回，不再空等 120 s |
| Legado 实读（2026-11-12 修复后） | 连续推进到第 50 句，`onError=0`、daemon `illegal text` 零新增 |

调试手法：`adb forward tcp:18945 tcp:18945` 后可直接用 socket 发 `P` / `S 10 <len>`；
`adb forward tcp:18765 tcp:8765` 后可用 `curl` 测 HTTP。daemon 日志在 rig 的
`serve_boot.log`。`/tts/stream` 的 WAV 头把 RIFF/data 长度填 `0x7fffffff`（流式常见写法），
按头解析的工具会读出巨大"时长"，但字节流本身是完整的。
只有「已吐出音频却没等到结束标记」才累计 3 次后 `_exit(70)` 自愈；纯标点/空白
这类无音频片段不再算故障，直接补静音继续，不会重启 daemon。

流式出口：

- **系统 TTS**：`BdTtsService` 用 `EngineController.synthesizeStream()`，第一块 PCM
  到达才 `callback.start()` 并开始 `audioAvailable`，不再等整章合成完。
- **Legado**：`/tts` 保持整段 WAV（兼容性最好，内部已走 daemon）；新增实验性
  `/tts/stream`，HTTP chunked + RIFF/data 长度填 `0x7fffffff` 的 WAV 头，PCM 每帧一个
  chunk。部分客户端会先缓存完整响应或要求 `Content-Length`，这种就用 `/tts`。
- **App 试听**：仍走 `synthesize()` 攒整段，但也自动享受常驻（第二次起几乎瞬时）。

改了声音预设后点诊断页「重启常驻引擎」：`tts_silence_duration` 等档位是 daemon 启动时
从 env 读一次固定下来的，重启才会重读（语速 `speed` 每次请求都传，不需要重启）。

---

## 7）已知边界（别当承诺）

1. **只有 arm64-v8a**：rig 里没有 32 位库闭包；x86 模拟器必须支持 ARM64 转译。
2. **采样率写死 24000**（`tts_rate/sample_rate/custom_sample_rate`）：换 16k 代音色要同步改 C 层与服务常量。
3. **PCM 整段返回**：合成完才一次性喂 `audioAvailable`（句中延迟 1–2 s）；流式可后续用
   ring buffer + 跨线程 JNI 升级。长文本在 `BdTtsService.splitText()` 里按句切开再拼。
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
   只有 `dlopen(RTLD_GLOBAL)`，且受 linker namespace `clns-7` 限制）；2026-11-12 已在
   原生 arm64 真机上确认同样崩溃，见第 12 条。
9. **native 崩溃兜不住 AUTO 降级**：SIGSEGV 直接杀进程，Java 层的「init + 冒烟失败 →
   降级 HOST2_SHELL」只在抛 Java 异常时有效。要真正兜底，需把进程内 JNI 放进独立
   `:native` 进程，或直接用 Shizuku 后端。现在的做法是**跨启动记忆的崩溃守护**：
   上次死在 native 后 AUTO 直接跳过进程内后端（也可在诊断页手动强选 `inprocess` 重试）；
   首次遇到崩溃的那一次仍然会闪退，无法避免。
10. **在线/克隆/情感等选项只做了静态枚举**，没有实接；清单在 `docs/PRONUNCIATION_KNOBS.md`。
11. **MuMu 上 Shizuku 已启用，但 `HOST2_SHELL` 仍出不了声（转译环境限制）**：用
    `adb root` + Shizuku v13.6.0 官方启动器 `libshizuku.so` 以 root 拉起 server，
    给 `com.bd.tts` 授权后，logcat 实证 `ShizukuProvider: binder received` →
    `UserServiceManager: com.bd.tts:midu-files` → `RemoteFileService` 以 root 启动
    → binder 回传，**整条 Shizuku/UserService 链路打通**。但执行 ARM64 rig 时：
    保留 `LD_PRELOAD` → houdini64 拒绝加载 ARM64 `log_shim.so`；临时去掉
    `LD_PRELOAD` → host2 能启动到 `[setOpt] license_directory`，却因 `cronet_shim4`
    拦截失效而 `StartWithParamsInternal ### NO global context` → `libsscronet.so` SIGSEGV。
    即：**shim 必须在原生 arm64 上靠 `LD_PRELOAD` 注入**，x86_64+houdini 的 MuMu 做不到；
    这不是 Shizuku 或应用代码的问题。换真机/原生 ARM64 镜像即可继续验证。
    完整实操见 `docs/SHIZUKU.md` §6。
12. **进程内 JNI 在原生 arm64 真机上同样崩溃**（2026-11-12，Redmi Note 8 Pro）：
    `libmidu_bridge.so` → `JNI_OnLoad` → `createEngineToNative` → 资源根全部正常，卡在
    `initEngineToNative` 约 30 s 后报 `cronet_initializer.cc: CronetEngineSpeechWrap
    Failed to prepare environment: -300`，随即 `Fatal signal 11 (SIGSEGV)` in
    `midu-ui-bg`，进程退出、界面回桌面。这不是 ABI / 转译问题，是 app 进程没有
    `LD_PRELOAD` 导致 shim 失效；信号杀进程，Java 层 catch 不到。现加
    `inproc_inflight` / `inproc_crashed` 崩溃守护（`Prefs` / `EngineController`）：AUTO
    会自动绕开进程内后端，首页「运行状态」显示守护状态；诊断页强选 `inprocess`
    且成功一次即自动解除。
13. **一次性 host2 仍是「一句一个进程」**：`splitText()` 按句切开，每段都重新 `dlopen`
    + 加载字典/模型。§6.1 的常驻 daemon 已实现来消除这项开销，但**必须先把
    `host2_serve` 编出来推到 rig**，没推就一直是这条慢路径。单次 `exec` 加了 90 s 超时
    强杀（返回 124），避免卡死时 Binder 调用永久挂住、界面一直停在「合成中」。
14. **2026-11-12 稳定性加固（需重新构建安装才生效，Shizuku UserService 同理）**：
    引擎合成不再用方法级 `synchronized`（原来 host2 合成期间改规则会让主线程抢不到
    `invalidate()` 的锁而 ANR）；`AudioTrack` 改为循环分块写 + `finally` 释放，并等
    播放头走完再 `stop()`，可中途停止；`host2` 失败会把 rig 日志尾部直接带回异常信息、
    临时 `.pcm/.log` 无论成败都清理；Legado HTTP 服务停止再启动会重建线程池。
15. **常驻 daemon 的多轮复用已在真机实测通过**（2026-11-12）：同一个 daemon PID
    连续完成 16+ 句、同一 TCP 连接连发 2 句，`E` 计数与实收字节一致，没有触发
    `_exit(70)` 自愈，也没有崩溃或重启。引擎 init 日志里会出现两行
    `[RECOVER] libsscronet worker crash → pthread_exit(thread)`，这是工装 cronet shim
    原有的兜底行为，不影响合成。C 侧「连续两句失败 `_exit(70)`」与 app 侧「任意失败
    回退一次性 exec」两道保险仍保留，且都已在真机验证（移走二进制 → 自动回退；
    恢复后 → 自动重新拉起）。
16. **`/tts/stream` 是实验性路由**：chunked + 长度未知 WAV 头（RIFF/data 填
    `0x7fffffff`）不是所有播放器都吃——实测 `curl` 能拿完整字节流，但 Python `wave`
    按头里的哨兵值会算出 44739 s 的"时长"；要求 Content-Length 或先缓存整段的播放器
    仍应走 `/tts`。收益实测：单句首字节 1.42 s → 0.019 s，5 句长文 6.80 s → 0.044 s。
    daemon 的 `tts_silence_duration` 等档位在启动时固定，改预设后需要在诊断页点
    「重启常驻引擎」。
17. **系统 TTS 引擎曾被规则定时更新带崩**（2026-11-12 真机实测发现并修复）：
    `RuleUpdateService.schedule()` 用了 `NETWORK_TYPE_ANY`，但 Manifest 缺
    `android.permission.ACCESS_NETWORK_STATE`，`JobScheduler.schedule()` 抛
    `SecurityException`；异常从 `BdTtsService.onCreate()` 漏出去，变成
    `Unable to create service`，系统 TTS 绑定超时、客户端 `onInit(-1)`。现已补权限，
    并把整个 `schedule()` 包进 `try/catch(Throwable)`：规则自动更新是可选功能，
    绝不能影响 TTS 引擎启动。修复后实测 `Connected successfully to TTS engine`、
    `onInit(0)` → `speak(0)` → 流式合成 → `AudioTrack` 播完 99200 帧 → `onDone`。
18. **阅读 App「朗读一句就停、再点没反应」**（2026-11-12 Legado 真机实测发现并修复）：
    Legado 按句切章节时会夹带只含引号/破折号/空白的片段（日志实证
    `grouping sub sentence ”`）。引擎 frontend 报 `no valid char after filtering` →
    `pipeline has error, illegal text!`，既不出 PCM 也不发 1409 结束标记；daemon 原本
    按 `WAITSEC=120` 空等，而 `serve_loop` 是单线程 `accept`，一个连接就把后续请求
    全堵在 backlog，`callback.error()` 再让 Legado 的 `UtteranceProgressListener` 停掉
    整章朗读（实测第二次点朗读 91 s 才出第一个 `onStart`）。三层修复：
    ① `Speakable.hasReadable()` 在 `EngineController` / `Host2ShellBackend` /
    `BdTtsService.splitText()` 拦掉无可发音字符的片段，补 80 ms 静音并正常 `done()`；
    ② daemon 收到 `1003`（Error callback one）立即结束本句并补静音帧 + `E`，
    `<64 B` 的状态回调不再被当成 PCM 发，单句等待上限 120 s → 20 s 且探测客户端断开；
    ③ `BdTtsService` 逐句 `try/catch`，单句失败只跳过该句，不再 `error()` 停整章。
    实测：直连 daemon 纯标点 20 s → **0.152 s**；系统 TTS 连续 5 句（含 2 句纯标点）
    全部 `onDone`、`onError=0`、总 6.4 s；Legado 实读连续推进到第 50 句，
    `illegal text` 零新增。**装好新包后旧朗读会话不会自愈**——Legado 前台服务可能
    还挂在等 `onDone`，需在界面点「下一句」或重新点朗读重新绑定一次引擎。

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
4. 「声音调校」页改参数会**同时**作用于系统 TTS 与 Legado 接口；只显示真机 A/B 实测有效的项（语速/音高/音量/句尾静音/子句长度/情感/立体声展宽），实测无响应的 22 项已隐藏。

参数表、证据分级、HTTP 路由与导入配置格式见 [`docs/LEGADO_AND_KNOBS.md`](docs/LEGADO_AND_KNOBS.md)。
