package com.liche.wechatagent.bilibili;

import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolRiskLevel;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 「在 B 站说句话」——发评论 / 回复楼中楼（2026-10-20 加，**写操作**）。
 *
 * <p>用户的原话：<i>"相当于笨鱼自己在刷视频发评论而已（这些内容也会同步记录在某个地方），
 * 如果有想要给我看的，就通过 qq 发给我并说话"</i>。所以这个工具只管"发出去 + 记台账"，
 * 通知他靠 QQ 那套；护栏全在 {@link BiliCommentGuard} 里（硬拦，不靠模型自觉）。
 *
 * <p>单独一个类而不是塞进 {@code BilibiliTool}：那边已经有两个构造器（含给测试用的），
 * 再动构造器容易踩"忘了 @Autowired 就启动即挂"的坑（坑 70）。
 */
@Component
public class BiliCommentTool implements com.liche.wechatagent.tool.AgentToolProvider {

    private final BilibiliClient client;
    private final BiliCommentGuard guard;
    /**
     * 只用来「写那一句评论」的一次窄调用（2026-10-20）。
     *
     * <p>用户要求：「关思考只是指生成评论内容的时候，其他时候按原来的不变」。
     * 所以这里单独做一次调用（场景 {@code SELF_COMMENT}，思考档位 {@code none}），
     * 不去动整个自主轮的思考设置。
     */
    private final dev.langchain4j.model.chat.ChatModel chatModel;

    @org.springframework.beans.factory.annotation.Autowired
    public BiliCommentTool(BilibiliClient client, BiliCommentGuard guard,
                           org.springframework.beans.factory.ObjectProvider<dev.langchain4j.model.chat.ChatModel> chatModels) {
        this(client, guard, chatModels == null ? null : chatModels.getIfAvailable());
    }

    private BiliCommentTool(BilibiliClient client, BiliCommentGuard guard,
                            dev.langchain4j.model.chat.ChatModel chatModel) {
        this.chatModel = chatModel;
        this.client = client;
        this.guard = guard;
    }

    /**
     * 把「随口说一句」单独做成一次调用（2026-10-20）。
     *
     * <p>为什么不直接让主对话写：主对话那一轮开着思考（值不值得看、要不要评，需要想清楚），
     * 而**同一句生成**里带着思考，写出来就偏"评论员"腔。拆开之后：
     * 判断照旧动脑子，落笔只凭直觉——这才像人。
     *
     * <p>它**不发送**，只返回那句话；发送仍然走 {@code commentBilibili}，护栏也仍然在那里拦。
     */
    @Tool(value = "先写出一句要发到 B 站评论区的話（**只生成，不发送**）。"
            + "在真正调用 commentBilibili 之前先用它，把它返回的那句原样传给 commentBilibili。"
            + "参数：video 是视频标题，point 是你想说的那个**具体点**（某句话、某个画面、某个数字）。"
            + "它只写一句 10~25 字的人话，不写总结、不带链接、不评价视频好坏。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult draftBilibiliComment(String video, String point) {
        if (chatModel == null) {
            return ToolBusinessResult.failure("现在没有可用的模型，这条别评了");
        }
        String title = video == null ? "" : video.trim();
        String key = point == null ? "" : point.trim();
        if (key.isBlank()) {
            return ToolBusinessResult.failure("先说清你想说的是哪一点（point），不然写出来就是空话");
        }
        String prompt = "你在 B 站看了一个视频，想随口留一句评论。\n\n"
                + "视频标题：" + (title.isBlank() ? "（未提供）" : title) + "\n"
                + "你想说的那个点：" + key + "\n\n"
                + "只输出那一句评论本身，不要引号、不要解释、不要换行。\n"
                + "要求：\n"
                + "- 10~25 个字，最多不超过 40 个。\n"
                + "- 像人随口说的：可以没主语、可以半句话、可以用「这」「那」「就」。\n"
                + "- 只说那个具体的点，不要总结视频、不要评价「很有价值／值得看」。\n"
                + "- 不许用：首先、其次、总之、总的来说、不得不说、值得一提的是、可以看出、建议、希望。\n"
                + "- 不要感叹号堆砌，最多一个。\n"
                + "- 不要提账号、机器、平台，不要放链接。\n";

        try {
            dev.langchain4j.model.chat.request.ChatRequest request =
                    dev.langchain4j.model.chat.request.ChatRequest.builder()
                            .messages(java.util.List.of(dev.langchain4j.data.message.UserMessage.from(prompt)))
                            .build();
            dev.langchain4j.model.chat.response.ChatResponse response =
                    com.liche.wechatagent.config.LlmScenario.run(
                            com.liche.wechatagent.config.LlmScenario.SELF_COMMENT,
                            () -> chatModel.chat(request));
            String text = response == null || response.aiMessage() == null ? "" : response.aiMessage().text();
            String cleaned = text == null ? "" : text.trim()
                    .replace("\"", "")
                    .replace("「", "")
                    .replace("」", "");
            if (cleaned.isBlank()) {
                return ToolBusinessResult.failure("这次没写出东西，换个说法再试一次，或者这条就别评了");
            }
            return ToolBusinessResult.success("这句可以直接发（原样传给 commentBilibili 的 message）：\n" + cleaned);
        } catch (RuntimeException exception) {
            return ToolBusinessResult.failure("写评论失败了：" + exception.getMessage() + "（这条别评了）");
        }
    }

