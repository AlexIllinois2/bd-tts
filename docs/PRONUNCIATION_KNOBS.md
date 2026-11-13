# 发音可调能力清单（REA 静态盘点）

> 目的：把 `libspeechengine.so` 里**所有能改发音/韵律的口子**记下来，方便以后继续利用。
> 本文区分「已实测」「静态字符串证据」「待验证」三档，不把字符串当成行为承诺。

## 0）这次怎么查的（可复现）

- 已按你的要求装了 **REA（逆向工程任何目标）** skill。当前会话里没有注册 REA 的 MCP 分析服务，
  因此走 REA 允许的「本地静态证据」路线：直接对仓库里的 ELF 原件做字符串与依赖闭包盘点，不执行目标。
  （本机 Linux 工具链：JDK 17 (Temurin) / Android SDK 34~37 / NDK 29.0.13846066 /
  CMake 3.22.1 / adb，并实际构建出 debug APK；当初写成「本机没有工具链」是误判，已更正。
  注意 `ndk/27.0.12077973` 只是空壳目录，实际用的是 29。）
- 目标：`host2rig/lib/libspeechengine.so`（21,967,568 B，md5 `3485827bce49163a7ac7cf45578af5d5`，
  与 `MANIFEST.md5` 可逐枚对账）。
- 方法（可重跑）：

```bash
cd /home/v/.local/project/midu-tts
python - <<'PY'
import re
b=open('host2rig/lib/libspeechengine.so','rb').read()
ss=[x.decode('ascii','ignore') for x in re.findall(rb'[ -~]{3,}',b)]
for pat in [r'^tts_[a-z0-9_]+$', r'(g2p|pinyin|rule|dict|ssml|overwrite|white list|polyphone)']:
    print('\n==',pat)
    for s in dict.fromkeys(x for x in ss if re.search(pat,x,re.I) and len(x)<180): print(s)
PY
```

- 依赖闭包（`.so` 名字符串）：`libOpenSLES / libandroid / liblog / libsscronet /
  libttboringssl / libttcrypto / libstdc++ / libz`；rig 另外要补 `libicuuc.so`、
  `libicui18n.so`、`libc++_shared.so`、`libspeexdsp.so` 和三枚 shim。

---

## 1）规则文件层 —— **已实测可用**（本工程的主路径）

| 口子 | 位置 | 状态 |
|---|---|---|
| 中文多音字/声调规则 | `<root>/zh-cn/frontend/g2p/mnc/unified_rule.txt` | ✅ rig 已验；本工程三层合并就写这里 |
| 英文附加词典 | `<root>/zh-cn/frontend/g2p/eng/additional_dict_path.txt` | ✅ rig 带原件（20 条，ARPAbet 音素串） |
| 规则条件类型 | `TEXT(...)` / `!TEXT(...)` / `WORD_INSIDE(n)` / `!WORD_INSIDE(n)` / `PINYIN(regex)` / `POS(regex)` | ✅ vendor 文件实物 |
| 上下文正则语法 | `(目标)` 圈定作用字、`[a\|b]` 备选、`[^城]` 否定字符集、`(?:...)` 非捕获、`?` 可选、`^`/`$` 边界 | ✅ 上游 `rule_patch.txt` 实物 |
| 读音列写法 | `hua4`（完整拼音+调）、`*5`（只改声调/沿用）、`none5`（儿化占位）；多字词逐字对应 | ✅ 上游产物实物 |
| 规则生效日志 | 引擎自印 `generate a char for rule:{}`, `apply rule for text({}) pinyins({})`, `Pinyin Rules Loaded, {} rules loaded in total`, `pinyin list after apply rules:{}` | ✅ rig §5 十条标注逐条命中 |

**关键工程结论**：规则文件是**引擎 init 时读**的，改规则必须**销毁并重建引擎**。
本工程用「规则代数 generation」触发重建（`EngineController.invalidate()`）。

---

