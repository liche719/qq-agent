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
    private final int maxTranscriptChars;

    public BilibiliTool(BilibiliClient client,
                        @Value("${bilibili.max-transcript-chars:6000}") int maxTranscriptChars) {
        this.client = client;
        this.maxTranscriptChars = Math.max(500, Math.min(20_000, maxTranscriptChars));
    }

    @Tool(value = "读取一个 B 站视频的字幕，用来知道视频里到底讲了什么。用户转发 B 站视频、"
            + "发来 B 站链接或 BV 号时调用。参数 url 传链接或 BV 号都行。"
            + "返回标题、UP 主、时长、简介和带时间戳的字幕正文。"
            + "如果这个视频没有字幕（或接口拿不到），结果会明确写出来——"
            + "**这时只能依据标题和简介，不要编造视频里的内容**。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult readBilibiliVideo(String url) {
        try {
            BilibiliClient.VideoRef ref = client.parse(url);
            BilibiliClient.VideoInfo info = client.videoInfo(ref);

            StringBuilder out = new StringBuilder("【B站视频】").append(info.title()).append('\n');
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
                out.append(describeUnavailable(info.title(), transcript.reason()));
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
