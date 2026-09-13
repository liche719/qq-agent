package com.liche.wechatagent.tool;

import com.liche.wechatagent.agent.ThinkingQuota;
import com.liche.wechatagent.config.LlmEscalation;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 让模型**自己申请临时升档**的工具（2026-09-13）。
 *
 * <p>为什么是工具而不是"路由器 agent"：升档要影响的是**当前这一轮**，而模型只有看到问题之后才知道难不难。
 * 外部路由器要多一次 LLM 调用（QQ 场景首字延迟直接翻倍），而且判错了分不清是谁的错；工具形态下模型在已有上下文里申请，
 * **判错最坏只是没升档，不改变正确性**（用户明确要求过"别让模型记得做某事"，见坑 27、39）。
 *
 * <p>升档的效果（由 {@link LlmEscalation} 与 AgentLoop 落实）：后续 model round 用深度思考档、允许更多工具轮、更长的流式超时；
 * 指标里单独记成 {@code dialog_deep}，方便看"升档到底有没有用"。
 */
@Component
public class ThinkingTool implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ThinkingTool.class);

    private final ThinkingQuota quota;
    private final ToolStatusService statusService;

    public ThinkingTool(ThinkingQuota quota, ToolStatusService statusService) {
        this.quota = quota;
        this.statusService = statusService;
    }

    @com.liche.wechatagent.tool.ToolExecutionPolicy(value = com.liche.wechatagent.tool.ToolExecutionClass.FAST,
            hasSideEffect = true, retryable = false, riskLevel = com.liche.wechatagent.tool.ToolRiskLevel.LOW,
            allowParallel = false)
    @com.liche.wechatagent.tool.NonIdempotentTool
    @Tool(value = "申请把当前这一轮升级到更深的思考档：后续轮次会用深度思考、允许更多工具轮、更长的超时。"
            + "只在问题确实复杂时调用——需要多步推理、反复核对、跨多份资料对比，或用户明确要求仔细分析/认真想想。"
            + "闲聊、确认、简单查询、单步操作**不要**调用（有每日额度）。同一轮最多调用一次，调用后继续把任务做完，"
            + "不要停下来问用户。")
    public ToolBusinessResult thinkDeeper(String reason) {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            return ToolBusinessResult.failure("拿不到当前用户上下文，这次就不升档了，按当前档位继续。");
        }
        if (LlmEscalation.active()) {
            return ToolBusinessResult.failure("这一轮已经升过档了，不要重复申请，直接把问题答完。");
        }
        if (!quota.allows(userId)) {
            log.info("升档额度已用完 user={} 今日已用={} 上限={}", userId, quota.used(userId), quota.limit());
            return ToolBusinessResult.failure("今天的深度思考额度已经用完了，按当前档位尽力回答用户；"
                    + "如果需要，可以如实告诉用户这条建议基于较快档位。");
        }
        LlmEscalation.escalate();
        quota.record(userId);
        // 升档后这一轮可能要跑十几秒到几十秒：先给用户一句反馈，别让他对着"正在输入"干等
        statusService.pushNotice("这个我得仔细想想，稍等我一下…");
        log.info("已升档 user={} 今日第 {} 次（上限 {}）reason={}", userId, quota.used(userId), quota.limit(),
                reason == null ? "" : reason.strip());
        return ToolBusinessResult.success("已升档：后面的轮次会用深度思考，也可以多用几轮工具，"
                + "请把这件事一次做完，最后再统一回复用户。");
    }
}
