package com.liche.qqagent.bilibili;

import com.liche.qqagent.tool.AgentToolProvider;
import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「看 B 站视频」的工具面（2026-10-10 加）。
 *
 * <p>用户转发 B站视频时，QQ 卡片只给标题和封面（平台把链接留在 {@code ark_data.fields.jump_url}，
 * 由 {@code QqArkCard} 拼进消息），所以模型拿到的是一条链接。**真正的视频内容只有字幕拿得到**，
 * 这个工具就是去取字幕。
 *
 * <p>取字幕要登录态（服务器 {@code .env} 的 {@code BILI_SESSDATA}）；拿不到时**明确说拿不到**，
 * 让模型如实告诉用户，而不是靠标题猜内容——这也是它和"随便编一段总结"的分界线。
 */
@Component
public class BilibiliTool implements AgentToolProvider {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BilibiliTool.class);

    private static final int DESC_PREVIEW_CHARS = 300;
    /** browseBilibili 默认给几条候选：够挑又不至于让他看得眼花 */
    private static final int DEFAULT_BROWSE_COUNT = 12;

    private final BilibiliClient client;
    private final com.liche.qqagent.media.VideoFrameService videoFrameService;
    private final com.liche.qqagent.media.VideoUnderstandService videoUnderstandService;
    /** 视频模型看不成时要不要回落抽帧（2026-10-21 起默认关，没额度了可以打开兜底） */
    private final boolean frameFallback;
    private final com.liche.qqagent.media.MediaToolContextService mediaContext;
    private final int maxTranscriptChars;
    /** 抽帧兜底最多下多少字节的视频（360p，2 分钟约 9MB；这里给到 60MB ≈ 十几分钟） */
    private final long maxDownloadBytes;
    /** 「它看过哪些视频」的台账（2026-10-20）：**工具埋点，不靠模型自述** */
    private final BiliViewLogRepository viewLogs;

    public BilibiliTool(BilibiliClient client,
                        com.liche.qqagent.media.VideoFrameService videoFrameService,
                        com.liche.qqagent.media.VideoUnderstandService videoUnderstandService,
                        @Value("${bilibili.frame-fallback:false}") boolean frameFallback,
                        com.liche.qqagent.media.MediaToolContextService mediaContext,
                        @Value("${bilibili.max-transcript-chars:6000}") int maxTranscriptChars,
                        @Value("${bilibili.frame-fallback-max-bytes:62914560}") long maxDownloadBytes,
                        BiliViewLogRepository viewLogs) {
        this.client = client;
        this.viewLogs = viewLogs;
        this.videoFrameService = videoFrameService;
        this.videoUnderstandService = videoUnderstandService;
        this.frameFallback = frameFallback;
        this.mediaContext = mediaContext;
        this.maxTranscriptChars = Math.max(500, Math.min(20_000, maxTranscriptChars));
        this.maxDownloadBytes = Math.max(5L * 1024 * 1024, maxDownloadBytes);
    }

    @Tool(value = "看一个 B 站视频讲了什么（他转发链接或 BV 号时）。**一轮只读一条**，读完立刻 noteWatchedVideo 记一笔。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult readBilibiliVideo(String url) {
        try {
            String matchedByTitle = null;
            BilibiliClient.VideoRef ref;
            try {
                ref = client.parse(url);
            } catch (BilibiliClient.BilibiliException notALink) {
                // 转发的卡片没有链接：模型会把卡片标题传进来，去 B 站搜，**只有标题逐字一致才认**
                String bvid = client.searchByTitle(url);
                if (bvid == null) {
                    return ToolBusinessResult.failure("这个输入既不是链接/BV 号，按标题去 B 站搜也没有"
                            + "标题完全一致的视频。问他要一下链接或 BV 号，别自己猜是哪个视频。");
                }
                matchedByTitle = url == null ? "" : url.trim();
                ref = new BilibiliClient.VideoRef(bvid, null, null);
            }
            BilibiliClient.VideoInfo info = client.videoInfo(ref);

            StringBuilder out = new StringBuilder("【B站视频】").append(info.title()).append('\n');
            // 链接必须带上（2026-10-21）：用户问过"之前不是能拿到链接的吗"。
            // 原来这里只有标题和 UP 主，它回答时手上根本没有 URL，之前那句
            // "以后你要链接随时有"其实是空头承诺。现在 BV 号和可点链接都在工具结果里。
            out.append("链接：https://www.bilibili.com/video/").append(info.bvid()).append('\n');
            out.append("BV号：").append(info.bvid()).append('\n');
            if (matchedByTitle != null && !matchedByTitle.isBlank()) {
                out.append("（这是按他转发过来的标题搜到的，原标题：「").append(matchedByTitle)
                        .append("」，已核对**逐字一致**）\n");
            }
            out.append("UP主：").append(info.owner().isBlank() ? "未知" : info.owner())
                    .append("，时长：").append(duration(info.durationSeconds())).append('\n');
            // 长度约束写进工具结果（2026-10-22）：只在系统提示词里写"80 字"，实测看视频后
            // 仍然会回 600+ 字；工具结果是模型当下必读的，放在这里比系统提示词管用。
            out.append("（回答提示：只讲最要紧的 2–3 点，200 字以内；他明确要详细时才展开）\n");

            if (info.pages().isEmpty()) {
                recordView(info.bvid(), info.title(), BiliViewLog.OUTCOME_FAILED, "拿不到分P信息");
                out.append(describeUnavailable(info.title(), "拿不到分P信息（接口没返回），字幕读不了。"));
                return ToolBusinessResult.success(out.toString());
            }

            BilibiliClient.Page page = pickPage(info, ref);
            if (info.pages().size() > 1) {
                out.append("分P：第 ").append(page.index()).append('/').append(info.pages().size())
                        .append(" P（").append(page.title()).append("）\n");
            }
            if (!info.desc().isBlank()) {
                out.append("简介：").append(clip(info.desc(), DESC_PREVIEW_CHARS)).append('\n');
            }

            BilibiliClient.Transcript transcript = client.transcript(info.bvid(), page.cid(), maxTranscriptChars);

            // 2026-10-21：先让视频模型把画面看一遍，字幕降级成"附带信息"。
            // 原来的顺序是「没字幕才抽帧」，而抽帧看到的是每 4 秒一张的静态切片——
            // 连续动作、飞过去的字幕、一句话配一个画面，都看不出来。
            WatchLook watch = watchVideo(info.bvid(), page.cid());

            // 两种模式（2026-10-21 按用户要求定死）：
            //   A. 视频模型能用 → **看视频是主干，字幕只是附带**（有就给，没有也不影响）
            //   B. 视频模型不能用 → 退回老办法：**字幕优先，没字幕才抽帧**
            FrameLook look = null;
            if (watch.ok()) {
                out.append("画面（这段视频我直接看了）：\n").append(watch.text()).append('\n');
                if (transcript.available()) {
                    out.append("\n附带字幕（").append(transcript.language().isBlank() ? "语言未知" : transcript.language())
                            .append(transcript.aiGenerated() ? "，B站自动生成" : "，UP主上传")
                            .append("）——上面是画面，这里是台词原文：\n");
                    out.append(transcript.text());
                } else {
                    out.append("\n（这个视频没拿到字幕：").append(transcript.reason())
                            .append("。画面已经看过了，字幕只是补充，不影响。）\n");
                }
            } else {
                // B 模式：看不成要说清为什么，然后走字幕 / 抽帧
                if (!watch.why().isBlank()) {
                    out.append("（画面没看成：").append(watch.why()).append("，改看字幕)\n");
                }
                if (transcript.available()) {
                    out.append("字幕（").append(transcript.language().isBlank() ? "语言未知" : transcript.language())
                            .append(transcript.aiGenerated() ? "，B站自动生成" : "，UP主上传").append("）：\n");
                    out.append(transcript.text());
                } else {
                    out.append("字幕：").append(transcript.reason()).append('\n');
                    // 有字幕就不抽帧了（字幕已经够用）；只有没字幕才抽
                    if (frameFallback) {
                        look = lookAtFrames(info.bvid(), page.cid());
                        out.append(look.text());
                    }
                }
            }

            // 字幕、画面、抽帧三样都没有，才说"只能依据简介"
            if (!transcript.available() && !watch.ok() && (look == null || !look.ok())) {
                out.append("\n（所以**只能**依据上面的简介，不要凭标题「").append(info.title())
                        .append("」编造视频里讲了什么。）");
            }

            if (watch.ok()) {
                recordView(info.bvid(), info.title(), BiliViewLog.OUTCOME_VIDEO,
                        "视频模型看了 " + watch.promptTokens() + " token");
            } else if (transcript.available()) {
                recordView(info.bvid(), info.title(), BiliViewLog.OUTCOME_TRANSCRIPT,
                        transcript.text() == null ? "" : transcript.text().length() + " 字");
            } else if (look != null && look.ok()) {
                recordView(info.bvid(), info.title(), BiliViewLog.OUTCOME_FRAMES, "抽帧成功");
            } else {
                recordView(info.bvid(), info.title(), BiliViewLog.OUTCOME_FAILED, "没字幕也没看成画面");
            }
            return ToolBusinessResult.success(out.toString());
        } catch (BilibiliClient.BilibiliException exception) {
            // 确定性失败（链接不认 / 视频没了 / 跳转不是 B 站）：直接回话，别让框架白重试（坑 67）
            return ToolBusinessResult.failure(exception.getMessage());
        } catch (Exception exception) {
            // 网络类瞬时故障：抛出去按策略重试
            throw new IllegalStateException("读 B 站视频失败：" + exception.getMessage(), exception);
        }
    }

    @Tool(value = "随便看看 B 站（热门/关注/稍后再看）。只是「刷会儿」用这个；**有具体问题去 digBilibili**。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult browseBilibili(String source, Integer count) {
        int limit = count == null ? DEFAULT_BROWSE_COUNT : Math.max(1, Math.min(30, count));
        try {
            String wanted = source == null || source.isBlank() ? "hot" : source.trim().toLowerCase(java.util.Locale.ROOT);
            java.util.List<BilibiliClient.FeedItem> items = client.feed(wanted, limit);
            if (items.isEmpty()) {
                return ToolBusinessResult.failure("这个来源现在没有可看的视频（可能关注列表是空的，或者登录态过期了）。"
                        + "如实跟他说没看到什么，别编。");
            }
            String from = items.get(0).source();
            StringBuilder out = new StringBuilder("【B站·").append(from).append("】").append(items.size()).append(" 条：\n");
            int index = 1;
            for (BilibiliClient.FeedItem item : items) {
                out.append(index++).append(". ").append(item.title()).append('\n')
                        .append("   BV：").append(item.bvid())
                        .append("　链接：https://www.bilibili.com/video/").append(item.bvid());
                if (!item.owner().isBlank()) {
                    out.append("；UP：").append(item.owner());
                }
                if (item.durationSeconds() > 0) {
                    out.append("；时长：").append(duration(item.durationSeconds()));
                }
                if (item.view() > 0) {
                    out.append("；播放：").append(item.view());
                }
                out.append('\n');
                if (!item.desc().isBlank()) {
                    out.append("   简介：").append(clip(item.desc(), 120)).append('\n');
                }
            }
            out.append("\n（这只是目录。要看某个视频讲了什么，把它的 BV 号传给 readBilibiliVideo。）");
            return ToolBusinessResult.success(out.toString());
        } catch (BilibiliClient.BilibiliException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        } catch (Exception exception) {
            throw new IllegalStateException("看 B 站失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 记一条「它看过这个视频」（2026-10-20）。
     *
     * <p>为什么在工具里记而不是让它自己报：**模型的自我总结不一定可信**，
     * 而用户明确要求"不干扰它，但我得知道它做了什么"。台账失败不影像读取本身。
     */
    private void recordView(String bvid, String title, String outcome, String detail) {
        if (viewLogs == null) {
            return;
        }
        try {
            BiliViewLog entry = new BiliViewLog();
            entry.setBvid(clip(bvid == null ? "" : bvid, 30));
            entry.setTitle(clip(title == null ? "" : title, 250));
            entry.setOutcome(outcome == null ? "" : outcome);
            entry.setDetail(clip(detail == null ? "" : detail, 60));
            entry.setCreatedAt(java.time.LocalDateTime.now());
            viewLogs.save(entry);
        } catch (RuntimeException ignored) {
            // 记台账失败不能盖过"读视频"这件事本身
        }
    }

    /**
     * 记「这个视频讲了什么」（2026-10-20 加）。
     *
     * <p>为什么必须有这一步：台账原来只记"字幕 266 字"这种**字数**，内容一个字都没存。
     * 结果它刷完就过去了，过两天想找"那个讲 git 记忆的视频"只能靠标题猜——等于没有记忆。
     *
     * <p>所以读完视频要**立刻**调用它，把摘要存进那条台账（同一个 bvid 覆盖最近一条，不重复插行）。
     */
    @Tool(value = "把刚看的视频记下来：summary 两三句带关键词，takeaway 写你自己怎么看。**看完立刻记，别拖到下一轮**。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult noteWatchedVideo(String bvid, String summary, String takeaway) {
        if (bvid == null || bvid.isBlank()) {
            return ToolBusinessResult.failure("得给一个 BV 号，不然不知道记的是哪个视频");
        }
        String text = summary == null ? "" : summary.trim();
        if (text.isBlank()) {
            return ToolBusinessResult.failure("summary 不能空——把你读懂的东西写两三句，哪怕只记住一点");
        }
        String view = takeaway == null ? "" : takeaway.trim();
        try {
            java.util.Optional<BiliViewLog> existing = viewLogs.findFirstByBvidOrderByIdDesc(bvid.trim());
            BiliViewLog entry;
            if (existing.isPresent()) {
                entry = existing.get();
            } else {
                entry = new BiliViewLog();
                entry.setBvid(bvid.trim());
                entry.setTitle("");
                entry.setOutcome(BiliViewLog.OUTCOME_TRANSCRIPT);
                entry.setDetail("");
                entry.setCreatedAt(java.time.LocalDateTime.now());
            }
            entry.setSummary(clip(text, 2000));
            entry.setTakeaway(clip(view, 190));
            viewLogs.save(entry);
            return ToolBusinessResult.success("记下了：" + clip(text, 60)
                    + (view.isBlank() ? "" : "（我的看法：" + clip(view, 40) + "）"));
        } catch (RuntimeException exception) {
            return ToolBusinessResult.failure("记摘要失败：" + exception.getMessage());
        }
    }

    /** 按 bvid 去重，保留**最新**那条（列表是按 id 倒序来的，所以先见到的就是最新的） */
    private java.util.List<BiliViewLog> dedupByBvid(java.util.List<BiliViewLog> rows) {
        java.util.Map<String, BiliViewLog> seen = new java.util.LinkedHashMap<>();
        for (BiliViewLog row : rows) {
            String key = row.getBvid() == null ? "" : row.getBvid().trim();
            seen.putIfAbsent(key, row);
        }
        return new java.util.ArrayList<>(seen.values());
    }

    /**
     * 找回「我以前看过什么」。
     *
     * <p>用户的原话：刷完了就过去了，想再回来找的时候找不到。所以给它一个按内容查的入口
     * ——标题、摘要、它自己的看法三处都匹配。
     *
     * <p><b>2026-10-28 修正</b>：`@Tool` / `@ToolExecutionPolicy` 原来被错挂在下面的私有方法
     * `dedupByBvid` 上（javadoc 在这里、注解在上面），而注册只扫 `getMethods()`（public），
     * 于是这个工具**从来没被注册进工具集**——表现就是"模型说它不能回看自己看过的视频"。
     * 注解挪回来之后，工具数从 64 变回 65。
     */
    @Tool(value = "翻你自己看过的 B 站视频。query 传关键词（比如「git 记忆」「听懂不会做」），留空就是看最近看过的。"
            + "返回 BV 号、标题、你看的时候记的摘要和看法。想再看一遍就把 BV 号传给 readBilibiliVideo。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult recallWatchedVideos(String query, Integer limit) {
        int size = Math.max(1, Math.min(20, limit == null ? 8 : limit));
        java.util.List<BiliViewLog> rows;
        String keyword = query == null ? "" : query.trim();
        try {
            // 2026-10-21：同一个视频会被记多条（每读一次 recordView 插一条，noteWatchedVideo 再补摘要），
            // 不去重的话"我看过哪些"会重复列三遍同一个 BV。按 bvid 去重、保留最新那条。
            if (keyword.isBlank()) {
                rows = dedupByBvid(viewLogs.findTop100ByOrderByIdDesc()).stream().limit(size).toList();
            } else {
                rows = dedupByBvid(viewLogs
                        .findTop30ByTitleContainingIgnoreCaseOrSummaryContainingIgnoreCaseOrTakeawayContainingIgnoreCaseOrderByIdDesc(
                                keyword, keyword, keyword)).stream().limit(size).toList();
            }
        } catch (RuntimeException exception) {
            return ToolBusinessResult.failure("查台账失败：" + exception.getMessage());
        }
        if (rows.isEmpty()) {
            return ToolBusinessResult.success(keyword.isBlank()
                    ? "你还没看过什么视频。"
                    : "没找到跟「" + keyword + "」相关的视频。换个词试试，或者留空看最近看过的。");
        }
        StringBuilder out = new StringBuilder("看过 " + rows.size() + " 条：\n");
        for (BiliViewLog row : rows) {
            out.append("· BV：").append(row.getBvid());
            if (!row.getTitle().isBlank()) {
                out.append("《").append(clip(row.getTitle(), 40)).append("》");
            }
            out.append("（").append(row.getCreatedAt() == null ? "?" :
                    row.getCreatedAt().toLocalDate().toString()).append("）\n");
            if (row.getSummary().isBlank()) {
                out.append("  摘要：（这条当时没记内容，只有标题）\n");
            } else {
                out.append("  讲了什么：").append(clip(row.getSummary(), 260)).append('\n');
            }
            if (!row.getTakeaway().isBlank()) {
                out.append("  我当时想：").append(clip(row.getTakeaway(), 120)).append('\n');
            }
        }
        return ToolBusinessResult.success(out.toString());
    }

    private String describeUnavailable(String title, String reason) {
        return "字幕：" + reason + "\n"
                + "（所以**只能**依据上面这些说明，不要凭标题「" + title + "」编造视频里讲了什么；"
                + "可以如实告诉用户你只看到了标题和简介。）";
    }

    /**
     * 没字幕时的兜底：下一段低清视频、抽帧、拼成联系表交给视觉模型（2026-10-17 加）。
     *
     * <p><b>为什么用联系表而不是几张单图</b>：工具返回只能带文字，能给模型塞图片的通道
     * （{@code addReadableMedia}）一次只有一张图；十几张单图会变成一堆消息。
     * 拼成网格后模型一眼看到整段的走向，而且同样信息量下更省 token——360p 源按 360px 一格基本是原分辨率。
     * 帧多了会切成 2~3 张（每张最多 12 格），否则整图会被上游缩放、格子里就糊了。
     *
     * <p>任何失败都只回一句话，绝不抛异常：字幕已经拿到了的话，抽帧失败不能把结果整个弄没。
     */
    /**
     * 让视频模型直接看这段视频（2026-10-21）。
     *
     * <p>和 {@link #lookAtFrames} 的区别：那边抽几帧静态图、拼成联系表交给主模型；
     * 这边把整段视频交给能干这个活的模型（默认 {@code qwen3.8-flash}），画面、节奏、
     * 台词的语气它自己能看。没配模型时返回 skipped，调用方按老逻辑走。
     *
     * <p>视频要**先下载到本地再 base64**：上游还能直接收公网 URL，但那是让**它的服务器**
     * 去下载，B 站视频有防盗链（服务端 403），所以只能自己下。
     */
    private WatchLook watchVideo(String bvid, long cid) {
        if (videoUnderstandService == null) {
            log.info("视频理解没启用：service 没注入");
            return WatchLook.skipped();
        }
        if (!videoUnderstandService.enabled()) {
            log.info("视频理解没启用：模型/地址/密钥没配全");
            return WatchLook.skipped();
        }
        java.nio.file.Path file = null;
        try {
            BilibiliClient.PlayUrl play = client.playUrl(bvid, cid);
            if (play.sizeBytes() > maxDownloadBytes) {
                return WatchLook.failed("视频太大（" + (play.sizeBytes() / 1024 / 1024) + "MB）");
            }
            file = java.nio.file.Files.createTempFile("va-bili-watch-", ".mp4");
            client.downloadTo(play.url(), bvid, file, maxDownloadBytes);
            com.liche.qqagent.media.VideoUnderstandService.Result result = videoUnderstandService.watch(file, null);
            if (result.ok()) {
                return new WatchLook(true, result.text(), result.promptTokens(), "");
            }
            return WatchLook.failed(result.text());
        } catch (Exception e) {
            return WatchLook.failed("看视频没成（" + shortReason(e) + "）");
        } finally {
            com.liche.qqagent.media.VideoFrameService.deleteQuietly(file);
        }
    }

    /** 视频模型看画面的结果：成没成 / 它说了什么 / 花了多少 token / 没成的原因 */
    private record WatchLook(boolean ok, String text, int promptTokens, String why) {

        /** 没配视频模型（或没开）：不打日志、不提示，安静地按老逻辑走 */
        static WatchLook skipped() {
            return new WatchLook(false, "", 0, "");
        }

        static WatchLook failed(String why) {
            return new WatchLook(false, "", 0, why == null ? "" : why);
        }
    }

    private FrameLook lookAtFrames(String bvid, long cid) {
        if (videoFrameService == null || mediaContext == null) {
            return FrameLook.failed("");
        }
        java.nio.file.Path file = null;
        try {
            BilibiliClient.PlayUrl play = client.playUrl(bvid, cid);
            if (play.sizeBytes() > maxDownloadBytes) {
                return FrameLook.failed("抽帧：这个视频太大（" + (play.sizeBytes() / 1024 / 1024)
                        + "MB），这次没抽帧看画面。\n");
            }
            file = java.nio.file.Files.createTempFile("va-bili-", ".mp4");
            client.downloadTo(play.url(), bvid, file, maxDownloadBytes);
            com.liche.qqagent.media.VideoFrameService.Result frames = videoFrameService.extract(file);
            if (!frames.usable()) {
                return FrameLook.failed("抽帧：" + frames.note() + "（画面也没看到。）\n");
            }
            java.util.List<String> sheets = videoFrameService.contactSheets(frames.frames());
            if (sheets.isEmpty()) {
                return FrameLook.failed("抽帧：抽到画面了但拼图失败，这次没看成。\n");
            }
            int perSheet = (int) Math.ceil(frames.frames().size() / (double) sheets.size());
            for (int i = 0; i < sheets.size(); i++) {
                int from = i * perSheet;
                int to = Math.min(frames.frames().size(), from + perSheet);
                if (from >= to) {
                    continue;
                }
                String where = com.liche.qqagent.media.VideoFrameService.describeTimes(
                        frames.frames().subList(from, to), to - from);
                mediaContext.addReadableMedia(
                        "B站视频《" + bvid + "》没字幕，这是第 " + (i + 1) + "/" + sheets.size()
                                + " 张联系表：从视频里抽出的 " + (to - from) + " 帧画面（约第 " + where
                                + "，按从左到右、从上到下）。看清画面里有什么、场景怎么变；听不到声音，小字也可能糊。",
                        sheets.get(i));
            }
            String where = com.liche.qqagent.media.VideoFrameService.describeTimes(frames.frames(),
                    frames.frames().size());
            return new FrameLook(true, "抽帧：没有字幕，所以我从视频里抽了 " + frames.frames().size()
                    + " 帧画面（约每 4 秒一帧，第 " + where + "），拼成 " + sheets.size()
                    + " 张联系表交给视觉部分看过了。"
                    + "**按画面描述即可，不要假装听到了对白或旁白**；"
                    + "画面里的小字、快速闪过的细节可能看不清，拿不准就说拿不准。\n");
        } catch (Exception e) {
            return FrameLook.failed("抽帧：这次没抽成（" + shortReason(e) + "）。\n");
        } finally {
            com.liche.qqagent.media.VideoFrameService.deleteQuietly(file);
        }
    }

    /** 抽帧兜底的结果：成没成 + 要拼进工具返回的那段话 */
    private record FrameLook(boolean ok, String text) {

        static FrameLook failed(String text) {
            return new FrameLook(false, text);
        }
    }

    private static String shortReason(Exception e) {
        return com.liche.qqagent.support.Texts.errorReason(e, 60);
    }

    private BilibiliClient.Page pickPage(BilibiliClient.VideoInfo info, BilibiliClient.VideoRef ref) {
        Integer wanted = ref.page();
        if (wanted != null && wanted >= 1 && wanted <= info.pages().size()) {
            return info.pages().get(wanted - 1);
        }
        return info.pages().get(0);
    }

    private static String duration(int seconds) {
        if (seconds <= 0) {
            return "未知";
        }
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return minutes >= 60
                ? String.format("%d小时%d分", minutes / 60, minutes % 60)
                : String.format("%d分%d秒", minutes, secs);
    }

    private static String clip(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}

