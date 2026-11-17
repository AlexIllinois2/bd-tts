# 声音参数全暴露 & Legado 对接（0.2.0）

> 本轮目标（用户原话）：「出声不着急，先做功能和界面；UI 太老了，用 M3 最新那套，仿 KSU Next 风格；
> 功能上把 so 的声音调整能力都暴露出去（重点对接 Legado），自身也保留调整能力。」

## 0）这一版新增了什么

| 层 | 文件 | 作用 |
|---|---|---|
| 参数表 | `sound/Knobs.java` | 28 个可调 `tts_*` / `voiceconv_*` 键的**唯一真相源**：标题、类型、范围、默认值、分组、证据等级、是否默认下发 |
| 预设 | `sound/SoundProfile.java`、`SoundProfiles.java` | 多套参数预设，JSON 持久化、导入导出、内置四套（标准 / 沉稳播报 / 轻快 / 小说慢读） |
| 下发 | `engine/EngineOption.java` → `NativeEngine.synthEx` → `midu_engine_synth_ex` | 从「固定三个韵律参数」升级成**任意选项通道** |
| 护栏 | `jni/fake_jni.c` 的 `RESERVED_KEYS` | UI/URL 无法覆盖会破坏授权与 PCM 回调的初始化档位 |
| 系统 TTS | `BdTtsService.java` | 改用当前预设；`speechRate%` / `pitch%` 叠乘在预设档位上 |
| Legado | `legado/LegadoHttpServer.kt` 等 | 本机 HTTP 朗读引擎，返回 `audio/wav`，可生成可导入的 HttpTTS JSON |
| UI | `ui/*` | Jetpack Compose + Material 3（动态取色、大圆角分组卡片、图标化设置项、折叠顶栏、底部导航） |

## 1）参数模型：数据驱动，不是逐个手写控件

`Knobs.ALL` 里每一项是一个 `Knob`：

```
key / title / desc / group / type(string|int|bool|double) / def / min..max / unit / choices / evidence / defaultEnabled
```

证据等级沿用 `PRONUNCIATION_KNOBS.md`：

| 等级 | 含义 | 默认下发 |
|---|---|---|
| `VERIFIED` | rig 逐条实测 / 本工程已接 | ✅ |
| `RIG_ENV` | rig 的 `host2.c` env 里验过键存在 | ✅ |
| `STATIC` | 只在 `.so` 字符串里出现，**未实测** | ❌（用户在 UI 里逐项打开） |

### 2026-11-17 真机 A/B 实测：无效项已从声音页隐藏

用 `scripts/probe_knobs.py` 走 App 本地 HTTP `/tts`，固定文本/基础参数，
对每个高级项取两个极端值各合成两次，比较 WAV 时长与能量包络（引擎不钉种子，
只看宏观量）；可疑项再用长文本/英文文本针对性复测。结论：

| 项 | 结论 | 依据 |
|---|---|---|
| `tts_silence_duration` | ✅ 有效 | 0→2000ms 使句尾多 2.6 秒留白 |
| `tts_sub_sentence_length` | ✅ 有效 | 长文本 0→8 字改变停顿与总时长（大值>文本长度时无效） |
| `tts_emotion` | ✅ 有效 | sad/angry 改变韵律；happy 接近默认 |
| `bd_stereo_width` | ✅ 有效 | 本地后处理，非引擎键 |
| 其余 22 项（比例档、分句开关、前端覆写、淡入淡出、输出编码/压缩/落盘、回调、并发、克隆、变声等） | ❌ 无效 | A/B 时长/能量/包络完全一致，引擎无响应；部分本工程链路不用 |

无效项以 `Knob.visible=false` 标记：仍留在 `Knobs` 表（历史预设/HTTP 覆盖不受影响），
但 `Knobs.grouped(false)` 在声音页过滤掉它们。要复测可跑
`python3 scripts/probe_knobs.py --repeat 2`（需先启动本地朗读服务并 `adb forward tcp:8765 tcp:8765`）。

分组：`韵律·已实测` / `韵律·比例档` / `分句与停顿` / `前端与发音覆写` /
`情感与风格` / `音频输出` / `音频输出·本地后处理` / `回调与性能` / `声音克隆`。

其中 `音频输出·本地后处理`（`bd_` 前缀的键，如 `bd_stereo_width`）**不下发引擎**：
`SoundProfile.toOptions()` 会跳过它们，由 app 在 PCM 出口（系统 TTS / Legado HTTP /
试听播放三条路共用 `sound/StereoWiden.java`）做后处理。改完即生效，无需重建引擎。
Legado 侧仍可按普通 knob 一样用 `/tts?bd_stereo_width=0` 这类参数按次覆盖。

Compose 的「声音调校」页按 `Knobs.grouped()` **自动生成控件**：整数→滑块、布尔→开关、
枚举→下拉、字符串→输入框；实验性分组默认折叠，展开后每项还有一个「下发此选项」开关。
所以以后要再暴露一个能力，只需要往 `Knobs` 里加一行，不用改 UI。

