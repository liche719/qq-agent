package com.liche.wechatagent.self;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 判断 → 倾向：**纯函数**（没有 Spring、没有数据库），所以可以离线灌数据验证「该提没提 / 不该提却提了」。
 *
 * <p>规则全部来自 spec §5（**是规则，不是提示词**）：
 * 同一 topic 同方向累计 ≥3 次、跨 ≥2 天、跨 ≥2 个情境（同一段对话里反复说只算 1 次）才提升；
 * 反例优先——方向相反的新判断达到**同等证据量**时必须修订，不许嘴硬。
 *
 * <p>权重照 spec §13.1 的最简形态：{@code w = exp(-Δt/τ) × (1 / 该情境已有条数)}，
 * τ 由半衰期换算（{@code τ = halfLife / ln2}）。
 *
 * <p><b>衰减用 FSRS v6 的真公式</b>（幂律，与 ACT-R / Anderson 1983 同源）：
 * {@code R(t,S) = (1 + factor·t/S)^decay}、{@code factor = exp(ln0.9/decay) − 1}，
 * 复查间隔由目标保留率反推 {@code I(r,S) = (r^(1/decay) − 1)/factor · S}。
 * 但 <b>S 的更新是简化规则</b>（成功 ×(1+(11−D)/20)，反例 ×0.5 且 D+1）——
 * FSRS 官方的 S' 公式要 w8~w14 等 21 个**拟合**参数，我们一条数据都没有，
 * 所以照 spec §13「参数留空、数据驱动」：先跑，攒够复习事件再拟合。
 */
public final class StancePromoter {

    /** 一条判断（来自 agent_self_event: kind=JUDGE） */
    public record Judge(long eventId, String direction, LocalDateTime at, String contextKey) {
    }

    public record Params(int minEvidence, int minDays, int minContexts, double threshold,
                         double halfLifeDays, double decay, double reviewTarget) {
    }

    public enum Action {
        /** 首次达到门槛 → 立为倾向 */
        PROMOTE,
        /** 反例达到同等证据量 → 修订（旧的留档） */
        REVISE,
        /** 还没到门槛（或只是继续支撑既有倾向） */
        HOLD
    }

    public record Decision(Action action, String direction, double score, double counterScore,
                           int evidenceCount, int days, int contexts,
                           List<Long> evidenceIds, List<Long> counterIds, String reason) {
    }

    private StancePromoter() {
    }

    // ------------------------------------------------------------ 归类与提升

