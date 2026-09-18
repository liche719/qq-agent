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
 * 工作记忆向量列的读写（2026-09-18，记忆 v2 · P2）。
 *
 * <p>和 {@link MemoryFactVectorStore} 一样，这里用原生 SQL 的唯一原因是 pgvector 的 {@code vector}
 * 类型 Hibernate 不认（映射进实体会让 {@code ddl-auto: validate} 失败，JDBC 也没有对应 Java 类型）。
 * 只有"写向量"和"按余弦距离打分"两件事走 SQL，其余一切照旧走 JPA。
 *
 * <p>距离算子是 pgvector 的 {@code <=>}（余弦距离 0~2），分数取 {@code 1 - 距离}（余弦相似度，1 = 完全同向）。
 */
@Component
public class WorkMemoryVectorStore {

    private static final Logger log = LoggerFactory.getLogger(WorkMemoryVectorStore.class);

    private final JdbcTemplate jdbcTemplate;

    public WorkMemoryVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 写入（或清空）一条工作记忆的向量。{@code vector} 为 null 表示置空。 */
    public void saveEmbedding(Long workId, float[] vector, String model) {
        if (workId == null) {
            return;
        }
        try {
            if (vector == null || vector.length == 0) {
                jdbcTemplate.update("update user_work_memory set embedding = null, embedding_model = ? where id = ?",
                        model, workId);
                return;
            }
            jdbcTemplate.update(
                    "update user_work_memory set embedding = cast(? as vector), embedding_model = ? where id = ?",
                    toLiteral(vector), model, workId);
        } catch (Exception e) {
            // 向量写失败不影响记忆本身（内容已经落库）
            log.warn("写入工作记忆向量失败 id={}: {}", workId, e.getMessage());
        }
    }

    /**
     * 该用户所有**已经有向量**的工作记忆的余弦相似度（id → 0~1）。
     *
     * <p>一次 SQL 把全部候选的分数拿回来，避免逐条算。没有向量的行不会出现在结果里——
     * 按当前设计这些行**不参与注入**（写路径与启动补齐会保证它们迟早有向量）。
     */
    public Map<Long, Double> scores(String userId, float[] query) {
        if (userId == null || userId.isBlank() || query == null || query.length == 0) {
            return Map.of();
        }
        try {
            String literal = toLiteral(query);
            Map<Long, Double> scores = new HashMap<>();
            jdbcTemplate.query("""
                            select id, 1 - (embedding <=> cast(? as vector)) as score
                            from user_work_memory
                            where user_id = ? and embedding is not null
                            """,
                    rs -> {
                        scores.put(rs.getLong("id"), rs.getDouble("score"));
                    }, literal, userId);
            return scores;
        } catch (Exception e) {
            log.warn("工作记忆向量打分失败（本次无候选）: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 没有向量的工作记忆条数（面板与自愈判据） */
    public long countMissingEmbedding(String userId) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from user_work_memory where user_id = ? and embedding is null",
                    Long.class, userId);
            return count == null ? 0L : count;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 已经从库里读出、但还没有向量的行 id（补齐时批量判"还差哪些"，避免逐条查）。
     *
     * <p>**不按状态过滤**：被取代/已过期的行也保留向量（面板要看得到"它确实有"），补齐只遍历活跃行。
     */
    public Set<Long> idsWithEmbedding(String userId) {
        try {
            return new HashSet<>(jdbcTemplate.queryForList(
                    "select id from user_work_memory where user_id = ? and embedding is not null",
                    Long.class, userId));
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** 一次取回全部有向量的行 id（启动补齐时按用户遍历用） */
    public List<String> distinctUserIdsWithMissingEmbedding() {
        try {
            return jdbcTemplate.queryForList(
                    "select distinct user_id from user_work_memory where embedding is null", String.class);
        } catch (Exception e) {
            log.warn("查询缺向量的用户失败: {}", e.getMessage());
            return List.of();
        }
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
