package com.liche.qqagent.bilibili;

import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import com.liche.qqagent.tool.ToolRiskLevel;
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
public class BiliCommentTool implements com.liche.qqagent.tool.AgentToolProvider {

    private final BilibiliClient client;
    private final BiliCommentGuard guard;
    /** 评论配图用（2026-10-21）：B 站评论支持带图，图要先传到它的图床 */
    private final com.liche.qqagent.media.ImageGenClient imageClient;
    /**
     * 只用来「写那一句评论」的一次窄调用（2026-10-20）。
     *
     * <p>用户要求：「关思考只是指生成评论内容的时候，其他时候按原来的不变」。
     * 所以这里单独做一次调用（场景 {@code SELF_COMMENT}，思考档位 {@code none}），
     * 不去动整个自主轮的思考设置。
     */
    private final dev.langchain4j.model.chat.ChatModel chatModel;
    /** 评论台账：查"我发过哪些评论"和"这条回复我回过没有" */
    private final BiliCommentLogRepository comments;

    @org.springframework.beans.factory.annotation.Autowired
    public BiliCommentTool(BilibiliClient client, BiliCommentGuard guard, BiliCommentLogRepository comments,
                           com.liche.qqagent.media.ImageGenClient imageClient,
                           org.springframework.beans.factory.ObjectProvider<dev.langchain4j.model.chat.ChatModel> chatModels) {
        this(client, guard, comments, chatModels == null ? null : chatModels.getIfAvailable(), imageClient);
    }

