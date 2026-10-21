# 它的新眼睛和新手（2026-10-21）

这一篇记三件事：**它怎么看视频、怎么画图、怎么把图发到 B 站评论里**。
再附带一件同一天的改动：**长任务放后台跑**。

共同背景：当天把 LLM 供应商从 DeepSeek 官方换到了**基元律动**（`tokenrhythm.studio`），
换完之后多出来的能力就是这些。**搜索没有换**（基元没有搜索接口，仍走 DeepSeek 官方，
见 `AGENTS.md` 里"搜索"那条）。

---

## 1. 看视频：直接交给模型，不再抽帧

### 之前怎么做

```
B 站视频 → 取字幕（要登录态）
         └─ 没字幕 → 下载 360p → ffmpeg 抽帧（4 秒一帧）→ 拼成联系表 → 交给视觉模型看几张图
```

抽帧的硬伤是**只能看到静态切片**：连续动作、飞过去的字幕、一句话配一个画面的节奏，
4 秒一张图看不出来。

### 现在怎么做

```
B 站视频 → 下载（复用原有的 playUrl + downloadTo）
         → base64 → 交给 qwen3.8-flash（支持视频输入）
         → 它自己看画面，返回文字描述
         → 字幕能拿到就一起给（附带信息），拿不到无所谓
```

优先级（用户明确要求过，写在 `BilibiliTool#readBilibiliVideo` 的注释里）：

| 情况 | 走哪条 |
|---|---|
| **视频模型能用** | **看视频是主干**，字幕只是附带（有就给，没有也不影响） |
| **视频模型不能用** | **字幕优先**；**没字幕才**抽帧 |

抽帧的代码**一行没删**，只是降级成兜底：

```yaml
bilibili:
  frame-fallback: ${BILIBILI_FRAME_FALLBACK:true}   # 默认开 = 视频模型失败时还能看几张
llm:
  video-model: ${LLM_VIDEO_MODEL:qwen3.8-flash}    # 留空 = 整条路关掉
  video-max-base64-bytes: ${LLM_VIDEO_MAX_BASE64_BYTES:16777216}
  video-max-output-tokens: ${LLM_VIDEO_MAX_OUTPUT_TOKENS:4000}
```

### 实测数据

| 视频 | 输入 token | 耗时 | 成本 |
|---|---|---|---|
| 3 分半（360p，10MB） | 48748（video 48650） | 67 秒 | ≈ 0.025 元 |
| 5 秒测试片 | 3680 | 数秒 | ≈ 0.0015 元 |

**它看到的内容是准的**——实测那条 MV，它说出了 4:3 画幅、1980 年代、黑发歌手、
酒吧场景、砖墙前的女舞者。

### 三个坑

**① `max_tokens` 给小了会得到空字符串**

一个 3 分半的视频要 **2361 思考 token + 596 字正文**。原来给 1200，
思考就吃满了，`content` 返回空——看起来像"模型没说话"。
**现在给 4000**，而且加了 `reasoning_effort=none`（但实测上游不一定完全关掉思考，
所以额度必须给够，不能只靠那个开关）。

**② URL 直传对 B 站没用**

上游也支持 `video_url` 直接给公网地址，但那是**让它的服务器去下载**。
B 站视频有防盗链（服务端 403），所以只能**自己下载再 base64**。

**③ 视频太大要老实说**

`video-max-base64-bytes` 默认 16MB（≈12MB 原始视频）。超了返回
"视频太大，这次没直接看"——不能假装看了。

---

## 2. 画图：0.2 元一张

### 接口

```bash
POST {base}/v1/images/generations
{"model": "qwen-image-2.0", "prompt": "一只橘猫在窗台上晒太阳", "n": 1}
```

返回 `{created, data:[{image_id, url}]}`，url 指向阿里云 OSS（**带签名、有时效**，
拿到就得立刻下载）。

**注意**：`/v1/models` 里**看不到** `qwen-image-2.0`，但接口是通的——这两个生图模型
是"隐藏模型"。别用模型列表判断能不能画。

### 额度（用户定的规则）

| 谁画的 | 额度 |
|---|---|
| **它自己主动画** | **5 张/天**（`LLM_IMAGE_SELF_DAILY_LIMIT`） |
| **用户让它画** | **不限**，一张也不占 |

怎么区分？`SelfTurnMark` 是一个 ThreadLocal 标记：

- `SelfTurnRunner` 开跑时 `install()`、`finally` 里清掉
- `DrawPictureTool.quotaExceeded()` 先看 `SelfTurnMark.active()`
  - 不在自主轮 → **直接放行**（用户要求的）
  - 在自主轮 → 才检查 5 张的额度

**为什么用线程标记而不是加参数**：工具层没法从参数看出"这算谁的"，
只能靠调用链上的上下文。

### 坑

**ThreadLocal 必须 clear**——漏了的话下一次用户对话会被当成自主轮，画图被误限额。

---

## 3. B 站评论带图

**用户纠正过我的一个错误判断**：我以为 B 站评论不能发图，其实可以。

### 两步

**第一步：把图传到 B 站图床**