## 2）字典 / 白名单层 —— 静态字符串证据，**尚未接进本工程**

引擎内部符号与日志串（均在 .so 里现读）：

| 内部名 / 日志 | 推测作用 | 状态 |
|---|---|---|
| `additional_dict_path_`、`treat word [{}] by [additional_dict_map_]` | 英文词 → 音素词典 | ✅ 已有文件，本工程会自动同步 |
| `pinyin_pron_dict_file_`, `pinyin_standard_dict_file_`, `pinyin_dict_file_`, `DictUtil load ... failed` | 拼音发音词典 / 标准词典 | ⚠️ 路径来自资源根配置，未验 |
| `pinyin_cmu_file_`, `load pinyin_cmu dict error, file:{}`, `english g2p load phondict or py_cmu dict failed` | 英文 G2P 的 CMU 词典 | ⚠️ 未验 |
| `char_dict_file_`, `word_dict_file_` | 字/词词典 | ⚠️ 未验 |
| `Load white list file failed: {}`, `get white list pinyin:{} succeed for key:{}`, `ApplyWhiteListOverwrite for word:{} succeed` | **白名单覆写拼音**（另一条独立于 unified_rule 的改音通路） | 🔬 值得下一步挖 |
| `mt_polyphone_char_id_file`, `polyphone_id_path`, `polyphone_infer_`, `Polyphone` 类 | 多音字模型预测（规则之前/之后） | ⚠️ 未验 |
| `pinyin_modify_rule_path_`, `pinyin_rule_ex_path_`, `pinyin_rule_path_`, `PINYIN_MODIFY_DEV/DER/AS/SP/CD/PER` | 变调、儿化、方言声调等修改规则的多个文件与分支 | 🔬 值得下一步挖 |
| `pinyin list after apply dialect tone modify:{}`, `apply_dialect_tone_modify_`, `dialect_` | 方言声调修改开关 | 🔬 值得下一步挖 |
| `pinyin_tag_json_file_` | 拼音标签配置 | ⚠️ 未验 |

> 这些文件名大多不是硬编码路径，而是 `settings_file_dir` / 资源根里的配置项。
> 下一步在 MuMu 上把 `settings_file_dir` 指到我们的有效根，用 logcat 抓
> `Failed to load ... file: {}` 就能把「引擎想读哪些文件」逐条问出来。

---

## 3）setOption 选项层 —— host2 实测档 + 静态枚举

### 3.1 rig 已逐条验证的初始化档位（不可乱改）

| key | 值 | 作用 |
|---|---|---|
| `tts_work_mode` | `2048` | 离线 |
| `engine_name` / `authenticate_type` | `tts` / `pre_bind` | 预绑定本地校验分支 |
| `license_directory` / `settings_file_dir` / `tracking_data_path` | 资源根 | 凭证、配置、日志 |
| `tts_scenario` / `tts_text_type` | `novel` / `plain` | 场景 / 文本类型 |
| `tts_voice_offline` / `tts_voice_type_offline` | `other` / `BV001_24k_streaming` | 音色 |
| `tts_off_resource_path` | `{"base":"…/zh-cn","voice":[{"BV001_24k_streaming":"…/BV001_24k_streaming"}]}` | 资源根 JSON |
| `tts_rate` / `sample_rate` / `custom_sample_rate` | `24000` | BV001 这一代；换 16k 代要同步改 |
| `tts_with_frontend` / `tts_frontend_type` | `0` / `tnSimple` | 引擎自带前端 |
| `tts_data_callback_mode` | `2` | PCM 走 `onSpeechMessage(1400,…)` |
| `tts_enable_player` / `prevent_player_creation` | `0` / `1` | 不建播放器 |
| `tts_enable_progress` | `1` | 进度回调 |

### 3.2 每次合成可调（本工程已接）