    /**
     * @param judges          该 topic 的全部判断（服务层已经过滤过 topic）
     * @param activeDirection 该 topic 当前活跃倾向的方向；没有则传 null/空
     * @param activeSince     现有倾向的形成/上次修订时间。**只有比它更新的反例才算数**——
     *                        否则旧反例会在下一轮把刚修订的倾向再翻回去（翻烧饼），
     *                        而"被说服"本来就要求**新**的理由（spec §5/§6）。
     */
    public static Decision evaluate(List<Judge> judges, String activeDirection, LocalDateTime activeSince,
                                    Params params, LocalDateTime now) {
        if (judges == null || judges.isEmpty()) {
            return hold("这个类别还没有判断", null, 0, 0, 0, 0, 0, List.of(), List.of());
        }
        Map<String, List<Judge>> byDirection = new LinkedHashMap<>();
        for (Judge judge : judges) {
            String direction = normalize(judge.direction());
            if (direction.isEmpty()) {
                continue;
            }
            byDirection.computeIfAbsent(direction, key -> new ArrayList<>()).add(judge);
        }
        if (byDirection.isEmpty()) {
            return hold("判断没有方向标签，归不了类", null, 0, 0, 0, 0, 0, List.of(), List.of());
        }

        String active = normalize(activeDirection);
        if (!active.isEmpty()) {
            // 反例优先：挑出"比现有倾向新"的反例里权重最高的那个方向，独立按同一套门槛判定
            String challenger = null;
            double bestCounter = -1;
            for (Map.Entry<String, List<Judge>> entry : byDirection.entrySet()) {
                if (entry.getKey().equals(active)) {
                    continue;
                }
                List<Judge> fresh = new ArrayList<>();
                for (Judge judge : entry.getValue()) {
                    if (activeSince == null || (judge.at() != null && judge.at().isAfter(activeSince))) {
                        fresh.add(judge);
                    }
                }
                if (fresh.isEmpty()) {
                    continue;
                }
                double score = score(fresh, params, now);
                if (score > bestCounter) {
                    bestCounter = score;
                    challenger = entry.getKey();
                }
            }
            if (challenger != null) {
                List<Judge> fresh = byDirection.get(challenger).stream()
                        .filter(judge -> activeSince == null || (judge.at() != null && judge.at().isAfter(activeSince)))
                        .toList();
                Stats challengerStats = stats(new ArrayList<>(fresh), params, now);
                if (!challengerStats.below(params)) {
                    Stats old = stats(byDirection.get(active), params, now);
                    return new Decision(Action.REVISE, challenger, challengerStats.score, old.score,
                            challengerStats.evidenceCount, challengerStats.days, challengerStats.contexts,
                            challengerStats.ids, old.ids,
                            "反例侧 " + challengerStats.evidenceCount + " 条、跨 " + challengerStats.days
                                    + " 天、跨 " + challengerStats.contexts + " 个情境（都是形成之后的新证据）"
                                    + "，达到与提升同一套门槛 → 修订");
                }
            }
            Stats support = stats(byDirection.get(active), params, now);
            List<Judge> counterJudges = new ArrayList<>();
            for (Map.Entry<String, List<Judge>> entry : byDirection.entrySet()) {
                if (!entry.getKey().equals(active)) {
                    counterJudges.addAll(entry.getValue());
                }
            }
            Stats counter = stats(counterJudges, params, now);
            return hold("继续支撑既有倾向：" + support.evidenceCount + " 条、跨 " + support.days + " 天"
                            + (counter.evidenceCount == 0 ? "" : "；反例 " + counter.evidenceCount
                            + " 条（含旧的，只有形成之后的新反例才算数）"),
                    active, support.score, counter.score, support.evidenceCount, support.days, support.contexts,
                    support.ids, counter.ids);
        }

        // 还没有倾向：按权重最高的方向判定能不能立
        String candidate = null;
        double best = -1;
        for (Map.Entry<String, List<Judge>> entry : byDirection.entrySet()) {
            double score = score(entry.getValue(), params, now);
            if (score > best) {
                best = score;
                candidate = entry.getKey();
            }
        }
        Stats stats = stats(byDirection.get(candidate), params, now);
        if (stats.below(params)) {
            return hold(shortfall(stats, params), candidate, stats.score, 0, stats.evidenceCount,
                    stats.days, stats.contexts, stats.ids, List.of());
        }
        return new Decision(Action.PROMOTE, candidate, stats.score, 0, stats.evidenceCount, stats.days,
                stats.contexts, stats.ids, List.of(), "同向 " + stats.evidenceCount + " 条、跨 "
                + stats.days + " 天、跨 " + stats.contexts + " 个情境，达到门槛");
    }

    private static Decision hold(String reason, String direction, double score, double counterScore,
                                 int evidenceCount, int days, int contexts,
                                 List<Long> evidenceIds, List<Long> counterIds) {
        return new Decision(Action.HOLD, direction, score, counterScore, evidenceCount, days, contexts,
                evidenceIds, counterIds, reason);
    }

    private record Stats(double score, int evidenceCount, int days, int contexts, List<Long> ids) {

        boolean below(Params params) {
            return evidenceCount < params.minEvidence() || days < params.minDays()
                    || contexts < params.minContexts() || score < params.threshold();
        }
    }

