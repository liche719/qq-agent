package com.liche.wechatagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

/**
 * 事实向量列的读写（2026-09-18）。
 *
 * <p><b>这里是全项目唯一写原生 SQL 的地方</b>，原因是 pgvector 的 {@code vector} 类型 Hibernate 不认：
 * 映射进 {@link MemoryFact} 会让 {@code ddl-auto: validate} 失败，而且 JDBC 也没有对应的 Java 类型。
 * 所以只有"写向量"和"按余弦距离取 top-k"这两件事走 SQL，其余一切照旧走 JPA。
 *
 * <p>距离算子是 pgvector 的 {@code <=>}（余弦距离，0~2），召回分数取 {@code 1 - 距离}（余弦相似度）。
 */
@Component
public class MemoryFactVectorStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryFactVectorStore.class);

    private final JdbcTemplate jdbcTemplate;

    public MemoryFactVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 向量命中：事实 id + 余弦相似度（1 = 完全同向） */
    public record FactHit(Long id, double score) {
    }

    /** 写入（或清空）一条事实的向量。{@code vector} 为 null 表示把向量置空（模型换维度时用得上） */
    public void saveEmbedding(Long factId, float[] vector, String model) {
        if (factId == null) {
            return;
        }
        try {
            if (vector == null || vector.length == 0) {
                jdbcTemplate.update("update memory_fact set embedding = null, embedding_model = ? where id = ?",
                        model, factId);
                return;
            }
            jdbcTemplate.update("update memory_fact set embedding = cast(? as vector), embedding_model = ? where id = ?",
                    toLiteral(vector), model, factId);
        } catch (Exception e) {
            // 向量写失败不影响事实本身（事实已经落库），下次重建即可
            log.warn("写入事实向量失败 id={}: {}", factId, e.getMessage());
        }
    }

    /**
     * 按语义取回该用户最相关的前 {@code limit} 条有效事实。
     *
     * @param minScore 余弦相似度下限，低于它的一律当"无关"（宁可新增也不要错并）
     */
    public List<FactHit> search(String userId, float[] query, int limit, double minScore) {
        if (userId == null || query == null || query.length == 0 || limit <= 0) {
            return List.of();
        }
        try {
            String literal = toLiteral(query);
            return jdbcTemplate.query("""
                            select id, 1 - (embedding <=> cast(? as vector)) as score
                            from memory_fact
                            where user_id = ? and status = 'ACTIVE' and embedding is not null
                            order by embedding <=> cast(? as vector)
                            limit ?
                            """,
                    (rs, rowNum) -> new FactHit(rs.getLong("id"), rs.getDouble("score")),
                    literal, userId, literal, limit).stream()
                    .filter(hit -> hit.score() >= minScore)
                    .toList();
        } catch (Exception e) {
            log.warn("向量召回失败（本次按无召回处理）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 没有向量的有效事实条数（判断是否需要补齐/重建） */
    public long countMissingEmbedding(String userId) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from memory_fact where user_id = ? and status = 'ACTIVE' and embedding is null",
                    Long.class, userId);
            return count == null ? 0L : count;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 已经有向量的有效事实 id（批量判"还差哪些"用，避免逐条查）。
     *  **不按状态过滤**：被取代的行也保留着向量（面板要看得到"它确实有"），补齐逻辑只遍历 ACTIVE 的行。 */
    public java.util.Set<Long> idsWithEmbedding(String userId) {
        try {
            return new java.util.HashSet<>(jdbcTemplate.queryForList(
                    "select id from memory_fact where user_id = ? and embedding is not null",
                    Long.class, userId));
        } catch (Exception e) {
            return java.util.Set.of();
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
