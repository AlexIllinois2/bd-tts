# 发音规则：三份文件、优先级与合并语义

## 1）三份规则文件

| # | 层 | 文件（app 内） | 来源 | 可改 | 优先级 |
|---|---|---|---|---|---|
| 1 | vendor | `files/rules/vendor_unified_rule.txt` | 随 APK 的 `assets/rules/vendor_unified_rule.txt`，由 `prepare_vendor.py` 从 `host2rig/midu/zh-cn/frontend/g2p/mnc/unified_rule.txt` 复制 | 只读真相源 | **最低** |
| 2 | 上游产物 | `files/rules/upstream_unified_rule.txt` | `https://github.com/AlexIllinois2/midu_unified_rule` 的 `conf/unified_rule.txt`，定时自动检查更新 | 自动覆盖，可手动回滚 | **中** |
| 3 | 用户自定义 | `files/rules/user_rules.txt` | 设置页编辑、快速生成表单、SAF 导入导出 | 用户自由编辑 | **最高** |

**优先级：用户 > midu_unified_rule 产物 > vendor。**
合并结果写到 `files/rules/merged_unified_rule.txt`，并原子覆盖引擎真正读的
`<有效资源根>/zh-cn/frontend/g2p/mnc/unified_rule.txt`。

> 上游项目本身的结构：`conf/unified_rule_vendor.txt`（只读原始）、`conf/rule_patch.txt`
> （`移除:` / `新增:` 补丁）、`src/organizer.ts`（去重/分组/排序后写出 `conf/unified_rule.txt`）。
> 上游 `rule_patch.txt` 的内容已全部整理进其产物 `unified_rule.txt`，本工程只消费**产物**本身。

---

## 2）行格式

```
<字词>\t<读音1> [<读音2> ...]\t<条件1>\t<条件2> ...\t[# 注释]
```

- 分隔符 Tab 或空格都认；条件里若含空格会自动合并成完整 token。
- 行内 `#` 注释只在括号外生效；`#` 开头的整行忽略。
- 读音列：
  - `hua4` / `liao3`：完整拼音 + 声调 1–4，`5` = 轻声；
  - `*5` / `*3`：**只改声调**，沿用引擎原本的拼音（上游产物大量使用）；
  - `none5`：儿化占位，「儿」不单独注音；
  - 多字词几个字就写几个读音，逐字对应。
- 条件列（可 0 到多列，全部满足才生效）：

| 条件 | 含义 | 实物例子 |
|---|---|---|
| `TEXT(模式)` | 上下文正则；内层 `(...)` 圈定真正改读音的字 | `TEXT(雪中悍刀(行))`、`TEXT((一)[级\|档])`、`TEXT(宝葫(芦))` |
| `!TEXT(...)` | 取反 | `!WORD_INSIDE(0)` |
| `WORD_INSIDE(n)` | 在分词结果里的相对位置（`-1` 词尾、`0` 词首/单字） | `WORD_INSIDE(-1) WORD_INSIDE(0)` |
| `PINYIN(regex)` | 前后字拼音匹配 | `PINYIN(.*_.*_.*_.*4_.*)` |
| `POS(regex)` | 词性匹配 | `POS(.*_.*_nr.*_.*)` |

括号内支持 `[a|b]` 备选、`[^城]` 否定字符集、`(?:...)` 非捕获、`?` 可选、`^`/`$` 边界。

### 2.1）「读音」页的表单式追加

App 底部「读音」页按 `midu_unified_rule` 工具页的表单语义生成规则行：

```
key ⇥ 读音 ⇥ 规则(匹配值) [⇥ # 注释]
```

- 匹配值留空或为 `()` 时自动取 `(key)`，所以 `吧 / ba1` 生成 `吧\tba1\tTEXT((吧))`；
- 支持 `TEXT / WORD_INSIDE / PINYIN / POS`、自定义规则名，以及「无（全局生效）」（不写条件列）；
- 任意输入框回车 = 添加一条，添加后清空表单并聚焦字词；内容区可删除、可点回表单重编辑；
- 「应用」把内容区规则写入 `files/rules/user_rules.txt` 的 **`新增:` 区块**（没有就新建在文件末尾），
  按规范化整行去重（Tab/空格等价、忽略行内注释、跳过 `移除:` 区块），
  然后 `RuleManager.apply()` 合并三份规则、原子写进资源根、`generation+1` 并重建引擎；
  内容区为空时也重新合并一次，与网页工具「应用 = 写入 + 整理」一致；
- 状态条显示用户规则文件真实行数 / 字节 / 最近修改时间，2 秒心跳发现被外部（编辑器 / adb）
  改动时高亮 8 秒；应用成功后同步基线，避免把自己的写入误报成外部修改；
