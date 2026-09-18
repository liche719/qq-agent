-- work 层向量（2026-09-18，记忆 v2 · P2：工作记忆按"跟当前话题像不像"注入）
--
-- 背景（实测）：恢复成活跃之后，主力用户有 64 条工作记忆抢 15 个注入名额，而选择逻辑是
-- "按字面关键词分排完**一律填到上限**"——实测每轮注入 808~1502 字，其中 600~1300 字是纯陪跑；
-- 同时"换了说法"的记忆（字面不重合）永远选不出来。
--
-- 所以给 work 层加向量：注入只看**窗口向量**的余弦相似度，过不了门槛就不注入（宁可留空，不再填坑）。
--
-- 注意：`embedding` / `embedding_model` **不映射进 JPA 实体**——Hibernate 不认识 pgvector 的 vector
-- 类型，映射了会让 `ddl-auto: validate` 失败（同 `memory_fact` 的 V11）。它们只由 `WorkMemoryVectorStore`
-- 的原生 SQL 读写。
--
-- ⚠️ 必须在部署新镜像**之前**执行：实体不映射这两列，但缺列时向量读写会整片失败（工作记忆退化成不注入）。

create extension if not exists vector;

alter table user_work_memory add column if not exists embedding vector(1024);
alter table user_work_memory add column if not exists embedding_model varchar(64);

-- 余弦距离的 HNSW 索引（数据量小时也会走顺序扫描，不影响正确性）
create index if not exists idx_work_embedding on user_work_memory using hnsw (embedding vector_cosine_ops);

-- 回滚：alter table user_work_memory drop column if exists embedding, drop column if exists embedding_model;
--      （记忆行本身不受影响，只是回到"按字面关键词选"的老行为——把 memory.work-vector-floor 设成 0 也能达到同样效果）
