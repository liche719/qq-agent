package com.liche.wechatagent.self;

import com.liche.wechatagent.agent.AgentLoop;
import com.liche.wechatagent.agent.TurnScope;
import com.liche.wechatagent.config.LlmSpendMeter;
import com.liche.wechatagent.tool.ToolRegistry;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserService;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 跑「它自己的一轮」（2026-10-20 加）。
 *
 * <p>抽出来是因为{@link SelfQuestService} 里那段"起一轮 agent + 记账 + 算 token"是通用的，
 * 但它和 quest 的方向/笔记/续期逻辑缠在一起。新活动（刷 B 站，以后刷小红书）只需要
 * "给一段指令、一个工具白名单、一点预算，把这一轮跑完并报账"，不需要 quest 那套。
 *
 * <p><b>故意不复用 quest 的作业行</b>（{@code agent_quest_run}）：那张表是"研究方向"的账，
 * 刷视频记进去会让面板上"我的方向"变脏。活动的痕迹走 {@code agent_self_event}（带 {@code topic}）。
 */
@Component
public class SelfTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(SelfTurnRunner.class);

    private final AgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final LlmSpendMeter spendMeter;
    private final UserService userService;

    public SelfTurnRunner(AgentLoop agentLoop,
                          ToolRegistry toolRegistry,
                          LlmSpendMeter spendMeter,
                          UserService userService) {
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.spendMeter = spendMeter;
        this.userService = userService;
    }

    /** 一轮的账（日志与活动结果用） */
    public record Result(String reply, int promptTokens, int completionTokens,
                         long durationMs, double costYuan) {
    }

    /**
     * 跑一轮。
     *
     * @param owner       机主（人设从这里取）
     * @param activityKey 活动名，只用于日志
     * @param instruction 这一轮要它干什么
     * @param scope       工具白名单 + 轮次 + 预算
     */
    public Result run(String owner, String activityKey, String instruction, TurnScope scope) {
        List<String> toolNames = toolRegistry.specificationsOf(scope).stream()
                .map(ToolSpecification::name)
                .toList();
        log.info("自主活动开跑 活动={} 预算={} 元 可用工具 {} 个：{}",
                activityKey, String.format("%.3f", scope.budgetYuan()), toolNames.size(),
                String.join("、", toolNames));

        double costBefore = spendMeter == null ? 0 : spendMeter.totalYuan();
        long started = System.nanoTime();
        // 2026-10-20：这一轮绑定 SELF 场景，思考档位配成 none（用户要求"发 B 站评论的思考程度设置成无"）。
        // 实测 reasoning_effort=none 时上游 reasoning_tokens=0；想太多反而写出 AI 腔。
        String reply = com.liche.wechatagent.config.LlmScenario.run(
                com.liche.wechatagent.config.LlmScenario.SELF,
                () -> agentLoop.chat(SelfCoreService.SELF_SCOPE, null, null, personaOf(owner), null, null,
                        List.of(), instruction, List.of(), List.of(), null, scope));
        long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        double yuan = Math.max(0, (spendMeter == null ? 0 : spendMeter.totalYuan()) - costBefore);
        return new Result(reply, 0, 0, durationMs, yuan);
    }

    /** 人设还是它自己那套（同一个人），只是这一轮做的事不一样 */
    private String personaOf(String owner) {
        try {
            UserProfile profile = userService.get(owner);
            return profile == null ? null : profile.getPersona();
        } catch (RuntimeException exception) {
            log.warn("取人设失败，按没有人设继续：{}", exception.getMessage());
            return null;
        }
    }
}

