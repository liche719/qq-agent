package com.liche.wechatagent.interview;

import com.liche.wechatagent.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 面试陪练：一次练习（session）的开场、逐轮记分、结束复盘。
 *
 * <p>评分卡与复盘报告都基于 {@link InterviewRound} 的真实记录生成，
 * 所以"练了什么、哪项最弱、下次练什么"不依赖模型临场记忆。
 */
@Service
public class InterviewService {

    private static final int MIN_SCORE = 1;
    private static final int MAX_SCORE = 5;

    private final InterviewRoundRepository repository;
    private final UserService userService;

    public InterviewService(InterviewRoundRepository repository, UserService userService) {
        this.repository = repository;
        this.userService = userService;
    }

    /** 开始一次练习：写入陪练模式、生成 session、记录岗位；返回给模型转述的开场白。 */
    @Transactional
    public String start(String userId, String role) {
        String normalizedRole = role == null ? "" : role.strip();
        userService.startInterviewSession(userId, normalizedRole);
        StringBuilder sb = new StringBuilder("已进入「面试陪练」模式，评分卡已开始记录。");
        if (!normalizedRole.isEmpty()) {
            sb.append("本次岗位：").append(normalizedRole).append("。");
        }
        sb.append("\n题库：" ).append(String.join(" / ", InterviewBank.categories()))
                .append("。规则：一次只问一个问题，等对方答完再反馈；每轮给出四个维度 1~5 分并调用 recordInterviewRound 记进评分卡；"
                        + "6~10 轮为宜。现在用一句话确认已开始，然后问第一题（建议从「自我介绍」或根据岗位最相关的一类开始）。");
        return sb.toString();
    }

    /** 记录一轮：返回评分卡进度与"下一步建议"，供模型继续对话。 */
    @Transactional
    public String record(String userId, String category, String question, String answerSummary,
                         Integer scoreContent, Integer scoreStructure, Integer scoreDepth, Integer scoreDelivery,
                         String feedback) {
        String sessionId = userService.coachSessionId(userId);
        if (sessionId == null || sessionId.isBlank()) {
            // 没走 start 就直接记分：补一个 session，避免数据丢掉
            userService.startInterviewSession(userId, "");
            sessionId = userService.coachSessionId(userId);
        }
        String normalizedCategory = InterviewBank.normalizeCategory(category);
        int seq = (int) repository.countByUserIdAndSessionId(userId, sessionId) + 1;

        InterviewRound round = new InterviewRound();
        round.setUserId(userId);
        round.setSessionId(sessionId);
        round.setRole(userService.coachRole(userId));
        round.setSeq(seq);
        round.setCategory(normalizedCategory == null ? "未分类" : normalizedCategory);
        round.setQuestion(trim(question, 600));
        round.setAnswerSummary(trim(answerSummary, 1200));
        round.setScoreContent(clamp(scoreContent));
        round.setScoreStructure(clamp(scoreStructure));
        round.setScoreDepth(clamp(scoreDepth));
        round.setScoreDelivery(clamp(scoreDelivery));
        round.setFeedback(trim(feedback, 800));
        round.setCreatedAt(LocalDateTime.now());
        repository.save(round);

        List<InterviewRound> all = repository.findByUserIdAndSessionIdOrderBySeqAsc(userId, sessionId);
        StringBuilder sb = new StringBuilder();
        sb.append("第 ").append(seq).append(" 轮已记入评分卡（题类：").append(round.getCategory()).append("）。\n");
        sb.append("当前均分（共 ").append(all.size()).append(" 轮）：")
                .append(formatAverages(all)).append("\n");
        if (!all.isEmpty() && all.size() > 1) {
            sb.append("已问题目（不要重复）：");
            List<String> asked = new ArrayList<>();
            for (InterviewRound r : all) {
                if (r.getQuestion() != null && !r.getQuestion().isBlank()) {
                    asked.add(shorten(r.getQuestion()));
                }
            }
            sb.append(String.join(" / ", asked)).append("\n");
        }
        Set<String> covered = new LinkedHashSet<>();
        for (InterviewRound r : all) {
            if (r.getCategory() != null) {
                covered.add(r.getCategory());
            }
        }
        List<String> missing = new ArrayList<>(InterviewBank.categories());
        missing.removeAll(covered);
        sb.append("还没覆盖的题类：").append(missing.isEmpty() ? "（全练过了）" : String.join("、", missing)).append("\n");
        sb.append("下一步：先用一两句给用户本轮反馈（含分数），再问下一题——优先从没覆盖的题类里选，或者顺着对方刚说的内容继续追问。");
        return sb.toString();
    }

