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

## 2. 一次任务最多读 10 个文件（③）

- 配置：`media.context.max-files-per-task`（默认 10，clamp 1..50）；`MediaToolContextService.reserveFileRead(mediaId)` 在**当前任务**的 ThreadLocal 上下文里**按 mediaId 去重**计数。
- **口径是「文件」，不是「图片」**：图片是整张 base64 原图（一张手机课表 1~2MB，读 5 张就是 9MB 级别），文本/PDF 是正文或提取内容——进上下文的方式不同，但**都占额度**。用户明确要求按文件算，所以第一版「只限制图片、上限 3」已经改成现在这样。
- 为什么要有上限：`readStoredMedia` 原来没有上限，模型把候选一个个看完就把模型窗口顶满，**本轮回复质量整体崩掉**（不是报错，是悄悄变傻）。
- 超限行为：**拒绝继续读**、在结果里告诉模型"让用户点名要哪几个"，同时打 WARN `本轮读取文件已达上限`。同一份文件重复读只算一个；计数随 `inspectRecentUnstoredMedia` 的"重新激活"一起延续，**不会被绕开**。

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
2. 造一批测试资料（**12 个 `.txt` + 4 张图片，文件名刻意不含目标关键词**），让机器人"连续调用 readStoredMedia 把这 16 份全读一遍"：日志里 `工具开始 name=readStoredMedia` 会出现十几次，其中第 11、12 次被拒——WARN `本轮读取文件已达上限 10，拒绝继续读取 mediaId=<id>`，**被拒的 id 必须是文本文件**，这才证明"文本也占额度"；回复里应说明有上限、让用户点名。**反向对照**：另起一句只读 5 份，不该出现任何上限 WARN（旧上限 3 会在这里挡住）。
3. 检查库里 `extracted_text like '%【视觉识别】%'` 有没有内容 → 证明模型把看到的东西写回来了。
4. 往某行的 `extracted_text` 里塞一个**只存在于内容里**的关键词，再问"我保存的文件里哪个提到 X" → 除了断言回复里出现文件名，还要断言**回复里带出那句内容**（回落列表也会列出所有文件名，只看文件名会假阳性），并核对日志里这一轮真的调了 `listStoredMedia`（同一会话里模型可能凭上一轮上下文直接回答、根本不搜）。

## 6. 附件支持的类型（2026-09-20 扩）

**改之前只有 PDF 和 DOCX 能读**：`DocumentExtractionService.extract()` 认不出就抛
`目前只支持 PDF 和 DOCX 文件。`，而这个异常**被直接当成回复发给用户**
（`AgentOrchestrator` 的 `catch (DocumentExtractionException e) → HandledReply(e.getMessage())`）。
所以用户发 PPT 只会收到那一句 21 字的话，**而且那一轮不落库**（`return` 在 `persistConversation` 之前）。

**另一个容易忽略的点**：QQ 的「图片」元素和「文件」元素走**两条完全不同的路**——
图片（`batch.images()`）由 `AgentLoop` 自动下载成 data URL 直接进多模态消息；而**把同一张 jpg 当"文件"发**
（`batch.attachments()`）会进 `DocumentExtractionService`，改之前一样被"只支持 PDF/DOCX"拒掉。

现在支持：

| 类型 | 怎么读 |
|---|---|
| 图片 jpg/jpeg/png/webp/gif（**含当文件发的**） | **按字节头**认类型（`sniffImageMime`，不认后缀/MIME，避免改名的怪文件），转 data URL 走 `pageImages` → 复用已有视觉通道 |
| PDF | 有文字层就取正文；扫描版渲染前 `document.max-pdf-pages`(10) 页成图给视觉模型 |
| Word `.docx` | POI `XWPFWordExtractor` |
| PPT `.pptx` | POI `XMLSlideShow` 逐页取文本框 + **表格**（`|` 分隔）+ 递归分组形状，**带页码**（`第 N 页：`） |
| 老版 `.ppt`/`.doc`、xlsx 等 | **不支持**（`.ppt` 要么加 `poi-scratchpad` 依赖，要么让用户另存为 pptx/pdf）；错误文案会直接告诉用户能读什么、该怎么转 |

**顺带修的**：读不了附件时也会 `persistConversation`（用户"发了但没读成"的消息不再在对话记忆里空白）；落库失败只 WARN，不影响回话。

**怎么验证这块**：
1. 单测式（不用发 QQ）：`poi-ooxml` 造一个含文本框+表格的 `.pptx` → 用 `extractPptx` 同一套走法取文本（页码/表格都要出来）；用 `ImageIO` 造 jpg/png 核对魔数 `ff d8 ff` / `89 50 4e 47`，并确认纯文本字节返回 `null`（不会被误当图片）。2026-09-20 已这样验过。
2. 端到端：在 QQ 里**以文件形式**发一张 jpg + 一个 pptx，看日志有没有 `图片下载完成` / 多模态消息（图片）或模型能说出 PPT 内容（文本），并确认**这一轮没有** `目前只支持` 字样。