### 下发链路

```
SoundProfile.toOptions()  →  List<EngineOption>
        ↓ (拍平成 keys / values / types 三条数组)
NativeEngine.synthWithOptions(...)  →  synthEx (JNI)
        ↓
midu_bridge.c: do_synth
        ↓
fake_jni.c: midu_engine_synth_ex
        ├─ 先 setStr/Int 基础三件套 (tts_text / tts_speed / tts_pitch / tts_volume)
        └─ 再按序 apply_option() → setOptionString/Int/Boolean/DoubleToNative
```

`apply_option()` 会：
- 挡住 `RESERVED_KEYS`（`tts_work_mode` / `license_directory` / `tts_data_callback_mode` /
  `tts_rate` / `tts_enable_player` 等 20 个钉死档位）；
- 只放行 `tts_*` / `voiceconv_*` 前缀；
- 把每条实际下发的选项打到 logcat（`midu-fakejni`），便于在真机上 A/B 验证。

**host2 shell 后端能力有限**：`src/host2.c:658-665` 只读 `TTS_SPEED` / `TTS_SIL` /
`TTS_SUBLEN` / `TTS_SPEED_RATIO` 四个环境变量。`Host2ShellBackend` 会把对应选项映射成 env，
其余明确忽略并打日志。想要全部能力，用「进程内 JNI」后端。

## 2）为什么重点走 Legado 的 HTTP 引擎，而不是系统 TTS

读 `D:/weiheng/proj/legado` 源码得到的硬约束：

- `TTSReadAloudService.kt:312-319` 给系统 TTS 的 Bundle **只有** `KEY_PARAM_VOLUME`。
  系统 TTS 路径拿不到自定义参数（音高、情感、前端覆写都传不进来）。
- `HttpReadAloudService.kt` + `HttpTTS.kt` 走 URL 规则，`{{}}` 里能拿到：
  `speakText`、`speakSpeed`(5–50)、`currentToneID`、`currentSpeakerName`、
  `currentEmotionName`、`currentEmotionTag`、`currentSpeechRouteJson`；**没有 speakPitch**。
- URL 规则形如 `http://host/tts,{options JSON}`，options 支持 `method` / `headers` / `body`；
  响应必须是**音频二进制流**（`audio/wav` / `audio/mpeg`），返回 JSON/text 会失败。
- `HttpTTS` 字段：`id / name / url / contentType / concurrentRate / synthesisThreadCount /
  lastUpdateTime / speakersJson / emotionsJson / header / loginCheckJs / jsLib ...`。

所以本工程做了一个**前台服务里的本地 HTTP 服务**：Legado 用「网络朗读」直连本机，
全部参数通过 query / body 传进来，绕开系统 TTS 的参数限制。系统 TTS 引擎本身保留
（阅读器仍可在系统设置里选 BD TTS），两条路都通。

## 3）HTTP 接口

默认 `127.0.0.1:8765`（可在 UI 里改成 `0.0.0.0` 供局域网调试，默认关闭）。

| 方法 | 路径 | 返回 |
|---|---|---|
| GET | `/` | 说明页 |
| GET | `/health` | 引擎状态、当前预设、knob/profiles 数量、请求计数 |
| GET | `/knobs` | 全部可调能力（含证据等级、范围、默认值） |
| GET | `/profiles` | 声音预设 |
| GET | `/legado.json` `/import` | 可直接粘进 Legado 的 HttpTTS JSON |
| GET / POST | `/tts` | `audio/wav`（24 kHz / s16le / 单声道，44 字节 RIFF 头） |

`/tts` 参数（query、form-urlencoded body、JSON body 都支持；body 优先）：

| 参数 | 说明 |
|---|---|
| `text` / `speakText` | 朗读文本（必填），服务端按句切分后逐句合成再拼 PCM；支持内联拼音（见下） |
| `speed` / `speakSpeed` | Legado 档位 5–50，`20 = 100%`；`>60` 直接当百分比 |
| `pitch` | 音高百分比，100 = 正常 |
| `tone` / `toneId` / `profile` | 发言人 toneID，即本地预设 id |
| `emotion` / `tag` | 情绪名或 `[情绪标签]` |
| `rules` | 覆盖全局规则开关：`on/off`、`1/0`、`true/false`。短路全部规则但不改各规则文件自己的开关；覆盖持续生效直到再次传参或在规则页改回。`/health` 的 `rulesEnabled` 可读当前值 |
| 任意 `tts_*` / `voiceconv_*` | 直接覆盖对应 Knob；`STATIC` 项受「允许实验性覆盖」开关约束 |

### 内联拼音（`[[字词|pin1 yin1]]`，2026-11-13 真机实测）

`text` 里可以临时注音，不用写规则、不用重建引擎：

```
text=[[行人|xing2 ren2]]如织，脚步匆匆。
text=他[[重|zhong4]]要的[[种|zhong3]]子丰收了。
```

