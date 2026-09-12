package com.liche.wechatagent.interview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试题库：按题类组织，每个题类带"评分观察点"，供模型选题与打分时对齐标准。
 *
 * <p>题库写在这里而不是数据库：题目是创作内容、随版本迭代，放代码里方便改也方便 diff；
 * 用户练过的题由 {@link InterviewRound} 记录，所以"不重复出题"靠的是记录而不是题库表。
 */
public final class InterviewBank {

    /** 题类 → 该类的题目列表（每类按难度从易到难） */
    private static final Map<String, List<String>> QUESTIONS = new LinkedHashMap<>();

    /** 题类 → 评分观察点（模型打分时对照这个，避免分随心情漂） */
    private static final Map<String, String> RUBRIC = new LinkedHashMap<>();

    static {
        QUESTIONS.put("自我介绍", List.of(
                "先用 1 分钟做个自我介绍",
                "介绍一个你最有成就感的项目",
                "为什么想换工作 / 为什么投这个岗位"));
        RUBRIC.put("自我介绍", "是否有清晰主线（身份+能力+动机）、能否 60~90 秒讲完、有没有具体数字");

        QUESTIONS.put("项目深挖", List.of(
                "挑一个你负责的项目，讲讲整体架构和你负责的部分",
                "这个项目里最难的技术问题是什么，你怎么定位和解决的",
                "如果流量涨 10 倍，你的项目会先崩在哪，你会怎么改",
                "项目里有没有做过取舍？为什么放弃了另一个方案",
                "你在这个项目里犯过的最大的错是什么，后来怎么补救"));
        RUBRIC.put("项目深挖", "能否讲清背景-目标-方案-结果、有没有量化数据、遇到追问能否给出细节和取舍理由");

        QUESTIONS.put("技术基础", List.of(
                "讲讲你最熟悉的技术栈里，一个你觉得容易被误解的机制",
                "线上 CPU 突然打满，你的排查顺序是什么",
                "数据库索引为什么会失效？你遇到过哪些情况",
                "缓存和数据库怎么保证一致性，你们怎么做的",
                "一个接口突然变慢，你会从哪些方向定位"));
        RUBRIC.put("技术基础", "回答是否分层（现象→假设→验证）、有没有真实经历、是否会主动说边界条件");

        QUESTIONS.put("系统设计", List.of(
                "设计一个短链服务，你怎么做",
                "设计一个消息推送系统，如何保证不丢不重",
                "设计一个排行榜，百万用户实时更新怎么扛",
                "如何设计一个幂等的下单接口"));
        RUBRIC.put("系统设计", "是否先问需求与量级、有没有容量估算、能不能说出方案取舍与瓶颈");

        QUESTIONS.put("行为面试", List.of(
                "讲一次你和同事意见不合的经历，最后怎么解决的",
                "讲一次你在压力下完成任务的经历",
                "讲一次你主动推动、改变了结果的事",
                "你怎么处理需求频繁变更"));
        RUBRIC.put("行为面试", "是否用 STAR（情境-任务-行动-结果）结构、结果是否具体、有没有反思");

        QUESTIONS.put("反问环节", List.of(
                "你还有什么想问我的",
                "关于团队和业务，你想了解什么"));
        RUBRIC.put("反问环节", "是否问了能体现思考的问题（团队分工、技术挑战、考核方式），而不是只问薪资福利");
    }

    private InterviewBank() {
    }

    public static List<String> categories() {
        return List.copyOf(QUESTIONS.keySet());
    }

    public static List<String> questionsOf(String category) {
        List<String> list = QUESTIONS.get(category);
        return list == null ? List.of() : list;
    }

    public static String rubricOf(String category) {
        return RUBRIC.getOrDefault(category, "");
    }

    /** 拼成给模型看的题库文本（含评分观察点）。 */
    public static String asPromptText() {
        StringBuilder sb = new StringBuilder();
        QUESTIONS.forEach((category, questions) -> {
            sb.append("【").append(category).append("】\n");
            for (String q : questions) {
                sb.append("  - ").append(q).append("\n");
            }
            sb.append("  评分观察点：").append(RUBRIC.getOrDefault(category, "")).append("\n");
        });
        return sb.toString().trim();
    }

    /** 题类是否合法（大小写、空格容错）。 */
    public static String normalizeCategory(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        for (String category : QUESTIONS.keySet()) {
            if (category.equals(value) || category.contains(value) || value.contains(category)) {
                return category;
            }
        }
        return QUESTIONS.containsKey(value) ? value : null;
    }
}
