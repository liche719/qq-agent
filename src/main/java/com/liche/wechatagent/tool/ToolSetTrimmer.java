package com.liche.wechatagent.tool;

import com.liche.wechatagent.exam.ExamService;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按用户状态裁剪工具集（2026-09-13）。
 *
 * <p>**为什么**：所有工具 schema 每轮都全量下发给模型（现在 50 个），而其中考试那一组有 18 个、描述都很长。
 * 一个还没建过备考计划的人，每轮都在为这 18 段 schema 付 prompt token，还要冒"选错工具"的风险。
 *
 * <p>**怎么裁（保守做法）**：只有当**三个条件同时成立**才把考试组收起来：
 * <ol>
 *   <li>该用户**没有备考计划**（有计划的用户照常全量下发）；</li>
 *   <li>这条消息里**没有任何考试相关线索词**；</li>
 *   <li>最近几轮对话里也没有（避免刚聊完考研、下一句"好"就把工具收走）。</li>
 * </ol>
 * 而且 **`saveExamPlan` / `viewExamPlan` 永远保留**——否则用户第一次说"帮我建个考研计划"时，
 * 建计划的工具恰好被裁掉，功能直接废了（这是这个方案最大的坑，写在这里提醒后来人）。
 *
 * <p>裁剪结果会打一行 INFO（含裁掉了几个、为什么），出问题可以立刻从日志看出来。
 */
@Component
public class ToolSetTrimmer {

    private static final Logger log = LoggerFactory.getLogger(ToolSetTrimmer.class);

    /** 考试组的工具名（ExamTool 里除"建计划/看计划"以外的全部） */
    private static final List<String> EXAM_TOOLS = List.of(
            "listExamTasks", "generateExamTasks", "addExamTask", "updateExamTask", "examCheckin",
            "examProgress", "setExamPush", "saveExamProgress", "updateExamProgress", "viewExamProgress",
            "addExamMistake", "reviewExamMistake", "viewExamMistakes",
            "saveExamMilestone", "completeExamMilestone", "viewExamMilestones",
            "startExamStudy", "endExamStudy");

    /** 永远不裁的两个：没有它们，新用户连计划都建不出来 */
    private static final Set<String> ALWAYS_KEEP = Set.of("saveExamPlan", "viewExamPlan");

    /** 命中任一就认为"这条消息/最近在聊考研"，于是不裁 */
    private static final List<String> EXAM_KEYWORDS = List.of(
            "考研", "备考", "初试", "复试", "考试", "复习", "背书", "刷题", "真题", "错题", "打卡",
            "学习", "学过", "学完", "要学", "今天学", "进度", "里程碑", "科目", "专业课",
            "数学", "英语", "政治", "408", "专业课", "单词", "墨墨", "计划", "任务");

    private final ExamService examService;
    private final boolean enabled;
    /** 最近几轮的用户消息也一起看，避免"刚聊完考研、下一句好"就把工具收走 */
    private final int historyTurnsToScan;

    public ToolSetTrimmer(ExamService examService,
                          @Value("${agent.tool-trim.enabled:true}") boolean enabled,
                          @Value("${agent.tool-trim.history-turns:4}") int historyTurnsToScan) {
        this.examService = examService;
        this.enabled = enabled;
        this.historyTurnsToScan = Math.max(0, Math.min(20, historyTurnsToScan));
    }

    public record TrimResult(List<ToolSpecification> specifications, Set<String> hidden, String reason) {
    }

    /**
     * @param all            当前全部工具
     * @param userId         当前用户
     * @param userText       本条消息
     * @param recentUserText 最近几轮用户消息（可空），用于判断"是不是正在聊考研"
     */
    public TrimResult trim(List<ToolSpecification> all, String userId, String userText, List<String> recentUserText) {
        if (!enabled || all == null || all.isEmpty()) {
            return new TrimResult(all, Set.of(), "未启用");
        }
        if (userId == null || userId.isBlank()) {
            return new TrimResult(all, Set.of(), "拿不到用户，保守全给");
        }
        if (containsExamKeyword(userText)) {
            return new TrimResult(all, Set.of(), "本条消息提到考研");
        }
        if (containsExamKeyword(String.join(" ", recentUserText == null ? List.of() : recentUserText))) {
            return new TrimResult(all, Set.of(), "最近几轮在聊考研");
        }
        if (examService != null && examService.plan(userId) != null) {
            return new TrimResult(all, Set.of(), "该用户已有备考计划");
        }
        Set<String> present = new LinkedHashSet<>();
        for (ToolSpecification spec : all) {
            present.add(spec.name());
        }
        Set<String> hidden = new LinkedHashSet<>();
        for (String name : EXAM_TOOLS) {
            if (present.contains(name) && !ALWAYS_KEEP.contains(name)) {
                hidden.add(name);
            }
        }
        if (hidden.isEmpty()) {
            return new TrimResult(all, Set.of(), "没有可裁的考试工具");
        }
        List<ToolSpecification> kept = new ArrayList<>(all.size() - hidden.size());
        for (ToolSpecification spec : all) {
            if (!hidden.contains(spec.name())) {
                kept.add(spec);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("本轮工具集已裁剪 user={} 保留 {} 个 / 裁掉 {} 个（考试组，用户没有备考计划且近期没聊考研）",
                    userId, kept.size(), hidden.size());
        }
        return new TrimResult(kept, hidden, "用户没有备考计划且近期没聊考研");
    }

    private boolean containsExamKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : EXAM_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public int historyTurnsToScan() {
        return historyTurnsToScan;
    }
}
