package com.liche.qqagent.agent;

import com.liche.qqagent.tool.ToolExecutionOutcome;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一轮对话里的"计划与反思"（2026-10-27 从 {@code AgentLoop} 拆出，第 3 步）。
 *
 * <p><b>拆的理由</b>：AgentLoop 里混着两套"该不该做某件事"的判断——要不要规划、要不要反思——
 * 以及它们的状态（planText / reflections / 反思记录 id）。这些判断和"怎么跟模型对话"无关，
 * 单独一类之后，循环里只剩一行"问它要不要注入"。
 *
 * <p><b>职责边界</b>：本类只决定**要不要做、做什么**，把结果作为一条待注入的消息返回；
 * 真正 `messages.add(...)` 仍由调用方做（保持循环对上下文的唯一写入口）。
 *
 * <p><b>状态是每轮一个实例</b>：调用方在每轮开始时 new 一个，因此不需要 reset，
 * 也不会跨轮污染（以前这些字段是 AgentLoop 的局部变量，正是这个原因）。
 */
public class PlanCoordinator {

    /** 规划配置（来自 application.yml） */
    public record PlanConfig(String mode, int minTools) {
    }

    private final PlanService planService;
    private final TaskReflectionService reflectionService;
    private final PlanConfig config;
    private final int maxReflections;

    private boolean planned;
    private String planText = "";
    private int reflections;
    private final List<Long> reflectionIds = new ArrayList<>();

    public PlanCoordinator(PlanService planService, TaskReflectionService reflectionService,
                           PlanConfig config, int maxReflections) {
        this.planService = planService;
        this.reflectionService = reflectionService;
        this.config = config == null ? new PlanConfig("auto", 2) : config;
        this.maxReflections = Math.max(0, maxReflections);
    }

    public String planText() {
        return planText;
    }

    public List<Long> reflectionIds() {
        return reflectionIds;
    }

    /** 记录"模型自己在工具轮里说过的话"，作为没有正式计划时的计划文本 */
    public void noteInlineText(String text) {
        if (planText.isBlank() && text != null && !text.isBlank()) {
            planText = text.strip();
        }
    }

    /**
     * 要不要在工具轮之后生成计划（只在 {@code mode=auto} 时；第一轮或第二轮、且累计工具数达标）。
     *
     * @return 要注入的消息（`（系统规划：…）`），不需要时返回 null
     */
    public String planIfNeeded(int round, Set<String> succeeded, Map<String, ToolExecutionOutcome> failed,
                               Map<String, ToolExecutionOutcome> results,
                               List<ChatMessage> messages, String userId) {
        if (planned || planService == null || !"auto".equalsIgnoreCase(config.mode())) {
            return null;
        }
        if (round > 1 || distinctTools(succeeded, failed) < Math.max(2, config.minTools())) {
            return null;
        }
        planned = true;
        List<String> toolsUsed = new ArrayList<>(succeeded);
        toolsUsed.addAll(failed.keySet());
        PlanService.Plan plan = planService.plan(firstUserText(messages), toolsUsed, briefResults(failed, results));
        if (!plan.ok()) {
            return null;
        }
        planText = String.join(" / ", plan.steps());
        return "（系统规划：" + plan.toPromptBlock() + "）";
    }

    /**
     * 要不要触发一次反思（策略性失败才触发：工具内部重试过仍失败，或轮次过半一次没成）。
     *
     * @return 要注入的消息（`（系统反思：…）`），不需要时返回 null
     */
    public String reflectIfNeeded(int round, int maxRounds, Set<String> succeeded,
                                  Map<String, ToolExecutionOutcome> failed,
                                  List<ChatMessage> messages, String userId) {
        return reflectIfNeeded(round, maxRounds, succeeded, failed, messages, userId, false);
    }

    /**
     * @param force 为 true 时**跳过策略性判据**直接反思（2026-10-29）。
     *              场景：模型拿**同样的参数**反复调同一个工具，每次都失败——这已经不是
     *              "再试一次就好"，而是策略本身错了，必须停下来重规划。
     *              仍然遵守每轮反思次数上限，避免被它拖成无限反思。
     */
    public String reflectIfNeeded(int round, int maxRounds, Set<String> succeeded,
                                  Map<String, ToolExecutionOutcome> failed,
                                  List<ChatMessage> messages, String userId, boolean force) {
        if (reflections >= maxReflections || reflectionService == null) {
            return null;
        }
        if (!force && !shouldReflect(round, maxRounds, succeeded, failed)) {
            return null;
        }
        reflections++;
        Map<String, String> reasons = new LinkedHashMap<>();
        Map<String, FailureKind> kinds = new LinkedHashMap<>();
        failed.forEach((name, outcome) -> {
            String reason = outcome == null ? "" : outcome.failureReason();
            reasons.put(name, reason);
            kinds.put(name, FailureKind.classify(reason, outcome == null ? 0 : outcome.attempts()));
        });
        TaskReflectionService.Result result = reflectionService.reflect(userId, firstUserText(messages), planText,
                reasons, kinds, new ArrayList<>(succeeded), round, maxRounds);
        if (!result.ok()) {
            return null;
        }
        reflectionIds.addAll(result.savedIds());
        return "（系统反思：" + result.toPromptBlock() + "）";
    }

    /**
     * 策略性失败的判据：
     * ① 某个工具内部已经重试过（attempts≥2）还是失败 → 大概率是策略问题；
     * ② 轮次过半、一次都没成功、但有失败 → 再走下去也是撞墙。
     */
    static boolean shouldReflect(int round, int maxRounds, Set<String> succeeded,
                                 Map<String, ToolExecutionOutcome> failed) {
        if (failed == null || failed.isEmpty()) {
            return false;
        }
        for (ToolExecutionOutcome outcome : failed.values()) {
            if (outcome != null && outcome.attempts() >= 2) {
                return true;
            }
        }
        return round + 1 >= Math.max(1, maxRounds / 2) && (succeeded == null || succeeded.isEmpty());
    }

    static int distinctTools(Set<String> succeeded, Map<String, ToolExecutionOutcome> failed) {
        Set<String> names = new java.util.HashSet<>(succeeded == null ? Set.of() : succeeded);
        if (failed != null) {
            names.addAll(failed.keySet());
        }
        return names.size();
    }

    /** 这一轮用户到底要什么：取第一条用户消息 */
    static String firstUserText(List<ChatMessage> messages) {
        if (messages == null) {
            return "";
        }
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage user) {
                String text = user.singleText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    /** 第一轮的结果摘要（给 Planner 当上下文，别太长） */
    static String briefResults(Map<String, ToolExecutionOutcome> failed, Map<String, ToolExecutionOutcome> results) {
        StringBuilder text = new StringBuilder();
        if (results != null) {
            results.forEach((name, outcome) -> {
                if (outcome != null && outcome.successful()) {
                    text.append(name).append("：成功。");
                }
            });
        }
        if (failed != null) {
            failed.forEach((name, outcome) -> {
                if (outcome != null) {
                    text.append(name).append("：失败（")
                            .append(outcome.failureReason() == null ? "" : outcome.failureReason()).append("）。");
                }
            });
        }
        String flat = text.toString().replaceAll("\\s+", " ").trim();
        return flat.length() <= 240 ? flat : flat.substring(0, 240) + "…";
    }
}
