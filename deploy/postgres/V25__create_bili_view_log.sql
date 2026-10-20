-- 「它看过哪些视频」的台账（2026-10-20）
--
-- 用户的诉求：**不干扰它，但必须知道它做了什么**。原来只有它自己写的一句总结
-- （`agent_self_note` 里 kind=ACTIVITY 那条），看不到"实际翻了哪几个视频、读到了什么"。
-- 这条台账由工具在**读视频时**落，不靠它自述——所以即使它总结得含糊，这里也是准的。
--
-- `outcome` 记结果类别：字幕 / 抽帧 / 读不到；`detail` 记字数或帧数。
CREATE TABLE IF NOT EXISTS bili_view_log (
    id         BIGSERIAL    PRIMARY KEY,
    bvid       VARCHAR(32)  NOT NULL DEFAULT '',
    title      VARCHAR(256) NOT NULL DEFAULT '',
    outcome    VARCHAR(32)  NOT NULL DEFAULT '',
    detail     VARCHAR(64)  NOT NULL DEFAULT '',
    created_at TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bili_view_created ON bili_view_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bili_view_bvid ON bili_view_log (bvid, created_at DESC);

COMMENT ON TABLE bili_view_log IS '它读过的 B 站视频台账（工具埋点，不靠模型自述）；面板「它自己」页签展示';
