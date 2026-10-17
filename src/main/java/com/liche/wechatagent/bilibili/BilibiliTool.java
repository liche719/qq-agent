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

    private String describeUnavailable(String title, String reason) {
        return "字幕：" + reason + "\n"
                + "（所以**只能**依据上面这些说明，不要凭标题「" + title + "」编造视频里讲了什么；"
                + "可以如实告诉用户你只看到了标题和简介。）";
    }

    /**
     * 没字幕时的兜底：下一段低清视频、抽帧、拼成一张联系表交给视觉模型（2026-10-17 加）。
     *
     * <p><b>为什么用联系表而不是几张单图</b>：工具返回只能带文字，能给模型塞图片的通道
     * （{@code addReadableMedia}）一次只有一张图；6~10 张单独发会变成一堆消息。
     * 拼成一张 3×2/2×2 的网格，模型一眼看到整段的走向，token 还比单图省一半以上。
     * 代价是每格变小——所以这条路径**只看"视频大概拍了什么"**，不指望看清小字。
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
            String sheet = videoFrameService.contactSheet(frames.frames());
            if (sheet == null) {
                return FrameLook.failed("抽帧：抽到画面了但拼图失败，这次没看成。\n");
            }
            String where = com.liche.wechatagent.media.VideoFrameService.describeTimes(frames.frames(),
                    frames.frames().size());
            mediaContext.addReadableMedia(
                    "B站视频《" + bvid + "》没字幕，这里是从视频里抽出的 " + frames.frames().size()
                            + " 帧画面拼成的联系表（按从左到右、从上到下大约对应第 " + where + "）。"
                            + "看清画面里有什么、场景怎么变；听不到声音，小字也可能糊。",
                    sheet);
            return new FrameLook(true, "抽帧：没有字幕，所以我从视频里抽了 " + frames.frames().size()
                    + " 帧画面拼成一张图（第 " + where + " 附近）交给视觉部分看过了。"
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