```bash
POST https://api.bilibili.com/x/dynamic/feed/draw/upload_bfs
Cookie: SESSDATA=...; bili_jct=...
multipart/form-data:
  file_up   = <图片二进制>        ← 字段名是 file_up，不是 file！
  biz       = new_dyn
  category  = daily
  csrf      = <bili_jct>
```

返回 `{code:0, data:{image_url, image_width, image_height, img_size}}`。

**这些参数不是猜出来的**——我按 `file` 试了 5 种组合都返回 `4100001 参数错误`，
最后是从 B 站前端的 `bili-comments.*.js` 里搜 `upload_bfs` 找到的真实调用：

```js
this.uploader = new iC({
  remoteOptions: {
    url: "/x/dynamic/feed/draw/upload_bfs",
    body: function(e) {
      var t = new FormData;
      t.append("file_up", e);
      t.append("biz", "new_dyn");
      t.append("category", "daily");
      return t;
    },
    ...
```

**以后遇到类似"接口存在但参数不对"**，直接去前端 JS 里搜接口路径，比猜快得多。

**第二步：发评论时带上 pictures**

```bash
POST https://api.bilibili.com/x/v2/reply/add
oid=<aid>&type=1&message=...&pictures=[{"img_src":"...","img_width":120,"img_height":120}]&csrf=...
```

### 失败不阻断

`BiliCommentTool` 里画图/上传**任何一步失败都退回纯文字**——
图是锦上添花，不能因为它把话憋回去。

### 实测验证方式

```bash
# ① 上传 → 拿 image_url
# ② 发带图评论 → 拿 rpid
# ③ 立刻删掉（POST /x/v2/reply/del，带 oid/type/rpid/csrf）
```

三步都返回 `code:0` 才算通。**测完记得删**，别在用户账号上留测试痕迹。

---

## 4. 长任务放后台（`workInBackground`）

用户的原话：*「她发现要做好久，就会先回我一下然后接着做，不用占用发消息的窗口，
这时候我也能给她发消息正常互动，后面做完了也会回复我汇报给我」*。

### 怎么做的

```
用户：帮我查 React 19 有什么新特性，慢慢弄
  ↓
模型判断要几分钟 → 调 workInBackground(task)
  ↓
工具**立刻返回**（这一轮结束）+ 任务落库 background_task + 扔进单线程池
  ↓
用户能立刻发下一条消息（实测 4.7 秒就回了）
  ↓
后台跑完 → ProactiveDelivery 主动发一条汇报（实测 150 秒后到）
```

### 边界（都写死，不靠模型自觉）

| 风险 | 处理 |
|---|---|
| 一口气扔十个任务 | 队列上限 **3**，超了直接拒绝并让它"这次直接做" |
| 后台里再调 `workInBackground` | `TurnScope.denies` 禁掉，防无限套娃 |
| 跑飞烧钱 | 12 轮 + **0.8 元预算**，到了自动停 |
| 失败静默消失 | 失败也主动说"没做成（原因）" |
| 重启丢任务 | 落库；QUEUED 重新排队，RUNNING 标失败并告诉他 |
| 汇报后用户追问它装傻 | 汇报**也写进 `conversation_memory`**（不写的话它真不记得） |

### 两个坑

**① 工具类不能直接注入 `AgentLoop`**

`AgentLoop` → `ToolRegistry` → 本工具 → `AgentLoop` 会成环，Spring 默认禁止，
启动直接 `APPLICATION FAILED TO START`。用 `ObjectProvider<AgentLoop>` 打破。

**这个坑是本地启动时发现的——如果直接推生产，容器起不来。**

**② `max-rounds` 和 `budget` 别给太小**

实测 8 轮 / 0.5 元时，它读完 4 个网页就到上限了，没时间总结，
只能说"还没弄好"。现在默认 12 轮 / 0.8 元。

### 面板在哪看

面板 →「它自己」→「它在后台忙的事」：
时间、要它做什么、当前状态（排队中/在跑/做完了/没做成）、花了多久、结果摘要。

数据保留 **30 天**（`agent.background.keep-days`，每天 03:30 清一次，
**只清 DONE/FAILED**，排队和在跑的一律不动）。

---

## 5. 花费按场景分类

顶栏那个数字**不是余额**——基元律动没有余额接口（`/user/balance` 404，
`/api/me` 要的是网页 Cookie 而不是 Bearer）。

现在显示的是**今日花费**，数据来自 `llm_call_audit` 的落库统计：

| 场景 | 怎么计价 |
|---|---|
| dialog / dialog_first / extract / reflect / self_comment / reminder_parse | token × 单价（原有逻辑） |
| **video** | 同样按 token（2026-10-21 起入账） |
| **image** | **没有 token**，按张数 × 0.2 元折算（`LlmCallAuditRecorder.todayImageYuan()`） |

悬停在顶栏数字上能看到分类明细。**实测合计与分类求和对得上**（1.86 vs 1.85，四舍五入差）。

### 峰谷计价已关

基元律动不区分峰谷，所以：

```yaml
LLM_PRICE_PEAK_MULTIPLIER=1.0    # 不再乘 2
MEMORY_SELF_OFFPEAK_ONLY=false   # 自主模块随时能跑
```

`SelfOffPeak` 那套代码**一行没删**——以后换回区分峰谷的供应商，改回这两个值就行。
