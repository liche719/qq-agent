package com.liche.qqagent.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 混合检索排序：向量顺序 + 词法匹配，用 RRF 融合（2026-10-28，**默认关闭**）。
 *
 * <p><b>为什么默认关</b>：先把评测做难（六门课六间教室、精确 token 反查、排序断言），
 * 纯向量 top-1 仍然全对（27/27）。没有掉分就没有可验证的收益，所以默认不开——
 * 留着它是因为"数据量上来后可能需要"，而不是因为"架构上更先进"。
 *
 * <p><b>为什么要 RRF 而不是分数相加</b>：向量相似度在 0~1，词法得分是"命中几个 token"（0~5），
 * 量级不可比；加权重调参又不可解释。RRF 只看名次（{@code 1/(k+rank)}），天然抹平两路分数的尺度差异。
 */
final class HybridRecallRanker {

    /** RRF 的平滑常数，业界常用 60 */
    static final int RRF_K = 60;

    private HybridRecallRanker() {
    }

    /**
     * 融合两路排序。
     *
     * @param facts 向量召回的顺序（越靠前越相似）
     * @param query 用户原话
     * @return 融合后的顺序；词法一路完全没命中时原样返回（不制造伪信号）
     */
    static List<MemoryFact> fuse(List<MemoryFact> facts, String query) {
        if (facts == null || facts.size() <= 1 || query == null || query.isBlank()) {
            return facts == null ? List.of() : facts;
        }
        Set<String> queryTokens = tokens(query);
        if (queryTokens.isEmpty()) {
            return facts;
        }
        // 每个候选的 token 集合先算出来，供 IDF 与命中计数复用
        List<Set<String>> factTokens = new ArrayList<>(facts.size());
        for (MemoryFact fact : facts) {
            factTokens.add(tokens(text(fact)));
        }
        // 本地 IDF（2026-10-29）：在**候选集**里统计每个 query token 出现在几条事实里。
        // 越稀有越值钱——"8b203" 只出现在 1 条里（权重高），"课""的"出现在大半候选里（权重接近 0）。
        // 这是真正的 BM25 缺的那一半：只做词频不做 IDF，高频虚词会把精确命中淹没。
        int total = facts.size();
        java.util.Map<String, Double> idf = new java.util.HashMap<>();
        for (String token : queryTokens) {
            int df = 0;
            for (Set<String> tokens : factTokens) {
                if (tokens.contains(token)) {
                    df++;
                }
            }
            // 加 1 平滑，保证出现在所有候选里的 token 权重也 > 0（不直接抹掉）
            idf.put(token, Math.log(1.0 + (double) total / (1 + df)));
        }
        // 词法得分：命中的 token 按 IDF 加权求和
        double[] lexical = new double[facts.size()];
        boolean anyHit = false;
        for (int index = 0; index < facts.size(); index++) {
            double score = 0;
            for (String token : queryTokens) {
                if (factTokens.get(index).contains(token)) {
                    score += idf.getOrDefault(token, 0d);
                }
            }
            lexical[index] = score;
            anyHit |= score > 0;
        }
        if (!anyHit) {
            return facts;
        }
        // 只在**词法有命中**的那几条里排名（RRF 的语义是"某一路召回的候选"，
        // 把 0 命中的也排进去会稀释信号——实测会让真正的精确命中顶不上来）
        List<Integer> lexicalHits = new ArrayList<>();
        for (int index = 0; index < facts.size(); index++) {
            if (lexical[index] > 0) {
                lexicalHits.add(index);
            }
        }
        lexicalHits.sort((left, right) -> {
            int byScore = Double.compare(lexical[right], lexical[left]);
            return byScore != 0 ? byScore : Integer.compare(left, right);
        });
        int[] lexicalRank = new int[facts.size()];
        java.util.Arrays.fill(lexicalRank, -1);
        for (int rank = 0; rank < lexicalHits.size(); rank++) {
            lexicalRank[lexicalHits.get(rank)] = rank;
        }
        // RRF 融合：向量一路全员参与，词法一路只有命中者参与
        double[] fused = new double[facts.size()];
        for (int index = 0; index < facts.size(); index++) {
            fused[index] = 1.0 / (RRF_K + index + 1);
            if (lexicalRank[index] >= 0) {
                fused[index] += 1.0 / (RRF_K + lexicalRank[index] + 1);
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < facts.size(); index++) {
            order.add(index);
        }
        order.sort((left, right) -> {
            int byFused = Double.compare(fused[right], fused[left]);
            return byFused != 0 ? byFused : Integer.compare(left, right);
        });
        List<MemoryFact> result = new ArrayList<>(facts.size());
        for (int index : order) {
            result.add(facts.get(index));
        }
        return result;
    }

    private static String text(MemoryFact fact) {
        if (fact == null) {
            return "";
        }
        String subject = fact.getSubject() == null ? "" : fact.getSubject();
        String object = fact.getObject() == null ? "" : fact.getObject();
        return subject + " " + object;
    }

    /**
     * 极简分词：拉丁/数字按整段（`8b203` 这种房间号、型号最需要精确匹配），中文取**字符二元组**。
     *
     * <p>不用词典分词：一是没维护成本，二是这里只需要"能对上号"，不需要语义切分。
     */
    static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        java.util.regex.Matcher latin = java.util.regex.Pattern
                .compile("[a-z0-9]{2,}").matcher(lower);
        while (latin.find()) {
            result.add(latin.group());
        }
        StringBuilder cjk = new StringBuilder();
        for (char ch : lower.toCharArray()) {
            if (ch >= 0x4e00 && ch <= 0x9fff) {
                cjk.append(ch);
            } else if (cjk.length() > 0) {
                addBigrams(result, cjk.toString());
                cjk.setLength(0);
            }
        }
        if (cjk.length() > 0) {
            addBigrams(result, cjk.toString());
        }
        return result;
    }

    private static void addBigrams(Set<String> into, String run) {
        if (run.length() == 1) {
            into.add(run);
            return;
        }
        for (int index = 0; index + 1 < run.length(); index++) {
            into.add(run.substring(index, index + 2));
        }
    }
}
