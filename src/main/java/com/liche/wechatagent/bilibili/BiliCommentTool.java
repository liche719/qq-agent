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

    public BiliCommentTool(BilibiliClient client, BiliCommentGuard guard) {
        this.client = client;
        this.guard = guard;
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
