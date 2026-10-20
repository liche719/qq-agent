-- 给「它看过的视频」加内容摘要（2026-10-20）
--
-- 为什么要加：原来的台账只有 bvid / 标题 / "字幕 266 字" 这种**字数**，
-- 真正的**内容**（这视频讲了什么）一个字都没存。结果它刷完就过去了，
-- 过两天想找"那个讲 git 记忆的视频"只能靠标题猜，等于没有记忆。
--
-- summary  ：这视频到底讲了什么（2~3 句，它自己读完提炼的）
-- takeaway ：它自己的看法——为什么值得记、跟它自己有什么关系
ALTER TABLE bili_view_log ADD COLUMN IF NOT EXISTS summary TEXT NOT NULL DEFAULT '';
ALTER TABLE bili_view_log ADD COLUMN IF NOT EXISTS takeaway VARCHAR(200) NOT NULL DEFAULT '';

-- 按内容检索（它回头找的时候用 ILIKE，数据量小够了）
CREATE INDEX IF NOT EXISTS idx_bili_view_title ON bili_view_log (title);

COMMENT ON COLUMN bili_view_log.summary IS '视频讲了什么（它读完自己写，2~3 句）';
COMMENT ON COLUMN bili_view_log.takeaway IS '它自己的看法：为什么值得记、跟自己有什么关系';