- 「查找 / 调整已有读音」把命中的规则行反解析回表单，改完读音后添加并应用，
  因为冲突键是「字词 + 条件」，同键规则会被用户层就地覆盖（见 §3）。

### 2.2）调用方内联拼音（临时读音，不写规则文件）

规则的另一面：单次合成的临时注音直接写在文本里 —— `[[字词|pin1 yin1]]`（拼音数=汉字数、
声调 1-5），app 的 `engine/PinyinInline.java` 把它转成 SSML `<phoneme alphabet="py">`，
对命中的句子切 `tts_text_type=ssml`，引擎逐字覆写拼音（2026-11-13 真机实测，
证据见 `PRONUNCIATION_KNOBS.md` §4）。校验不过只摘标记按普通文本读。
规则层管持久读音，内联拼音管单次读音，互不影响。

---

## 3）冲突键与覆盖

**同一条 = 字词 + 条件集合（不含读音）。** 高层用同键的规则**就地替换**低层的读音，
低层其它条件不同的规则全部保留。

```
vendor : 行  xing2  TEXT(雪中悍刀(行))
用户   : 行  hang2  TEXT(雪中悍刀(行))
→ 最终只有 hang2 那条，位置保持不变（沿用上游排序）
```

多层都存在时：上游产物作为**有序基底**（它已经是 `organizer.ts` 分组/排序后的结果），
vendor 里上游没有的键追加到末尾，最后应用用户覆盖与删除。这样既保留上游顺序，
也保证换过厂商语音包时不丢规则。

### 用户文件里的删除语法（兼容上游补丁）

```
移除:
芦	lu5	TEXT(宝葫(芦))
新增:
银行	yin2 hang2	TEXT((银行))

-嗓	sang5	TEXT((嗓)子)        # 单条删除的简写
@删除 了                         # 清空该字词在低层的所有规则
```

`移除:` 区块里的行按**规范化整行**（Tab/空格等价、去注释）匹配；
其它区块头（`去重:` `分组:` `排序:` `表单定义:` `页面操作:`）按上游 organizer 的语义跳过收集。

---

## 4）自动更新

- `RuleUpdateService`（`JobScheduler`，默认每 6 小时、需网络、重启后保留）拉一个 URL：
  - `https://raw.githubusercontent.com/AlexIllinois2/midu_unified_rule/main/conf/unified_rule.txt`
  - **镜像回退（2026-11-13）**：未设置自定义 URL 时按「源站 → gh-proxy.com 前缀 →
    ghproxy.net 前缀」顺序尝试（代理前缀直接拼在原 URL 前），网络类失败
    （TLS 重置 / 超时 / 非 2xx）自动换下一个，全失败才报错并保留原始原因。
    raw.githubusercontent.com 在国内网络 TLS 握手即被 RST（真机实测 curl 35 / 0.2s），
    jsDelivr 在部分网络同样不可达，故走代理前缀镜像；镜像失效只需改
    `RuleUpdateService.MIRROR_PREFIXES`。
  - 设置页可改成镜像 / 自建 URL（自定义 URL 是显式选择，不做镜像拼接）。
- 用 `If-None-Match` + ETag；304 就不动文件。
- 落盘前过闸：`Rules.looksValid()`（非空、<4 MiB、无 NUL、可解析规则 ≥ 20 行、错误率 < 25%），
  **不合格就保留上一版**，绝不把坏文件写进引擎资源根。
- 每次安装前把上一版存成 `*.prev`，设置页「回滚上游到上一版」可一键恢复。
- 规则内容变了 → `generation + 1` → 广播 `com.bd.tts.RULES_UPDATED` →
  `EngineController.invalidate()` → **下一次合成销毁并重建引擎**（规则是 init 时读的）。
- 设置页「立即检查上游更新」可手动触发；关掉自动更新会取消 Job。

---

## 5）离线自查（不需要手机）

```bash
cd D:/weiheng/proj/midu-tts
python android/scripts/verify_rules.py                     # 用 local_rules/ 里的文件预览合并
python android/scripts/verify_rules.py --out /tmp/merged.txt
```

`android/local_rules/` 放 `upstream_unified_rule.txt`、
`user_rules.txt`（样例已给），脚本与 Java 的 `Rules.java` 同语义，会打印计数与逐条冲突。

---

## 6）生效链路一图流

```
vendor(assets) ─┐
upstream(定时下载) ─┼→ Rules.merge → merged_unified_rule.txt
user(设置页/SAF) ─┘        │
                            ↓ 原子写
<files>/midu_effective/zh-cn/frontend/g2p/mnc/unified_rule.txt
                            ↓ 引擎 init 时读
              libspeechengine.so → pinyin list after apply rules
```
