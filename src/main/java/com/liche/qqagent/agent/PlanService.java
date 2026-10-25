package com.liche.qqagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liche.qqagent.config.LlmScenario;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行规划（2026-10-25，用户拍板的 Plan-and-Execute）。
 *
 * <p><b>为什么不是"每轮先规划"</b>：多一次调用 = +¥0.01 + 3~5 秒，而我们首跳 p50 已经 12.7 秒；
 * 90% 的轮次是"一句话 + 一个工具"，规划对它们没有价值。所以触发条件用**结构信号**——
 * 第一轮就要调 **≥2 个不同工具**（说明这件事天然是多步的），才真的跑一次 Planner。
 * 一句话能办的事，一次规划都不做。
 *
 * <p>产出是一份**可执行步骤清单**（3~5 步），注入上下文后按它走；后面如果失败，
 * {@link TaskReflectionService} 会给 {@code revised_plan}，那才是"重规划"。
 */
@Component
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 规划结果；ok=false 表示没跑成（调用失败/解析不出） */
    public record Plan(boolean ok, List<String> steps, String reason) {

        static Plan failed(String why) {
            return new Plan(false, List.of(), why);
        }

        /** 给模型看的注入文本 */
        public String toPromptBlock() {
            StringBuilder text = new StringBuilder("【执行计划 v1】按这个顺序走，别跳步、也别重复已经做过的：\n");
            for (int i = 0; i < steps.size(); i++) {
                text.append(i + 1).append(". ").append(steps.get(i)).append('\n');
            }
            text.append("（这是程序根据你第一步就要调的多个工具生成的计划；中途发现走不通，就说明原因再换方法。）");
            return text.toString();
        }
    }

    private final ChatModel chatModel;
    private final int maxSteps;

    public PlanService(ChatModel chatModel,
                       @Value("${agent.plan.max-steps:5}") int maxSteps) {
        this.chatModel = chatModel;
        this.maxSteps = Math.max(2, Math.min(8, maxSteps));
    }

    /**
     * 生成计划。
     *
     * @param task     用户这一轮要什么
     * @param tools    第一轮打算/已经调用的工具
     * @param progress 第一轮的结果摘要（可能为空）
     */
    public Plan plan(String task, List<String> tools, String progress) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是这个 Agent 的执行规划员。它接到一个需要多步的任务，请给出可执行计划。\n\n");
            prompt.append("【任务】").append(clip(task, 300)).append("\n");
            prompt.append("【它准备用的工具】").append(String.join("、", tools)).append("\n");
            if (progress != null && !progress.isBlank()) {
                prompt.append("【第一步的结果】").append(clip(progress, 300)).append("\n");
            }
            prompt.append("\n要求：\n")
                    .append("- 只输出 JSON：{\"steps\": [\"一句话一步\", ...]}\n")
                    .append("- 步数 2~").append(maxSteps).append(" 步；每步必须是能直接执行的动作")
                    .append("（调哪个工具、查什么、算/写什么），不要写「分析一下」这种空话；\n")
                    .append("- 已经完成的第一步不要再列；不要编造工具名。\n");
            String raw = LlmScenario.run(LlmScenario.DIALOG, () -> chatModel.chat(prompt.toString()));
            return parse(raw);
        } catch (RuntimeException exception) {
            log.warn("生成执行计划失败（忽略，退回原来的做法）：{}", exception.getMessage());
            return Plan.failed(exception.getMessage());
        }
    }

    private Plan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Plan.failed("模型没返回内容");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Plan.failed("返回里没有 JSON");
        }
        try {
            JsonNode root = JSON.readTree(raw.substring(start, end + 1));
            List<String> steps = new ArrayList<>();
            for (JsonNode node : root.path("steps")) {
                String step = node.asText("").trim();
                if (!step.isBlank()) {
                    steps.add(step);
                }
                if (steps.size() >= maxSteps) {
                    break;
                }
            }
            if (steps.isEmpty()) {
                return Plan.failed("计划是空的");
            }
            log.info("执行计划生成 {} 步：{}", steps.size(), clip(String.join(" / ", steps), 160));
            return new Plan(true, steps, "");
        } catch (Exception exception) {
            return Plan.failed("JSON 解析失败：" + exception.getMessage());
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
