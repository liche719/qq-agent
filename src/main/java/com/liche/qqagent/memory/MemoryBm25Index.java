package com.liche.qqagent.memory;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆的字面检索（BM25，2026-11-04）。
 *
 * <p><b>为什么要它</b>：纯向量检索在"专有名词、编号、日期"上会漏——它不懂「6e206」是一间教室、
 * 也不觉得「陈立中」比「我」更值得看。BM25 用 TF×IDF×长度归一化补这一路。
 *
 * <p><b>为什么放在应用内存</b>：检索永远带 userId，每个用户只有几百到几千条记忆，
 * 全量建索引是毫秒级。等单个用户的记忆超过 5 万条（基本不可能）再换成 pg 的 zhparser。
 *
 * <p><b>为什么不直接上 RRF 融合</b>：后面还有 rerank 精排。召回阶段只要**把两路的并集**给出去，
 * 排序交给交叉编码器更准；RRF 是"没有精排时"的替代品，这里不需要叠两层排序。
 */
@Component
public class MemoryBm25Index {

    private static final Logger log = LoggerFactory.getLogger(MemoryBm25Index.class);
    /** 索引缓存有效期：记忆写入后最迟这么久生效（简单、够用） */
    private static final long TTL_MILLIS = 5 * 60 * 1000L;

    private final MemoryRepository repository;
    private final JiebaSegmenter segmenter = new JiebaSegmenter();
    private final double k1;
    private final double b;
    private final int maxChars;

    /** userId → 索引快照 */
    private final Map<String, Snapshot> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public MemoryBm25Index(MemoryRepository repository,
                           @Value("${memory.bm25.k1:1.2}") double k1,
                           @Value("${memory.bm25.b:0.75}") double b,
                           @Value("${memory.bm25.max-chars:600}") int maxChars) {
        this.repository = repository;
        this.k1 = k1;
        this.b = b;
        this.maxChars = maxChars;
    }

    private record Snapshot(long builtAt, Map<Long, String> kinds, Map<Long, String> texts,
                            Map<Long, Integer> lengths, Map<String, Integer> docFreq, double avgLength, int total) {
    }

    /**
     * 按字面相关度取该用户最相关的记忆。
     *
     * @param limit 最多返回几条
     */
    public List<Memory> search(String userId, String query, int limit) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        Snapshot snapshot = snapshotOf(userId);
        if (snapshot == null || snapshot.total() == 0) {
            return List.of();
        }
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> scores = new HashMap<>();
        for (String term : terms) {
            Integer df = snapshot.docFreq().get(term);
            if (df == null || df == 0) {
                continue;
            }
            // BM25 的 IDF 项：稀有词权重高，「我」「的」这种近乎为零
            double idf = Math.log(1 + (snapshot.total() - df + 0.5) / (df + 0.5));
            for (Map.Entry<Long, String> doc : snapshot.texts().entrySet()) {
                int tf = count(segmentCache(doc.getValue()), term);
                if (tf == 0) {
                    continue;
                }
                int length = snapshot.lengths().getOrDefault(doc.getKey(), 1);
                double norm = 1 - b + b * length / Math.max(1e-6, snapshot.avgLength());
                scores.merge(doc.getKey(), idf * tf * (k1 + 1) / (tf + k1 * norm), Double::sum);
            }
        }
        if (scores.isEmpty()) {
            return List.of();
        }
        List<Long> topIds = scores.entrySet().stream()
                .sorted((a, b2) -> Double.compare(b2.getValue(), a.getValue()))
                .limit(Math.max(1, limit))
                .map(Map.Entry::getKey)
                .toList();
        List<Memory> result = new ArrayList<>();
        for (Long id : topIds) {
            // 只回 id 和 kind 就够（调用方要的是"候选"，正文它自己有）
            Memory memory = new Memory();
            memory.setId(id);
            memory.setUserId(userId);
            memory.setKind(snapshot.kinds().get(id));
            memory.setContent(snapshot.texts().get(id));
            result.add(memory);
        }
        return result;
    }

    /** 分词并缓存（同一文档在多次打分里会被反复用到） */
    private final Map<Long, List<String>> tokenCache = new java.util.concurrent.ConcurrentHashMap<>();

    private List<String> segmentCache(String text) {
        long key = text == null ? 0 : text.hashCode();
        return tokenCache.computeIfAbsent(key, ignored -> tokenize(text));
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String clipped = text.length() > maxChars ? text.substring(0, maxChars) : text;
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : segmenter.sentenceProcess(clipped)) {
            String trimmed = token.trim();
            // 单字和纯表情没有区分度，丢掉
            if (trimmed.length() >= 2) {
                tokens.add(trimmed.toLowerCase());
            }
        }
        return new ArrayList<>(tokens);
    }

    private static int count(List<String> tokens, String term) {
        int hits = 0;
        for (String token : tokens) {
            if (token.equals(term)) {
                hits++;
            }
        }
        return hits;
    }

    private Snapshot snapshotOf(String userId) {
        Snapshot cached = cache.get(userId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.builtAt() < TTL_MILLIS) {
            return cached;
        }
        try {
            List<Memory> memories = repository.findByUserId(userId);
            if (memories == null || memories.isEmpty()) {
                Snapshot empty = new Snapshot(now, Map.of(), Map.of(), Map.of(), Map.of(), 0, 0);
                cache.put(userId, empty);
                return empty;
            }
            Map<Long, String> kinds = new HashMap<>();
            Map<Long, String> texts = new HashMap<>();
            Map<Long, Integer> lengths = new HashMap<>();
            Map<String, Integer> docFreq = new HashMap<>();
            long totalLength = 0;
            for (Memory memory : memories) {
                if (memory.getId() == null || memory.getContent() == null) {
                    continue;
                }
                List<String> tokens = tokenize(memory.getContent());
                kinds.put(memory.getId(), memory.getKind());
                texts.put(memory.getId(), memory.getContent());
                lengths.put(memory.getId(), Math.max(1, tokens.size()));
                totalLength += Math.max(1, tokens.size());
                for (String token : new LinkedHashSet<>(tokens)) {
                    docFreq.merge(token, 1, Integer::sum);
                }
            }
            Snapshot snapshot = new Snapshot(now, kinds, texts, lengths, docFreq,
                    lengths.isEmpty() ? 1 : (double) totalLength / lengths.size(), lengths.size());
            cache.put(userId, snapshot);
            log.info("记忆索引重建 user={} 条数={} 词数={}", userId, snapshot.total(), docFreq.size());
            return snapshot;
        } catch (RuntimeException exception) {
            log.warn("记忆索引构建失败（本轮退回纯向量）：{}", exception.getMessage());
            return null;
        }
    }

    /** 记忆变了就清缓存（由 MemoryService 写入后调用） */
    public void invalidate(String userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }
}
