# 媒体记忆：存图、搜关键词、让模型"看过就记住"（2026-09-13）

用户会把课表、讲义、截图发给机器人。这个模块负责**存下来**、**以后能按内容搜出来**、以及**不要在一次任务里把上下文撑爆**。

## 1. 现状与数据流

```
用户发文件/图片
  → MediaStorageService.save()       落盘 stored-media/<可读名>-<sha256(userId)[0:12]>/active/<文件>
  → stored_media 一行                 file_name / original_name / summary / importance_reason / extracted_text / sha256 / status
  → 模型要看内容时 readStoredMedia    读文件 → 图片按 ImageContent 塞进上下文（视觉模型直接看）
  → 模型看完后 noteStoredMediaContent 把"里面是什么"写回 extracted_text（前缀【视觉识别】）
  → 以后 listStoredMedia 按关键词搜    searchableText() = 文件名 + 原名 + summary + importanceReason + extractedText
```

- `MediaStorageService.searchableText()` 已经把上面这些字段拼在一起做 `contains` 匹配，**新增内容不需要改搜索逻辑**，写进 `extracted_text` 就等于可搜。
- `listStoredMedia` 命中不到精确匹配时会退化成「没有精确匹配，以下是最近保存的文件」+ 最近 20 条，**不会空手而归**。

## 2. 一次任务最多喂 3 张图（③）

- 配置：`media.context.max-images-per-task`（默认 3，clamp 1..20）；`MediaToolContextService.reserveImageRead()` 在**当前任务**的 ThreadLocal 上下文里计数。
- 为什么：`readStoredMedia` 原来没有上限。一张手机拍的课表 1~2MB，读 5 张就是 9MB 级别的上下文，直接顶到模型窗口上限，**本轮回复质量整体崩掉**（不是报错，是悄悄变傻）。
- 超限行为：**拒绝继续塞图**、在结果里告诉模型"让用户点名下一张"，同时打 WARN `本轮读图已达上限`。计数随 `inspectRecentUnstoredMedia` 的"重新激活"一起延续，**不会被绕开**。

## 3. 让模型把看到的内容写回库（①②）

- 关键约束：**视觉理解发生在模型脑子里，工具拿不到**。想让"这张图讲什么"以后能搜到，只有两条路——
  1. **模型自己记**：新增工具 `noteStoredMediaContent(mediaId, hint)`，模型看完图顺手调一下（零额外成本）；
  2. **服务端再调一次视觉模型**：额外花钱、额外延迟，本项目**没做**。
- 存放位置：`stored_media.extracted_text`，前缀 `【视觉识别】`；**替换式写入**（从标记处往后整段替换），保留此前已有的文档正文；`MAX_CONTENT_HINT_CHARS = 400`。
- **故意不更新 `updated_at`**：`listStoredMedia` 按时间倒序，随手补一条备注就把老文件顶到最前会让用户困惑。
- 存图时另外要求模型把 `summary` 写成**带可检索关键词**的一句话（模型本来就看过了，0 成本）——这是 ①。

## 4. 读不出来的文件不要重试（已修）

`stored_media` 有行、磁盘上没文件（本地跑 JAR 时存的图，**行跟着库搬到了服务器、文件没搬**，见 `docs/backup.md`）时，读取会抛 `MediaSourceMissingException` → 转成 `ToolBusinessResult.failure(...)`（**不可重试**）。原来抛 `IllegalStateException` 会被判成可重试，白烧两轮模型调用，而且模型什么都得不到。现在模型会明确说"记录还在、内容读不出来，别再反复试"。

## 5. 怎么验证（都在服务器上做）

1. **别用机主 openid 发模拟消息**：模拟器会把 `user_profile.last_channel` 写成 `simulator`，之后所有主动推送静默失败（坑 30）。用一个一次性测试用户，测完删掉它的行。
2. 造 5 张测试图片（文件名刻意不含目标关键词），让机器人"依次读这 5 个文件"：日志应出现 `本轮读图已达上限 3`，回复里说明要用户点名。
3. 检查库里 `extracted_text like '%【视觉识别】%'` 有没有内容 → 证明模型把看到的东西写回来了。
4. 往某行的 `extracted_text` 里塞一个**只存在于内容里**的关键词，再问"我保存的文件里哪个提到 X" → 命中的文件名出现在回复里，证明按内容检索这条路是通的。
