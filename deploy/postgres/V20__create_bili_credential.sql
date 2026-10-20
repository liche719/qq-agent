-- B站扫码登录后存下来的凭据（2026-10-20）
--
-- 为什么要单独一张表：`SESSDATA` 从扫码登录里能同时拿到 `SESSDATA` 和 `bili_jct`（写操作的 csrf token），
-- 后者是发评论/回复的前提。凭据写进库里，面板上就能「扫码 → 自动存 → 随时看状态」，
-- 不用再手工复制 Cookie 到 .env（.env 里的 `BILI_SESSDATA` 仍然保留，作为库里没数据时的兜底）。
--
-- 只有一行（id = 1）；`bili_jct` 允许为空——只读场景（读字幕/关注动态）只要有 SESSDATA 就够。
CREATE TABLE IF NOT EXISTS bili_credential (
    id            INTEGER      PRIMARY KEY,
    sessdata      VARCHAR(1024) NOT NULL DEFAULT '',
    bili_jct      VARCHAR(256)  NOT NULL DEFAULT '',
    mid           VARCHAR(64)   NOT NULL DEFAULT '',
    uname         VARCHAR(128)  NOT NULL DEFAULT '',
    updated_at    TIMESTAMP     NOT NULL
);

COMMENT ON TABLE bili_credential IS 'B站扫码登录凭据（单行，id=1）；bili_jct 为空时只能读不能写';