    /** 结束练习并生成复盘报告；没有记录时返回 null（由调用方换成普通结束语）。 */
    @Transactional
    public String finish(String userId) {
        String sessionId = userService.coachSessionId(userId);
        List<InterviewRound> rounds = (sessionId == null || sessionId.isBlank())
                ? List.of()
                : repository.findByUserIdAndSessionIdOrderBySeqAsc(userId, sessionId);
        String role = userService.coachRole(userId);
        userService.endInterviewSession(userId);
        if (rounds.isEmpty()) {
            return null;
        }

        Map<String, Double> averages = dimensionAverages(rounds);
        double overall = averages.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        String weakest = averages.entrySet().stream()
                .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("结构清晰度");

        InterviewRound best = rounds.stream()
                .max((a, b) -> Double.compare(roundScore(a), roundScore(b))).orElse(null);
        InterviewRound worst = rounds.stream()
                .filter(r -> rounds.size() > 1)
                .min((a, b) -> Double.compare(roundScore(a), roundScore(b))).orElse(null);

        Set<String> covered = new LinkedHashSet<>();
        for (InterviewRound r : rounds) {
            if (r.getCategory() != null) {
                covered.add(r.getCategory());
            }
        }
        List<String> missing = new ArrayList<>(InterviewBank.categories());
        missing.removeAll(covered);

        StringBuilder sb = new StringBuilder();
        sb.append("📋 面试陪练复盘");
        if (role != null && !role.isBlank()) {
            sb.append("（").append(role).append("）");
        }
        sb.append("\n练了 ").append(rounds.size()).append(" 轮，平均 ")
                .append(formatScore(overall)).append(" / 5\n\n");
        sb.append("各维度：\n");
        averages.forEach((dimension, value) -> sb.append("• ").append(dimension).append(" ")
                .append(formatScore(value))
                .append(dimension.equals(weakest) ? "  ← 最需要练" : "")
                .append("\n"));
        sb.append("\n");
        if (best != null) {
            sb.append("最好一轮：第 ").append(best.getSeq()).append(" 轮「").append(shorten(best.getQuestion()))
                    .append("」").append(formatScore(roundScore(best))).append("\n");
        }
        if (worst != null && worst != best) {
            sb.append("最弱一轮：第 ").append(worst.getSeq()).append(" 轮「").append(shorten(worst.getQuestion()))
                    .append("」").append(formatScore(roundScore(worst))).append("\n");
            if (worst.getFeedback() != null && !worst.getFeedback().isBlank()) {
                sb.append("  当时的问题：").append(shorten(worst.getFeedback())).append("\n");
            }
        }
        sb.append("\n下次重点：\n")
                .append("1. ").append(weakest).append(" —— ").append(adviceFor(weakest)).append("\n");
        if (!missing.isEmpty()) {
            sb.append("2. 还没练到的题类：").append(String.join("、", missing))
                    .append("，下次优先补上。\n");
        } else {
            sb.append("2. 题类都覆盖过了，下次可以换岗位/换难度再练一遍。\n");
        }
        sb.append("\n想接着练就说「陪练 面试」，随时可以。评分卡和这份复盘都留在记录里。");
        return sb.toString();
    }

    /** 为面板/接口提供的最近练习记录（按轮次倒序）。 */
    public List<InterviewRound> recentRounds(String userId, int limit) {
        List<InterviewRound> all = repository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    private String adviceFor(String dimension) {
        return switch (dimension) {
            case "内容完整度" -> "每题至少给一个具体数字或例子（做了什么、提升了多少），别停在「参与了」。";
            case "结构清晰度" -> "用 STAR 说：情境→任务→行动→结果；先给一句话结论，再展开三点。";
            case "技术深度" -> "多讲取舍与边界：为什么选 A 不选 B、量级多大、瓶颈在哪。";
            case "表达流畅度" -> "控制在 90 秒内，先说结论，别从背景讲起；卡壳就用「简单说，就是…」收一下。";
            default -> "按面试官最在意的点组织答案：结论先行、有数据、有取舍。";
        };
    }

    private Map<String, Double> dimensionAverages(List<InterviewRound> rounds) {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("内容完整度", average(rounds, InterviewRound::getScoreContent));
        result.put("结构清晰度", average(rounds, InterviewRound::getScoreStructure));
        result.put("技术深度", average(rounds, InterviewRound::getScoreDepth));
        result.put("表达流畅度", average(rounds, InterviewRound::getScoreDelivery));
        return result;
    }

    private String formatAverages(List<InterviewRound> rounds) {
        Map<String, Double> averages = dimensionAverages(rounds);
        StringBuilder sb = new StringBuilder();
        averages.forEach((dimension, value) -> {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(dimension.replace("完整度", "").replace("清晰度", "").replace("流畅度", ""))
                    .append(" ").append(formatScore(value));
        });
        return sb.toString();
    }

    private double average(List<InterviewRound> rounds, java.util.function.Function<InterviewRound, Integer> getter) {
        double sum = 0;
        int count = 0;
        for (InterviewRound round : rounds) {
            Integer value = getter.apply(round);
            if (value != null) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private double roundScore(InterviewRound round) {
        double sum = 0;
        int count = 0;
        for (Integer value : new Integer[]{round.getScoreContent(), round.getScoreStructure(),
                round.getScoreDepth(), round.getScoreDelivery()}) {
            if (value != null) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    private String formatScore(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private Integer clamp(Integer score) {
        if (score == null) {
            return null;
        }
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    private String trim(String text, int max) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').strip();
        return value.length() <= 24 ? value : value.substring(0, 24) + "…";
    }
}