    private static Stats stats(List<Judge> group, Params params, LocalDateTime now) {
        if (group == null || group.isEmpty()) {
            return new Stats(0, 0, 0, 0, List.of());
        }
        List<Judge> ordered = new ArrayList<>(group);
        ordered.sort(Comparator.comparing(Judge::at));
        Map<String, Integer> perContext = new LinkedHashMap<>();
        Set<LocalDate> days = new LinkedHashSet<>();
        Set<String> contexts = new LinkedHashSet<>();
        List<Long> ids = new ArrayList<>();
        double score = 0;
        for (Judge judge : ordered) {
            String context = judge.contextKey() == null ? "" : judge.contextKey();
            int seen = perContext.merge(context, 1, Integer::sum);
            score += weight(judge.at(), now, params) / seen;
            contexts.add(context);
            if (judge.at() != null) {
                days.add(judge.at().toLocalDate());
            }
            ids.add(judge.eventId());
        }
        return new Stats(score, ordered.size(), days.size(), contexts.size(), ids);
    }

    /** w = exp(-Δt/τ)；τ = 半衰期 / ln2 */
    static double weight(LocalDateTime at, LocalDateTime now, Params params) {
        if (at == null || now == null) {
            return 1;
        }
        double days = Math.max(0, Duration.between(at, now).toMinutes() / 1440.0);
        double tau = Math.max(0.5, params.halfLifeDays() / Math.log(2));
        return Math.exp(-days / tau);
    }

    /** 权重之和（服务层要按方向分组时用得到） */
    public static double score(List<Judge> judges, Params params, LocalDateTime now) {
        return stats(judges, params, now).score();
    }

    private static String shortfall(Stats stats, Params params) {
        StringBuilder text = new StringBuilder("证据还不够：");
        text.append("同向 ").append(stats.evidenceCount).append(" 条（需 ≥").append(params.minEvidence()).append("）");
        text.append("、跨 ").append(stats.days).append(" 天（需 ≥").append(params.minDays()).append("）");
        text.append("、跨 ").append(stats.contexts).append(" 个情境（需 ≥").append(params.minContexts()).append("）");
        text.append("、权重 ").append(round(stats.score)).append("（需 ≥").append(round(params.threshold())).append("）");
        return text.toString();
    }

    // ------------------------------------------------------------ FSRS：复查时机与强度

    /** 遗忘曲线 R(t,S)：t 天没碰、强度 S → 还能记住的概率 */
    public static double retrievability(double days, double stability, double decay) {
        if (stability <= 0) {
            return 0;
        }
        double factor = factor(decay);
        return Math.pow(1 + factor * Math.max(0, days) / stability, decay);
    }

    /** 由目标保留率反推间隔天数 I(r,S) */
    public static double intervalDays(double targetRetention, double stability, double decay) {
        double factor = factor(decay);
        double interval = (Math.pow(targetRetention, 1 / decay) - 1) / factor * stability;
        return Math.max(1, interval);
    }

    /** 复习成功：S 变长（越容易记得住、D 越小，长得越快）；D 微降 */
    public static double stabilityOnSuccess(double stability, double difficulty) {
        double base = Math.max(0.5, stability);
        double grow = 1 + (11 - clampDifficulty(difficulty)) / 20.0;
        return Math.min(3650, base * grow);
    }

    /** 又犯了同类错 / 被反例推翻：S 收缩、D 上升 */
    public static double stabilityOnFailure(double stability) {
        return Math.max(0.5, stability * 0.5);
    }

    public static double difficultyOnFailure(double difficulty) {
        return Math.min(10, clampDifficulty(difficulty) + 1);
    }

    public static LocalDateTime nextReviewAt(LocalDateTime lastReview, double stability, double decay,
                                             double targetRetention) {
        LocalDateTime base = lastReview == null ? LocalDateTime.now() : lastReview;
        long minutes = Math.round(intervalDays(targetRetention, stability, decay) * 1440);
        return base.plusMinutes(Math.max(60, minutes));
    }

    private static double clampDifficulty(double difficulty) {
        return Math.min(10, Math.max(1, difficulty));
    }

    private static double factor(double decay) {
        // 文档里的 decay 是负数（FSRS6 默认 -0.1542）；传正数也认，0 当默认值
        double safe = decay > 0 ? -decay : decay;
        if (safe == 0) {
            safe = -0.1542;
        }
        return Math.exp(Math.log(0.9) / safe) - 1;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
