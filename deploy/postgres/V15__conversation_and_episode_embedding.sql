-- P3 向量列（2026-09-18）：原始对话与情景记忆也能"按语义找回来"
--
-- 背景：P2 给工作记忆加了向量之后，剩下两块还在靠字面匹配：
--   ① `conversation_memory`（原始对话，生产 1100+ 行）——没有活跃记忆匹配时，系统会按**字面词**
--      捞旧对话并把 1500 字硬灌进提示词（实测"今天心情不错"这种无关问题也会被灌满旧课表）；
--   ② `episodic_memory`（情景记忆）——注入资格也只看字面关键词，换个说法就找不回来。
-- 这两块加向量之后：历史兜底改成"向量 top-K 且过门槛"（字数上限也压到 ~600），episodes 同理；
-- 再给模型一只手 `searchConversation`，让它能自己"翻旧账"。
--
-- 同 V11/V14：`embedding`/`embedding_model` **不映射进 JPA 实体**（Hibernate 不认 pgvector 的 vector
-- 类型，映射了 `ddl-auto: validate` 会失败），只由 `PgVectorStore` 的原生 SQL 读写。
--
-- ⚠️ 必须先跑这个迁移再部署新镜像（缺列时向量读写会整片失败 → 对话检索退化成空）。

create extension if not exists vector;

alter table conversation_memory add column if not exists embedding vector(1024);
alter table conversation_memory add column if not exists embedding_model varchar(64);
create index if not exists idx_conversation_embedding on conversation_memory using hnsw (embedding vector_cosine_ops);

alter table episodic_memory add column if not exists embedding vector(1024);
alter table episodic_memory add column if not exists embedding_model varchar(64);
create index if not exists idx_episodic_embedding on episodic_memory using hnsw (embedding vector_cosine_ops);

-- 回滚：alter table conversation_memory drop column if exists embedding, drop column if exists embedding_model;
--       alter table episodic_memory drop column if exists embedding, drop column if exists embedding_model;
--      （记忆行不受影响；代码回退后这两列不会被读）