| key | 范围 | 本工程映射 |
|---|---|---|
| `tts_text` | 文本 | `SynthesisRequest.getText()`（长句按句切） |
| `tts_speed` | 1..20（缺省 10） | 系统 `speechRate%` × 设置档 |
| `tts_pitch` | 1..20（缺省 10） | 系统 `pitch%` × 设置档 |
| `tts_volume` | 0..100 | 设置档 |
| `tts_silence_duration` | ms | rig env `TTS_SIL` 已验存在 |
| `tts_sub_sentence_length` | 字 | rig env `TTS_SUBLEN` 已验存在 |
| `tts_speed_ratio` | int | rig env `TTS_SPEED_RATIO` 已验存在 |

### 3.3 .so 里枚举到、但**尚未实接/未验**的选项（下一步的料）

| key | 疑似作用 | 证据等级 |
|---|---|---|
| `tts_frontend_overwrite_label` | **单次合成的拼音/标签覆写**（配合 SSML，见 §4） | 静态字符串 |
| `tts_speed_ratio` / `tts_volume_ratio` / `tts_pitch_ratio` | 比例型三件套，和整数档并存 | 静态字符串 |
| `tts_emotion` / `tts_style_name` / `tts_voiceclone_style` | 情感 / 风格 | 静态字符串 |
| `tts_pure_english_opt` | 纯英文优化 | 静态字符串 |
| `tts_split_sentence` / `tts_sub_sentence_length` | 分句 | 静态字符串（后者 rig 已验存在） |
| `tts_fadein_duration` / `tts_fadeout_duration` | 淡入淡出 | 静态字符串 |
| `tts_encoding` / `tts_compression_rate` | 输出编码 / 压缩 | 静态字符串 |
| `tts_audio_path` / `tts_audio_file` / `tts_enable_dump` / `tts_audio_file` | 直接落盘音频 / dump | 静态字符串 |
| `tts_with_timestamp` / `tts_with_intent` / `tts_limit_cpu_usage` / `tts_concurrency_mode` | 回调附带信息 / 性能 | 静态字符串 |
| `tts_voice_online` / `tts_voice_type_online` / `tts_cluster` / `tts_uri` / `tts_address` / `tts_conn_timeout` | 在线链路（本工程不用） | 静态字符串 |
| `tts_use_voiceclone` / `voiceconv_*` 一族 | 声音克隆 / 变声（另一整条链） | 静态字符串 |

完整枚举（53 个 `tts_*` 键、18 个 `voiceconv_*` 键）由 §0 的脚本可复现。

> **2026-11-13 证据复核**：对 `Knobs.java` 里 22 个 STATIC 键逐个在引擎二进制
> （真机 `rig/lib/` 与 `vendor/` zip 双源）跑 `strings`，21 个确认命中
> （`tts_fadein/out_duration`、`tts_concurrency_mode` 各 ×2，其余 ×1）。
> 唯一无证据的是 `voiceconv_formant`——真实 `voiceconv_*` 一族里没有它，
> 已从 Knobs 表移除，换成真实存在的 `voiceconv_volume`。
> 注意：命中只证明引擎认识键名，是否有音效仍以实测为准（目前只实测过
> `tts_pitch` / `tts_volume` / `tts_silence_duration` / emotion 回填）。

---

## 4）SSML / 前端覆写层 —— **✅ 已实测可用（2026-11-13 真机）**

`.so` 静态证据 + Redmi Note 8 Pro（host2 daemon :18946，serve_py.log）实跑验证：