- 拼音用空格分隔逐字对应，声调 1–5；命中时该句自动切 `tts_text_type=ssml`，
  引擎按 `<phoneme alphabet="py">` 逐字覆写（日志 `overwrite pinyin to …`）；
- **拼音个数必须与汉字个数相等**，否则该句引擎会近乎静音——app 侧已做校验：
  任一标签不合法就只摘掉 `[[ ]]` 标记按普通文本读，绝不喂坏 SSML；
- 直接传整段 `<speak>` SSML 也原样支持；系统 TTS 与 App 试听同样吃这套语法
  （统一在 `engine/PinyinInline.java` 转换，所有 `EngineController` 入口生效）。

示例：

```bash
curl "http://127.0.0.1:8765/tts?text=%E4%BD%A0%E5%A5%BD&speed=25&tts_silence_duration=180" -o out.wav
```

### 导入 Legado 的配置

UI「Legado 对接」页一键复制：

```json
[
  {
    "id": 20261109001,
    "name": "BD 离线 TTS（本机）",
    "url": "http://127.0.0.1:8765/tts,{\"method\":\"POST\",\"headers\":{...},\"body\":\"text={{java.encodeURI(speakText)}}&speed={{speakSpeed}}&tone={{java.encodeURI(currentToneID)}}&emotion={{java.encodeURI(currentEmotionName)}}&tag={{java.encodeURI(currentEmotionTag)}}\"}",
    "contentType": "audio/wav",
    "concurrentRate": "0",
    "synthesisThreadCount": 1,
    "speakersJson": "[{\"speakerName\":\"标准\",\"toneID\":\"std\"}, ...]",
    "emotionsJson": "[{\"emotionName\":\"高兴\",\"emotionTag\":\"[高兴]\"}, ...]"
  }
]
```

用 form-urlencoded POST 而不是 JSON body 是刻意的：Legado 会把 `speakText` 原样替换进
字符串，中文/引号/换行交给 `java.encodeURI`，不会破坏 JSON（与官方百度示例同款写法）。
`id` 固定，重复导入会覆盖同一条，不会越导越多。

### 发言人 / 情绪映射

- **发言人**：每个本地声音预设变成一个 `toneID`。Legado 多角色朗读选谁，服务端就用谁那套参数。
- **情绪**：8 个内置情绪（平静/高兴/悲伤/愤怒/温柔/紧张/庄重/耳语）映射成
  **已实测**的语速 / 音高 / 音量微调，所以情绪一定有可听的效果；
  `tts_emotion` 这类静态证据项只在用户主动启用时才会附带下发。

## 4）UI

Jetpack Compose + Material 3（`compose-bom 2025.10.00` / `material3 1.4.0` / Kotlin 2.3.10 /
AGP 8.13.2 / compileSdk 36），对齐本机已验证可构建的组合。

- Android 12+ 跟随系统动态取色，低版本退回自带配色；
- `LargeTopAppBar` 折叠顶栏 + 底部 5 个 Tab；
- 24–28dp 大圆角分组卡片、42dp 圆角图标底、状态 Hero 卡、Pill 标签；
- 5 页：总览 / 声音调校 / 发音规则 / Legado 对接 / 语音源·诊断；
- 0.1.x 的 Java View 设置页保留为「经典设置」，系统 TTS 设置的齿轮入口改指新版 `ui.MainActivity`。

## 5）边界（照旧，不夸口）

- 「已实测」只有 `tts_speed` / `tts_pitch` / `tts_volume` 以及 host2 env 验过存在的三项，
  其余扩展项**只是 .so 字符串证据**。所有非空扩展项都会下发（调用方参数优先）：
  进程内 JNI 走 `setOption*ToNative`，常驻 daemon 走协议 v2 的 `O` 快照逐句下发
  （19 项扩展 + 基础 `tts_pitch` / `tts_volume` = 21 项，`tts_speed` 走 `S` 行），
  一次性 host2 仍只认 3 个 env 档位、会静默跳过 pitch/volume。
  下发后是否有效要看引擎日志，不要拿 PCM 字节比对（不钉种子同文本也不同）。
  实测 `tts_pitch` 改档会明显改变过零率；`tts_volume` 只有 0（静音）与非 0 的差别，
  50 与 100 幅度几乎一致，进程内 JNI 也是同一个 `setInt`，属引擎自身行为。
- 不修改 `libspeechengine.so` 一个字节；授权仍走合法 `licbag` 原件 +
  假 JNIEnv 回报宿主包名 `com.lechuan.mdwz`。
- MuMu 的 x86_64 + houdini 依旧出不了声（arm64 `LD_PRELOAD` shim 注入不了），
  但功能、UI、HTTP 接口、Shizuku 链路可以在其上验证；出声仍需原生 arm64 真机。
- 本地 HTTP 默认只绑 loopback；打开 `0.0.0.0` 等于把无鉴权的合成接口暴露到局域网，
  只在可信网络里开。
