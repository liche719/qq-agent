package com.liche.qqagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.qqagent.config.LlmScenario;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务级反思（2026-10-25）——**独立一次调用 + 结构化 JSON + 能驱动重规划**。
 *
 * <p>用户的原话：「独立"反思调用"+ 结构化 JSON + 重规划」。灵感来自 ReAct 之后的范式
 * （Reflexion / Plan-and-Execute 的组合），但只借"失败后归因、然后换条路"这一件事：
 * <ul>
 *   <li>**只在真正的策略性失败时调用**：同一工具内部重试仍失败（attempts≥2）、或轮次过半却一次都没成功；</li>
 *   <li>每轮最多调 1 次（配置项），避免"反思套反思"把成本翻倍；</li>
 *   <li>输出必须是 JSON——散文反思没法用，也无法判断该不该重规划；</li>
 *   <li>{@code shouldReplan=true} 时，把 {@code revisedPlan} 作为新的行动指引注入上下文，
 *       这才是"反思驱动重规划"，而不是往上下文里加一段感想。</li>
 * </ul>
 *
 * <p>计费走 {@link LlmScenario#REFLECT} 档，面板「花费」里能单独看到这类调用。
 */
@Component
public class TaskReflectionService {

    private static final Logger log = LoggerFactory.getLogger(TaskReflectionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 反思结果；ok=false 表示这次没跑成（调用失败或返回不可解析） */
    public record Result(boolean ok, String failureType, String rootCause, boolean replan,
                         List<String> revisedPlan, String nextAction) {

        static Result failed(String why) {
            return new Result(false, "", why, false, List.of(), "");
        }

        /** 给模型看的注入文本：重规划时给出步骤，否则给一句归因 + 下一步 */
        public String toPromptBlock() {
            StringBuilder text = new StringBuilder();
            if (replan && !revisedPlan.isEmpty()) {
                text.append("【重新规划】上面的做法走不通，按下面来：\n");
                for (int i = 0; i < revisedPlan.size(); i++) {
                    text.append(i + 1).append(". ").append(revisedPlan.get(i)).append('\n');
                }
            } else {
                text.append("【失败归因】").append(rootCause.isBlank() ? "上一步没成功" : rootCause).append('\n');
            }
            if (!nextAction.isBlank()) {
                text.append("【下一步】").append(nextAction).append('\n');
            }
            text.append("（这是程序在你连续失败后做的归因，不是用户说的。别再重复同样的调用；"
                    + "实在做不到就如实告诉他，不要硬试。）");
            return text.toString();
        }
    }

    private final ChatModel chatModel;
    private final boolean enabled;
    private final int evidenceChars;

    public TaskReflectionService(ChatModel chatModel,
                                 @Value("${agent.reflect.enabled:true}") boolean enabled,
                                 @Value("${agent.reflect.evidence-chars:200}") int evidenceChars) {
        this.chatModel = chatModel;
        this.enabled = enabled;
        this.evidenceChars = Math.max(80, Math.min(600, evidenceChars));
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 跑一次反思。
     *
     * @param task     用户这一轮到底要什么（第一条用户消息）
     * @param failed   工具名 → 失败原因
     * @param succeeded 已经成功的工具
     * @param round    当前是第几轮（从 0 开始）
     * @param maxRounds 这一轮最多几轮
     */
    public Result reflect(String task, String plan, Map<String, String> failed, List<String> succeeded,
                          int round, int maxRounds) {
        if (!enabled) {
            return Result.failed("反思开关关着");
        }
        try {
            String prompt = buildPrompt(task, plan, failed, succeeded, round, maxRounds);
            String raw = LlmScenario.run(LlmScenario.REFLECT, () -> chatModel.chat(prompt));
            return parse(raw);
        } catch (RuntimeException exception) {
            log.warn("任务反思调用失败：{}", exception.getMessage());
            return Result.failed(exception.getMessage());
        }
    }

    private String buildPrompt(String task, String plan, Map<String, String> failed, List<String> succeeded,
                               int round, int maxRounds) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是这个 Agent 的「失败复盘员」。它刚刚连续失败，请判断**是哪里错了、下一步该怎么改**。\n\n");
        prompt.append("【用户要什么】").append(clip(task, 300)).append("\n\n");
        prompt.append("【失败的尝试】\n");
        if (failed == null || failed.isEmpty()) {
            prompt.append("（没有失败记录）\n");
        } else {
            failed.forEach((tool, why) ->
                    prompt.append("- ").append(tool).append("：").append(clip(why, evidenceChars)).append('\n'));
        }
        prompt.append("\n【已经成功过的工具】")
                .append(succeeded == null || succeeded.isEmpty() ? "（无）" : String.join("、", succeeded)).append('\n');
        prompt.append("【轮次】第 ").append(round + 1).append(" / ").append(maxRounds).append(" 轮\n\n");
        prompt.append("""
                只输出一个 JSON 对象，不要解释、不要代码块：
                {
                  "failure_type": "tool_limitation | wrong_plan | wrong_params | ambiguous_goal | no_capability",
                  "root_cause": "一句话说清为什么失败（30 字内）",
                  "plan_is_wrong": true或false,
                  "should_replan": true或false,
                  "revised_plan": ["改成这样做（1~3 条，每条一句话）"],
                  "next_action": "下一步具体做什么（一句话，20 字内）"
                }
                判断规则：
                - 参数写错、工具重复返回"查无结果" → failure_type=wrong_params，should_replan=false；
                - 方法本身走不通、需要换工具或换思路 → failure_type=wrong_plan，should_replan=true，并给 revised_plan；
                - 我们根本没有这个能力 → failure_type=no_capability，should_replan=false，next_action 写"如实告诉他做不到"。
                """);
        return prompt.toString();
    }

    private Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.failed("模型没返回内容");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Result.failed("返回里没有 JSON");
        }
        try {
            JsonNode root = JSON.readTree(raw.substring(start, end + 1));
            List<String> plan = new ArrayList<>();
            for (JsonNode node : root.path("revised_plan")) {
                String step = node.asText("").trim();
                if (!step.isBlank()) {
                    plan.add(step);
                }
            }
            boolean replan = root.path("should_replan").asBoolean(false);
            String next = root.path("next_action").asText("").trim();
            Result result = new Result(true, root.path("failure_type").asText("").trim(),
                    root.path("root_cause").asText("").trim(), replan, plan, next);
            log.info("任务反思完成 type={} replan={} cause={}", result.failureType(), replan,
                    clip(result.rootCause(), 60));
            return result;
        } catch (Exception exception) {
            return Result.failed("JSON 解析失败：" + exception.getMessage());
        }
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
