package com.liche.qqagent.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 说话意图的判别（2026-11-04）。
 *
 * <p><b>为什么单独一个类</b>：这类判断是"词表 + 规则"，放进 {@code MemoryRetrievalService}
 * 会把它变成一个越来越重的巨类（构造器已经十几个参数）。独立出来后：
 * 词表进配置、改规则只动一个文件、也方便单独写用例。
 *
 * <p><b>为什么不是"调模型判断"</b>：每轮多一次调用，延迟和成本都不划算；
 * 而这里要判的东西（寒暄 / 指代）恰恰是规则能覆盖的**窄问题**。
 * 模型判断留给真正需要语义的地方（比如改写）。
 */
@Component
public class MemorySignal {

    /** 寒暄/附和词：命中就说明"这句不需要翻旧账" */
    private final List<String> smallTalkWords;

    /** 指代词：命中说明"他在指代过去的事" */
    private final List<String> referentialWords;

    /** 疑问词：命中说明"他在问事"，不能当寒暄 */
    private final List<String> questionWords;

    public MemorySignal(
            @Value("${memory.signal.small-talk-words:哈,嘿,嗯,哦,噢,好,行,对,谢谢,谢,牛,棒,厉害,可以,收到,懂了,知道了,真的,确实}")
            String smallTalk,
            @Value("${memory.signal.referential-words:上次,上回,刚才,刚刚,之前,那次,那件事,后来,前面说,跟你说过}")
            String referential,
            @Value("${memory.signal.question-words:吗,呢,啥,什么,哪,谁,多少,咋,怎么,为什么,是不是,能不能,来着}")
            String question) {
        this.smallTalkWords = split(smallTalk);
        this.referentialWords = split(referential);
        this.questionWords = split(question);
    }

    /**
     * 是不是寒暄/附和。
     *
     * <p>判据刻意保守：**短 + 寒暄词 + 无疑问 + 无指代 + 无数字英文**。
     * 只要有一点像在问事，就返回 false（走完整检索）——宁可多花几百毫秒，
     * 也不能让它对着重要上下文装失忆。
     */
    public boolean isSmallTalk(String text) {
        if (text == null) {
            return false;
        }
        String query = text.trim();
        if (query.isEmpty() || query.length() > 8) {
            return false;
        }
        if (query.matches(".*[0-9A-Za-z].*")) {
            return false;
        }
        if (containsAny(query, questionWords) || containsAny(query, referentialWords)) {
            return false;
        }
        return containsAny(query, smallTalkWords);
    }

    /** 是不是在指代过去（"上次那事""之前说的"） */
    public boolean isReferential(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String query = text.trim();
        if (containsAny(query, referentialWords)) {
            return true;
        }
        // 短句 + 疑问词，多半也是"你懂的"那种问法
        return query.length() <= 8 && containsAny(query, questionWords);
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (!word.isEmpty() && text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(word -> !word.isEmpty())
                .toList();
    }
}
