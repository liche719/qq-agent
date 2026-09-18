-- 数据修复（2026-09-18）：撤掉 3 条**已经被新说法完整取代**的工作记忆
--
-- 为什么：这几条互相矛盾，模型读到一起就会打太极（实测回复里出现"事实库查不到""以你手上的通知为准"）。
--   29 / 30：教务系统"必须 VPN / 校外 403"的旧结论；
--   31：后来核对出来的新结论（jw.dgut.edu.cn 校外可直接打开，原 jwc 才需要 VPN）——它已经把 29/30 覆盖了。
--   32：女友"2025 级健身指导与管理专业"一句话版；
--   33：同信息 + 班号 + 课程 + 教室的详版——32 是 33 的子集。
-- 另外 19 / 36 / 50 也含重复片段，但它们是"一条记多事"的混合条目，整行撤掉会丢有效信息，**不动**。
--
-- 做法：标 SUPERSEDED（**不 DELETE**）+ 写变更日志留痕，和程序里"新事实替代旧事实"完全同一套语义。
-- 回滚：update user_work_memory set status='ACTIVE', superseded_by_id=null, last_decision_at=null
--       where id in (29, 30, 32);
--
-- ⚠️ 只对机主用户执行；先确认这几行的 user_id 与内容。

begin;

update user_work_memory
set status = 'SUPERSEDED',
    superseded_by_id = 31,
    updated_at = now(),
    last_decision_at = now()
where id in (29, 30)
  and user_id = '9C81741E2EFD75552F7FB3EB4B0D821C';

update user_work_memory
set status = 'SUPERSEDED',
    superseded_by_id = 33,
    updated_at = now(),
    last_decision_at = now()
where id = 32
  and user_id = '9C81741E2EFD75552F7FB3EB4B0D821C';

insert into memory_change_log (user_id, action, layer, target_id, before_content, after_content, reason, operator, created_at)
select user_id, 'SUPERSEDE', 'WORK', id, content, null,
       '旧说法已被新说法完整取代（教务系统：jw 校外可直接打开、jwc 才需 VPN）', 'SYSTEM', now()
from user_work_memory where id in (29, 30);

insert into memory_change_log (user_id, action, layer, target_id, before_content, after_content, reason, operator, created_at)
select user_id, 'SUPERSEDE', 'WORK', id, content, null,
       '简版已被含班级/课程/教室的详版取代', 'SYSTEM', now()
from user_work_memory where id = 32;

commit;

-- 核对（应返回 3 行 SUPERSEDED，superseded_by_id 分别是 31/31/33）
-- select id, status, superseded_by_id, left(content, 40) from user_work_memory where id in (29, 30, 32, 31, 33) order by id;