    @Tool(value = "在 B 站视频下发一条评论，或回复某条评论（楼中楼）。"
            + "**这是拿机主的账号说话，只有这两种情况才用**：①你自己刷视频时确实有话想说；"
            + "②机主明确让你去发。别因为一句闲聊就发评论。"
            + "message 用你自己的语气、短、像人说的话，别写成总结报告，别提机主的账号/机器/聊天记录，不要放链接。"
            + "要回复楼中楼就传 rootRpid（那一楼的根评论号）和 parentRpid（你回复的那条评论号）。"
            + "护栏：每天最多几条、同一视频只评一次、两条之间要隔一段时间——被拦了会告诉你原因，如实转述、别绕。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true,
            riskLevel = ToolRiskLevel.HIGH, allowParallel = false, retryable = false)
    @com.liche.wechatagent.tool.NonIdempotentTool
    public ToolBusinessResult commentBilibili(String bvid, String message, Long rootRpid, Long parentRpid) {
        String videoId = bvid == null ? "" : bvid.trim();
        if (videoId.isEmpty()) {
            return ToolBusinessResult.failure("没给 bvid，不知道该在哪个视频下评论。");
        }
        if (!client.configured()) {
            return ToolBusinessResult.failure("B 站没有登录态，发不了评论——让机主去面板「B站」页签扫一次码。");
        }

        Optional<String> rejected = guard.reject(videoId, message);
        if (rejected.isPresent()) {
            return ToolBusinessResult.failure(rejected.get());
        }

        long aid;
        String title;
        try {
            BilibiliClient.VideoInfo info = client.videoInfo(new BilibiliClient.VideoRef(videoId, null, 1));
            aid = info.aid();
            title = info.title();
        } catch (Exception exception) {
            return ToolBusinessResult.failure("先查这个视频的信息失败了：" + exception.getMessage() + "，这次没发。");
        }

        try {
            BilibiliClient.CommentResult result = client.comment(aid, message.trim(), rootRpid, parentRpid);
            guard.record(videoId, aid, title, message.trim(), rootRpid, parentRpid, result);
            if (!result.ok()) {
                return ToolBusinessResult.failure("B 站没让发（" + result.code() + "：" + result.message() + "），如实跟他说。");
            }
            return ToolBusinessResult.success("已经发出去了。rpid=" + result.rpid()
                    + "；视频《" + title + "》；今天第 " + guard.todayCount() + " 条。"
                    + "（B 站回了成功不等于立刻公开可见，可能还在审核，不用因此重发。）");
        } catch (BilibiliClient.BilibiliException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        } catch (Exception exception) {
            guard.record(videoId, aid, title, message, rootRpid, parentRpid, null);
            throw new IllegalStateException("发评论失败：" + exception.getMessage(), exception);
        }
    }
}
