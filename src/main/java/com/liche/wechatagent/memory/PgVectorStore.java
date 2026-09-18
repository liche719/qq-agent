package com.liche.wechatagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * pgvector 的通用读写（2026-09-18，P3）：给"也想要向量"的记忆表用（`conversation_memory` / `episodic_memory`）。
 *
 * <p>为什么要通用版：{@link MemoryFactVectorStore}（事实层，2026-09-18 上）与
 * {@link WorkMemoryVectorStore}（工作记忆，P2）已经把同一套 SQL 写过两遍，P3 再要两张表就是四遍。
 * **这两条已验证的路径这次不动**（能跑就别碰），新的两张表统一走这里；
 * 以后要合并的话，把那两个类改成委托本类即可（纯机械改动）。
 *
 * <p>表名由调用方传入，用 {@link #TABLES} 白名单兜住——表名要拼进 SQL，不能是外部输入。
 * 距离算子是 pgvector 的 {@code <=>}（余弦距离 0~2），分数取 {@code 1 - 距离}。
 */
@Component
public class PgVectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    /** 允许操作的表（白名单，防止表名被当成注入点） */
    private static final Set<String> TABLES = Set.of("conversation_memory", "episodic_memory");

    private final JdbcTemplate jdbcTemplate;

    public PgVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 向量命中：id + 余弦相似度（1 = 完全同向） */
    public record Hit(Long id, double score) {
    }

    public void saveEmbedding(String table, Long id, float[] vector, String model) {
        if (!allowed(table) || id == null) {
            return;
        }
        try {
            if (vector == null || vector.length == 0) {
                jdbcTemplate.update("update " + table + " set embedding = null, embedding_model = ? where id = ?",
                        model, id);
                return;
            }
            jdbcTemplate.update("update " + table + " set embedding = cast(? as vector), embedding_model = ? where id = ?",
                    toLiteral(vector), model, id);
        } catch (Exception e) {
            // 向量写失败不影响记录本身
            log.warn("写向量失败 table={} id={}: {}", table, id, e.getMessage());
        }
    }

    /** 该用户所有"已经有向量"的行的相似度（id → 0~1），一次 SQL 全拿回来 */
    public Map<Long, Double> scores(String table, String userId, float[] query) {
        if (!allowed(table) || userId == null || userId.isBlank() || query == null || query.length == 0) {
            return Map.of();
        }
        try {
            String literal = toLiteral(query);
            Map<Long, Double> scores = new HashMap<>();
            jdbcTemplate.query("select id, 1 - (embedding <=> cast(? as vector)) as score from " + table
                            + " where user_id = ? and embedding is not null",
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            rs -> scores.put(rs.getLong("id"), rs.getDouble("score")), literal, userId);
            return scores;
        } catch (Exception e) {
            log.warn("按向量打分失败 table={}: {}", table, e.getMessage());
            return Map.of();
        }
    }

    /** 语义 top-K：只返回相似度 ≥ {@code minScore} 的行 */
    public List<Hit> search(String table, String userId, float[] query, int limit, double minScore) {
        if (!allowed(table) || userId == null || userId.isBlank() || query == null || query.length == 0 || limit <= 0) {
            return List.of();
        }
        try {
            String literal = toLiteral(query);
            return jdbcTemplate.query("select id, 1 - (embedding <=> cast(? as vector)) as score from " + table
                            + " where user_id = ? and embedding is not null"
                            + " order by embedding <=> cast(? as vector) limit ?",
                    (rs, rowNum) -> new Hit(rs.getLong("id"), rs.getDouble("score")),
                    literal, userId, literal, limit).stream()
                    .filter(hit -> hit.score() >= minScore)
                    .toList();
        } catch (Exception e) {
            log.warn("向量检索失败 table={}: {}", table, e.getMessage());
            return List.of();
        }
    }

    public long countMissing(String table, String userId) {
        if (!allowed(table) || userId == null) {
            return 0L;
        }
        try {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from " + table + " where user_id = ? and embedding is null", Long.class, userId);
            return count == null ? 0L : count;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 已经有向量的行 id（补齐时批量判"还差哪些"） */
    public Set<Long> idsWithEmbedding(String table, String userId) {
        if (!allowed(table) || userId == null) {
            return Set.of();
        }
        try {
            return new HashSet<>(jdbcTemplate.queryForList(
                    "select id from " + table + " where user_id = ? and embedding is not null", Long.class, userId));
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** 还有缺向量的行、需要补齐的用户（启动补齐用） */
    public List<String> userIdsWithMissing(String table) {
        if (!allowed(table)) {
            return List.of();
        }
        try {
            return jdbcTemplate.queryForList(
                    "select distinct user_id from " + table + " where embedding is null", String.class);
        } catch (Exception e) {
            log.warn("查缺向量的用户失败 table={}: {}", table, e.getMessage());
            return List.of();
        }
    }

    private boolean allowed(String table) {
        boolean ok = table != null && TABLES.contains(table);
        if (!ok) {
            log.warn("拒绝在非白名单表上做向量操作: {}", table);
        }
        return ok;
    }

    /** pgvector 的字面量格式：{@code [0.1,-0.2,...]} */
    private String toLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }
}