| 事实 | 证据 |
|---|---|
| `tts_text_type` **不在** SVO_RESERVED / RESERVED_KEYS 名单里，可每句经 setOption / O 快照切换 | rig `host2.c`；plain→ssml→plain 无残留（after_plain PCM 与基线一致） |
| 引擎**每句合成时**读 `tts_text_type`（非 init-only） | ssml 句后紧跟的 plain 句打印 `no need to overwrite g2p result as the text is not ssml` |
| `<phoneme alphabet="py" ph="xing2">行</phoneme>` 逐字覆写拼音 | `1 pinyins are parsed from: xing2` → `overwrite pinyin to xing2, word_index:3, text:行` |
| 多字词 `ph="zha1 dai4"` 逐字对应 | `2 pinyins are parsed` → word_index:4/5 各自覆写 |
| 一句内任意多个 phoneme 节点 | 种→zhong3、重→zhong4 同时生效 |
| **ph 拼音数 ≠ 节点汉字数 ⇒ 整句近乎静音** | `py text size: 2 does not match pinyins size:1`，实测仅 3840B |
| `yi2` 被引擎内部音位化为 `i2` | `overwrite pinyin to i2` |
| alphabet 白名单（.so rodata）：`jp-accent / expressive / ipa / ipa-pd / en_py`（`py` 是代码内立即数比较，不以 rodata 字符串出现） | §0 脚本可复现 |

**调用方语法（app 已实现，`engine/PinyinInline.java`）**：任何经
`EngineController.synthesize / synthesizeStream` 的文本（试听 / 系统 TTS / Legado HTTP）
支持 `[[字词|pin1 yin1]]`（拼音数=汉字数、声调 1-5、纯汉字），或整段 `<speak>` SSML 直通；
命中时自动给本句切换 `tts_text_type=ssml`（`withTextType` 同键替换不追加）。
校验不过只摘掉标记按普通文本读，绝不喂坏 SSML——数量不匹配会废掉整句（见上表）。

原静态证据串（留档）：SsmlParser / SsmlSplit / SsmlPhonemeNode / ApplyPinyinSsmlOverwrite
failed / `overwrite pinyin to {}, word_index:{}, text:{}` / `no need to overwrite g2p result
as the text is not ssml` / tts_frontend_overwrite_label。

`tts_frontend_overwrite_label` 的 label 串格式仍未知；phoneme 路已覆盖需求，暂不挖。

---

## 5）时长 / 读数口径（rig 实跑结论，做 A/B 对照时要用）

- `predicted duration = 10 ms × Σdur + 250`，`out.pcm 样本 = 240 × Σdur + 4000`，
  `out.pcm` 为 **s16le / 24 kHz / 单声道**。
- 实测默认句 165,920 B / pd 3540；冒烟两句 100,160 B(pd 2170) 与 71,840 B(pd 1580)。
- **不钉种子＝同文本两发出声不同**，所以「规则有没有生效」要看前端标注日志
  （`pinyin list after apply rules`、`generate a char for rule`），不要比对 PCM 字节。
- app 进程里不加载 `log_shim.so`（系统 liblog 是真的，日志进 logcat）；
  抓引擎标注用：`adb logcat -v time -s midu-fakejni:V | grep -E "pinyin list|apply rule|Processed"`。

---

## 6）未覆盖 / 未解释（诚实账）

- 条件关键字 `WORD_INSIDE(` / `POS(` / `PINYIN(` / `TEXT(` 在 .so 里**不是整串常量**，
  说明规则解析器是按通用 token 拆的；具体支持哪些条件类型目前只能从实物规则文件归纳。
- `pinyin_modify_*` / white list / dialect 三套通路是否和 `unified_rule.txt` 叠加、先后顺序如何，未知。
- `tts_frontend_overwrite_label` 的载荷格式未知。
- 引擎 `SendDirective` 的 STOP 指令码未验（本工程 `onStop()` 只做本地收流）。
- 32 位设备不在本包范围（rig 只有 arm64 原件）。

---

## 7）合规边界

REA 技能要求：只做自用学习 / 互操作 / 资产盘点，不做破解、绕过授权、窃取商业秘密。
本工程：不修改 `libspeechengine.so` 一个字节；喂的是设备上合法持有的
`speech_license.licbag` 原件；宿主包名按 rig 原样回报，未伪造校验判决；
厂商 `.so` / `ptl.dat` 只在本机复制到构建目录且已加 `.gitignore`，不随源码外传、不重新分发。
