-- 「笨鱼自己在 B 站刷视频、发评论」的台账（2026-10-20）
--
-- 用户的原话是"相当于笨鱼自己在刷视频发评论而已（这些内容也会同步记录在某个地方）"，
-- 所以这张表既是记录（面板/排障能翻），也是**护栏的判定依据**：
--   每天发了多少条 → COUNT；同一视频评过没有 → 查 bvid；两条之间隔了多久 → MAX(created_at)。
-- 护栏故意用表来算，不靠模型自觉（它偶尔会"忘了刚才发过"）。
CREATE TABLE IF NOT EXISTS bili_comment_log (
    id             BIGSERIAL    PRIMARY KEY,
    bvid           VARCHAR(32)  NOT NULL DEFAULT '',
    aid            BIGINT       NOT NULL DEFAULT 0,
    video_title    VARCHAR(256) NOT NULL DEFAULT '',
    message        TEXT         NOT NULL DEFAULT '',
    root_rpid      BIGINT       NOT NULL DEFAULT 0,
    parent_rpid    BIGINT       NOT NULL DEFAULT 0,
    result_code    INTEGER      NOT NULL DEFAULT -1,
    result_message VARCHAR(256) NOT NULL DEFAULT '',
    rpid           BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bili_comment_bvid ON bili_comment_log (bvid, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bili_comment_created ON bili_comment_log (created_at DESC);

COMMENT ON TABLE bili_comment_log IS 'B站评论台账：既记录每一条，也用于每日上限/同视频一次/冷却间隔的护栏判定';