    private BiliCommentTool(BilibiliClient client, BiliCommentGuard guard, BiliCommentLogRepository comments,
                            dev.langchain4j.model.chat.ChatModel chatModel,
                            com.liche.qqagent.media.ImageGenClient imageClient) {
        this.comments = comments;
        this.chatModel = chatModel;
        this.client = client;
        this.guard = guard;
        this.imageClient = imageClient;
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
    @Tool(value = "先写一句要发到 B 站评论区的話（**只生成不发送**）。写出来再交给 commentBilibili。")
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
                    com.liche.qqagent.config.LlmScenario.run(
                            com.liche.qqagent.config.LlmScenario.SELF_COMMENT,
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

    /** 截断（按列宽留出省略号） */
    private static String clip(String text, int max) {
        return com.liche.qqagent.support.Texts.clipTrimmed(text, max);
    }

    /**
     * 列出「别人在**它自己发的评论**下的回复」（2026-10-20 加）。
     *
     * <p>为什么要有这个工具：它发完评论就走了，别人回了它也不会知道。用户的要求是
     * 「回复笨鱼自己发的评论下，别人回复的话」——所以它得先**看得见**，才谈得上回。
     *
     * <p>会过滤掉两类：**它自己发的**（同 mid）和**已经回过的**（台账里有 parent_rpid 记录），
     * 所以重复调用不会给它一堆"已经处理过"的噪音。
     */
    @Tool(value = "看有没有人回复你发过的评论（只列你还没回过的）。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult listMyCommentReplies(Integer limit) {
        if (!client.configured()) {
            return ToolBusinessResult.failure("B 站登录态没配，读不到回复");
        }
        int perComment = Math.max(1, Math.min(20, limit == null ? 10 : limit));
        java.util.List<BiliCommentLog> mine = comments
                .findTop20ByResultCodeAndRpidGreaterThanOrderByIdDesc(0, 0L).stream().limit(8).toList();
        if (mine.isEmpty()) {
            return ToolBusinessResult.success("你还没在 B 站发过评论，没有人会回复你。");
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (BiliCommentLog log : mine) {
            java.util.List<BilibiliClient.CommentReply> replies;
            try {
                replies = client.replies(log.getAid(), log.getRpid(), perComment);
            } catch (Exception exception) {
                // 单条拉失败不影响其他条（风控 -352 很常见）
                continue;
            }
            for (BilibiliClient.CommentReply reply : replies) {
                if (reply.rpid() == log.getRpid() || reply.message().isBlank()) {
                    continue;
                }
                if (comments.existsByParentRpidAndResultCode(reply.rpid(), 0)) {
                    continue; // 已经回过了
                }
                lines.add("· 在《" + clip(log.getVideoTitle(), 30) + "》下，" + reply.uname()
                        + " 回你：「" + clip(reply.message(), 120) + "」\n"
                        + "  BV：" + log.getBvid() + "  你的评论 rpid=" + log.getRpid()
                        + "  对方回复 rpid=" + reply.rpid());
            }
        }
        if (lines.isEmpty()) {
            return ToolBusinessResult.success("你发过的评论下面还没有人回复你（或者都已经回过了）。");
        }
        return ToolBusinessResult.success("有 " + lines.size() + " 条新回复：\n" + String.join("\n", lines));
    }

    @Tool(value = "在 B 站视频下发评论、或回复某条评论。message 用 draftBilibiliComment 的结果，**别自己现编**。")
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true,
            riskLevel = ToolRiskLevel.HIGH, allowParallel = false, retryable = false)
    @com.liche.qqagent.tool.NonIdempotentTool
    public ToolBusinessResult commentBilibili(String bvid, String message, Long rootRpid, Long parentRpid,
                                              String picturePrompt) {
        String videoId = bvid == null ? "" : bvid.trim();
        if (videoId.isEmpty()) {
            return ToolBusinessResult.failure("没给 bvid，不知道该在哪个视频下评论。");
        }
        if (!client.configured()) {
            return ToolBusinessResult.failure("B 站没有登录态，发不了评论——让机主去面板「B站」页签扫一次码。");
        }

        Optional<String> rejected = guard.reject(videoId, message, rootRpid);
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

        // 2026-10-21：给了 picturePrompt 就先画一张、传到 B 站图床，再带图发。
        // 画图/上传任何一步失败都**不阻断文字评论**——图是锦上添花，不能因为它把话憋回去。
        java.util.List<BilibiliClient.UploadedImage> pictures = null;
        String pictureNote = "";
        if (picturePrompt != null && !picturePrompt.isBlank()) {
            try {
                pictures = drawAndUpload(picturePrompt.trim());
                if (pictures == null || pictures.isEmpty()) {
                    pictureNote = "（图没画成，这条是纯文字）";
                }
            } catch (Exception exception) {
                logIfPossible(exception);
                pictureNote = "（图没上传成功，这条是纯文字：" + shortReason(exception) + "）";
            }
        }

        try {
            BilibiliClient.CommentResult result = pictures == null || pictures.isEmpty()
                    ? client.comment(aid, message.trim(), rootRpid, parentRpid)
                    : client.commentWithPictures(aid, message.trim(), pictures, rootRpid, parentRpid);
            guard.record(videoId, aid, title, message.trim(), rootRpid, parentRpid, result);
            if (!result.ok()) {
                return ToolBusinessResult.failure("B 站没让发（" + result.code() + "：" + result.message() + "），如实跟他说。");
            }
            String me = client.myDisplayName();
            return ToolBusinessResult.success("已经发出去了" + (pictures != null && !pictures.isEmpty() ? "（带图）" : "")
                    + "。" + pictureNote + "rpid=" + result.rpid()
                    + "；视频《" + title + "》；今天第 " + guard.todayCount() + " 条。"
                    + (me.isBlank() ? "" : "\n这条是用「" + me + "」这个账号发的（就是他的账号）——"
                    + "要是他问起是哪个号，你就说这个名字。")
                    + "（B 站回了成功不等于立刻公开可见，可能还在审核，不用因此重发。）");
        } catch (BilibiliClient.BilibiliException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        } catch (Exception exception) {
            guard.record(videoId, aid, title, message, rootRpid, parentRpid, null);
            throw new IllegalStateException("发评论失败：" + exception.getMessage(), exception);
        }
    }
    /** 画一张图并传到 B 站图床；失败返回 null（评论照发，只是没图） */
    private java.util.List<BilibiliClient.UploadedImage> drawAndUpload(String prompt) throws Exception {
        if (imageClient == null || !imageClient.enabled()) {
            return null;
        }
        com.liche.qqagent.media.ImageGenClient.Result generated = imageClient.generate(prompt);
        if (!generated.ok()) {
            return null;
        }
        java.nio.file.Path file = null;
        try {
            file = java.nio.file.Files.createTempFile("va-bili-pic-", ".png");
            java.net.http.HttpClient downloader = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(15)).build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(generated.url()))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .GET()
                    .build();
            java.net.http.HttpResponse<byte[]> response =
                    downloader.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length < 1024) {
                return null;
            }
            java.nio.file.Files.write(file, response.body());
            return java.util.List.of(client.uploadImage(file));
        } finally {
            if (file != null) {
                try {
                    java.nio.file.Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // 临时文件删不掉不影响结果
                }
            }
        }
    }

    private static void logIfPossible(Exception exception) {
        org.slf4j.LoggerFactory.getLogger(BiliCommentTool.class)
                .warn("评论配图没成：{}", exception.toString());
    }

    private static String shortReason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 60 ? message.substring(0, 60) + "…" : message;
    }
}
