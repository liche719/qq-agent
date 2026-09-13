package com.liche.wechatagent.memory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 记忆文本的粗粒度相似度（字符二元组 Jaccard + 互相包含）。
 *
 * <p>**为什么要它**：模型偶尔会把"和已有事实冲突/重复"的内容当成**新事实**输出（实测：「目标分改成 140」被记成
 * 一条新的核心记忆，旧的 130 还挂在库里 → 用户感觉"它记着旧的、不认新的"）。光靠提示词纠不住，
 * 所以在写库前用一层**确定性的兜底**：跟已有记忆足够像的新事实，走
 * {@link CoreMemoryService#replaceFromExtraction} 替换旧条目（旧条目 status=SUPERSEDED，仍可审计），而不是新增一条平行的。
 *
 * <p>为什么不用向量：项目里没有 embedding 接口，而这类冲突（改分数、改日期、改院校、改称呼）**字面高度重合**，
 * 二元组 Jaccard 已经够用；真需要语义检索时再上向量（见 docs/memory-extraction.md 的"下一步"）。
 */
final class MemoryTextSimilarity {

    private MemoryTextSimilarity() {
    }

    /** 只保留中日韩文字、字母和数字，其它（标点、空白、emoji）一律丢掉 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** 字符二元组集合；文本太短（<2）时退化成单字符集合 */
    static Set<String> bigrams(String text) {
        String normalized = normalize(text);
        Set<String> grams = new HashSet<>();
        if (normalized.length() < 2) {
            if (!normalized.isEmpty()) {
                grams.add(normalized);
            }
            return grams;
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * 相似度 0~1：二元组 Jaccard。互相包含（一方是另一方的子串）时直接给 {@code 0.95}——
     * 「考研数学目标分是130」/「考研数学目标分是130分」这种就属于这类。
     */
    static double similarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        if (a.equals(b)) {
            return 1d;
        }
        if (a.length() >= 4 && b.length() >= 4 && (a.contains(b) || b.contains(a))) {
            return 0.95d;
        }
        Set<String> ga = bigrams(left);
        Set<String> gb = bigrams(right);
        if (ga.isEmpty() || gb.isEmpty()) {
            return 0d;
        }
        int intersection = 0;
        for (String gram : ga) {
            if (gb.contains(gram)) {
                intersection++;
            }
        }
        int union = ga.size() + gb.size() - intersection;
        return union == 0 ? 0d : (double) intersection / (double) union;
    }
}
