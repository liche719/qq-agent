package com.liche.wechatagent.bilibili;

import com.liche.wechatagent.tool.AgentToolProvider;
import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
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

    private static final int DESC_PREVIEW_CHARS = 300;
    /** browseBilibili 默认给几条候选：够挑又不至于让他看得眼花 */
    private static final int DEFAULT_BROWSE_COUNT = 12;

    private final BilibiliClient client;
    private final com.liche.wechatagent.media.VideoFrameService videoFrameService;
    private final com.liche.wechatagent.media.MediaToolContextService mediaContext;
    private final int maxTranscriptChars;
    /** 抽帧兜底最多下多少字节的视频（360p，2 分钟约 9MB；这里给到 60MB ≈ 十几分钟） */
    private final long maxDownloadBytes;

    public BilibiliTool(BilibiliClient client,
                        com.liche.wechatagent.media.VideoFrameService videoFrameService,
                        com.liche.wechatagent.media.MediaToolContextService mediaContext,
                        @Value("${bilibili.max-transcript-chars:6000}") int maxTranscriptChars,
                        @Value("${bilibili.frame-fallback-max-bytes:62914560}") long maxDownloadBytes) {
        this.client = client;
        this.videoFrameService = videoFrameService;
        this.mediaContext = mediaContext;
        this.maxTranscriptChars = Math.max(500, Math.min(20_000, maxTranscriptChars));
        this.maxDownloadBytes = Math.max(5L * 1024 * 1024, maxDownloadBytes);
    }

    @Tool(value = "读取一个 B 站视频的字幕，用来知道视频里到底讲了什么。用户转发 B 站视频、"
            + "发来 B 站链接或 BV 号时调用。参数 url 传链接或 BV 号都行；"
            + "**如果只有标题（比如他转发过来的卡片没有带链接），直接把那个标题原样传进来**——"
            + "我会拿标题去 B 站搜，只有搜到标题逐字一致的视频才会用。"
            + "返回标题、UP 主、时长、简介和带时间戳的字幕正文。"
            + "如果这个视频没有字幕（或接口拿不到），结果会明确写出来——"
            + "**这时只能依据标题和简介，不要编造视频里的内容**。")
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
            if (matchedByTitle != null && !matchedByTitle.isBlank()) {
                out.append("（这是按他转发过来的标题搜到的，原标题：「").append(matchedByTitle)
                        .append("」，已核对**逐字一致**）\n");
            }
            out.append("UP主：").append(info.owner().isBlank() ? "未知" : info.owner())
                    .append("，时长：").append(duration(info.durationSeconds())).append('\n');

            if (info.pages().isEmpty()) {
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
            if (transcript.available()) {
                out.append("字幕（").append(transcript.language().isBlank() ? "语言未知" : transcript.language())
                        .append(transcript.aiGenerated() ? "，B站自动生成" : "，UP主上传").append("）：\n");
                out.append(transcript.text());
            } else {
                // 没字幕不等于看不到：2026-10-17 起会下 360p 抽帧兜底，把画面交给视觉模型（见 lookAtFrames）
                out.append("字幕：").append(transcript.reason()).append('\n');
                FrameLook look = lookAtFrames(info.bvid(), page.cid());
                out.append(look.text());
                if (!look.ok()) {
                    // 抽帧也没成，才说"只能依据标题和简介"——抽成了再说这句会自相矛盾
                    out.append("\n（所以**只能**依据上面的简介，不要凭标题「").append(info.title())
                            .append("」编造视频里讲了什么。）");
                }
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

    @Tool(value = "看看 B 站上有什么视频。用户说「看看 B 站」「刷会儿 B 站」「有什么好玩的」时调用，"
            + "或者你自己想找点东西看时也可以。source 三选一："
            + "hot=全站热门（不用登录）、following=他关注的 UP 主更新（要登录）、later=他的稍后再看（要登录）；"
            + "count 是条数，1~30，默认 12。返回标题、UP 主、时长、播放量、BV 号。"
            + "**它只是目录**：想知道某个视频讲了什么，把 BV 号传给 readBilibiliVideo 再读字幕/抽帧。")
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
                        .append("   BV：").append(item.bvid());
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
            com.liche.wechatagent.media.VideoFrameService.Result frames = videoFrameService.extract(file);
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
                String where = com.liche.wechatagent.media.VideoFrameService.describeTimes(
                        frames.frames().subList(from, to), to - from);
                mediaContext.addReadableMedia(
                        "B站视频《" + bvid + "》没字幕，这是第 " + (i + 1) + "/" + sheets.size()
                                + " 张联系表：从视频里抽出的 " + (to - from) + " 帧画面（约第 " + where
                                + "，按从左到右、从上到下）。看清画面里有什么、场景怎么变；听不到声音，小字也可能糊。",
                        sheets.get(i));
            }
            String where = com.liche.wechatagent.media.VideoFrameService.describeTimes(frames.frames(),
                    frames.frames().size());
            return new FrameLook(true, "抽帧：没有字幕，所以我从视频里抽了 " + frames.frames().size()
                    + " 帧画面（约每 4 秒一帧，第 " + where + "），拼成 " + sheets.size()
                    + " 张联系表交给视觉部分看过了。"
                    + "**按画面描述即可，不要假装听到了对白或旁白**；"
                    + "画面里的小字、快速闪过的细节可能看不清，拿不准就说拿不准。\n");
        } catch (Exception e) {
            return FrameLook.failed("抽帧：这次没抽成（" + shortReason(e) + "）。\n");
        } finally {
            com.liche.wechatagent.media.VideoFrameService.deleteQuietly(file);
        }
    }

    /** 抽帧兜底的结果：成没成 + 要拼进工具返回的那段话 */
    private record FrameLook(boolean ok, String text) {

        static FrameLook failed(String text) {
            return new FrameLook(false, text);
        }
    }

    private static String shortReason(Exception e) {
        String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
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
