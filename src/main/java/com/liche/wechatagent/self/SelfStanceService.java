package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 自主模块的**二期：判断 → 倾向**。
 *
 * <p>分工是刻意的：模型只能{@link #recordJudge 记判断}（原料），
 * **倾向只由程序按证据门槛提升**（{@link #promoteStance}），模型没有这个工具——
 * "它形成了一贯的样子"必须是攒出来的，不是它自己宣布的。
 * 复习成功/失败走 FSRS 曲线调整 S/D 与下次复查时间，反例必须落在账上（{@link #contradictStance}）。
 */
@Service
public class SelfStanceService {

    private static final Logger log = LoggerFactory.getLogger(SelfStanceService.class);

    private final AgentSelfEventRepository eventRepository;
    private final AgentStanceRepository stanceRepository;
    private final SelfCoreService core;
    private final int maxActiveStances;
    private final double decay;
    private final double reviewTarget;

    public SelfStanceService(AgentSelfEventRepository eventRepository,
                             AgentStanceRepository stanceRepository,
                             SelfCoreService core,
                             @Value("${memory.self-stance-max-active:5}") int maxActiveStances,
                             @Value("${memory.self-fsrs-decay:-0.1542}") double decay,
                             @Value("${memory.self-review-target:0.8}") double reviewTarget) {
        this.eventRepository = eventRepository;
        this.stanceRepository = stanceRepository;
        this.core = core;
        this.maxActiveStances = Math.max(1, maxActiveStances);
        this.decay = decay > 0 ? -decay : (decay == 0 ? -0.1542 : decay);
        this.reviewTarget = Math.min(0.98, Math.max(0.5, reviewTarget));
    }

    // ---------------------------------------------------------------- 判断（原料）

    /** 记一条判断——倾向的原料。topic 是类别（归不了类就不算），direction 是方向。 */
    @Transactional
    public AgentSelfEvent recordJudge(String topic, String direction, String content, String evidence) {
        core.requireEvidence(evidence);
        if (SelfText.isBlank(content)) {
            throw new IllegalArgumentException("判断内容不能为空");
        }
        String normalizedTopic = SelfText.clip(topic, 60);
        if (normalizedTopic.isEmpty()) {
            throw new IllegalArgumentException("判断必须带类别 topic（例如「学习安排」「该不该答应」），否则归不了类");
        }
        String normalizedDirection = SelfText.clip(direction, 16);
        return core.appendEvent(AgentSelfEvent.KIND_JUDGE, content, evidence, normalizedTopic,
                normalizedDirection.isEmpty() ? "NEUTRAL" : normalizedDirection, 2);
    }

    /** 记一次分歧：它的意见和用户的不一样（默认只讲一次，记一笔；spec §6 档 1）。 */
    @Transactional
    public AgentSelfEvent recordDisagree(String topic, String direction, String content, String evidence) {
        core.requireEvidence(evidence);
        if (SelfText.isBlank(content)) {
            throw new IllegalArgumentException("分歧要写清「我主张什么、他主张什么」");
        }
        String normalizedTopic = SelfText.clip(topic, 60);
        if (normalizedTopic.isEmpty()) {
            throw new IllegalArgumentException("分歧必须带类别 topic");
        }
        return core.appendEvent(AgentSelfEvent.KIND_DISAGREE, content, evidence, normalizedTopic,
                SelfText.clip(direction, 16), 3);
    }

    @Transactional(readOnly = true)
    public List<AgentSelfEvent> judgesFor(String topic) {
        if (SelfText.isBlank(topic)) {
            return List.of();
        }
        return eventRepository.findByKindAndTopicIgnoreCaseOrderByIdAsc(AgentSelfEvent.KIND_JUDGE, topic.trim());
    }

    /** 有判断记录过的类别（最近 scan 条里出现的） */
    @Transactional(readOnly = true)
    public List<String> judgeTopics(int scan) {
        List<AgentSelfEvent> recent = eventRepository.findByKindOrderByIdDesc(AgentSelfEvent.KIND_JUDGE,
                PageRequest.of(0, Math.max(1, scan)));
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        for (AgentSelfEvent event : recent) {
            if (event.getTopic() != null && !event.getTopic().isBlank()) {
                topics.add(event.getTopic().trim());
            }
        }
        return new ArrayList<>(topics);
    }

    // ---------------------------------------------------------------- 倾向

    @Transactional(readOnly = true)
    public List<AgentStance> activeStances() {
        return stanceRepository.findByStatusOrderByUpdatedAtDesc(AgentStance.STATUS_ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<AgentStance> stanceFor(String topic) {
        if (SelfText.isBlank(topic)) {
            return Optional.empty();
        }
        return stanceRepository.findByTopicAndStatusOrderByIdDesc(topic.trim(), AgentStance.STATUS_ACTIVE)
                .stream().findFirst();
    }

    /** 到点该复查的倾向（由 R(t,S) 掉到目标保留率反推出来的，不是拍脑袋的天数） */
    @Transactional(readOnly = true)
    public List<AgentStance> dueStances(LocalDateTime now) {
        return stanceRepository.findByStatusAndNextReviewAtBeforeOrderByNextReviewAtAsc(AgentStance.STATUS_ACTIVE, now);
    }

    @Transactional(readOnly = true)
    public long countActiveStances() {
        return stanceRepository.countByStatus(AgentStance.STATUS_ACTIVE);
    }

    /** 立一条倾向——**只由程序按规则调用**（达到证据门槛时），模型没有这个工具。 */
    @Transactional
    public AgentStance promoteStance(String topic, String direction, String content, List<Long> evidenceIds,
                                     List<Long> counterIds, String evidence) {
        core.requireEvidence(evidence);
        long active = countActiveStances();
        if (active >= maxActiveStances) {
            throw new IllegalArgumentException("活跃倾向已经有 " + active + " 条（上限 " + maxActiveStances
                    + "）：先退役或合并一条再加");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentStance stance = newStance(topic, direction, content, evidenceIds, counterIds, now);
        AgentStance saved = stanceRepository.save(stance);
        core.appendEvent(AgentSelfEvent.KIND_STANCE_FORMED,
                "形成倾向（" + saved.getTopic() + "）：" + saved.getContent(), evidence,
                saved.getTopic(), saved.getDirection(), 4);
        return saved;
    }

    /** 修订：旧倾向留档（REVISED 不删），新倾向接替，修订次数 +1。 */
    @Transactional
    public AgentStance reviseStance(Long stanceId, String direction, String content, List<Long> evidenceIds,
                                    List<Long> counterIds, String evidence) {
        core.requireEvidence(evidence);
        AgentStance old = stanceRepository.findById(stanceId).orElseThrow(
                () -> new IllegalArgumentException("找不到那条倾向：" + stanceId));
        LocalDateTime now = LocalDateTime.now();
        old.setStatus(AgentStance.STATUS_REVISED);
        old.setUpdatedAt(now);
        stanceRepository.save(old);

        AgentStance next = newStance(old.getTopic(), direction, content, evidenceIds, counterIds, now);
        // 修订本身是一次"学到了"：强度按复习成功增长，难度不变
        next.setStability(Fsrs.stabilityOnSuccess(old.getStability(), old.getDifficulty()));
        next.setDifficulty(old.getDifficulty());
        next.setReviseCount((old.getReviseCount() == null ? 0 : old.getReviseCount()) + 1);
        AgentStance saved = stanceRepository.save(next);
        core.appendEvent(AgentSelfEvent.KIND_STANCE_REVISED,
                "修订倾向（" + old.getTopic() + "）：旧「" + SelfText.clipLine(old.getContent(), 500) + "」→ 新「"
                        + SelfText.clipLine(saved.getContent(), 500) + "」",
                evidence, saved.getTopic(), saved.getDirection(), 4);
        return saved;
    }

    /** 复习成功：按这条倾向做事、结果被证实 → S 变长、复查推后。 */
    @Transactional
    public AgentStance supportStance(Long stanceId, List<Long> newEvidenceIds, String evidence) {
        core.requireEvidence(evidence);
        AgentStance stance = stanceRepository.findById(stanceId).orElseThrow(
                () -> new IllegalArgumentException("找不到那条倾向：" + stanceId));
        LocalDateTime now = LocalDateTime.now();
        stance.setEvidenceIds(mergeIds(stance.getEvidenceIds(), newEvidenceIds));
        stance.setSupportCount((stance.getSupportCount() == null ? 0 : stance.getSupportCount())
                + size(newEvidenceIds));
        stance.setStability(Fsrs.stabilityOnSuccess(stance.getStability(), stance.getDifficulty()));
        stance.setLastReviewAt(now);
        stance.setUpdatedAt(now);
        stance.setNextReviewAt(Fsrs.nextReviewAt(now, stance.getStability(), decay, reviewTarget));
        return stanceRepository.save(stance);
    }

    /** 复习失败：又犯了同类错 / 被反例推翻 → S 收缩、D 上升、复查提前（反例必须落在账上）。 */
    @Transactional
    public AgentStance contradictStance(Long stanceId, List<Long> newCounterIds, String evidence) {
        core.requireEvidence(evidence);
        AgentStance stance = stanceRepository.findById(stanceId).orElseThrow(
                () -> new IllegalArgumentException("找不到那条倾向：" + stanceId));
        LocalDateTime now = LocalDateTime.now();
        stance.setCounterIds(mergeIds(stance.getCounterIds(), newCounterIds));
        stance.setCounterCount((stance.getCounterCount() == null ? 0 : stance.getCounterCount())
                + size(newCounterIds));
        stance.setStability(Fsrs.stabilityOnFailure(stance.getStability()));
        stance.setDifficulty(Fsrs.difficultyOnFailure(stance.getDifficulty()));
        stance.setLastReviewAt(now);
        stance.setUpdatedAt(now);
        stance.setNextReviewAt(Fsrs.nextReviewAt(now, stance.getStability(), decay, reviewTarget));
        return stanceRepository.save(stance);
    }

    /** 60 天没有新证据支撑 → 降级为普通 note（**不删除**，spec §5 的衰减硬规则）。 */
    @Transactional
    public int demoteStaleStances(int idleDays, LocalDateTime now) {
        int affected = 0;
        for (AgentStance stance : activeStances()) {
            LocalDateTime last = stance.getLastReviewAt() == null ? stance.getFormedAt() : stance.getLastReviewAt();
            if (last == null || Duration.between(last, now).toDays() < Math.max(1, idleDays)) {
                continue;
            }
            stance.setStatus(AgentStance.STATUS_DEMOTED);
            stance.setUpdatedAt(now);
            stanceRepository.save(stance);
            // 审计事件：证据取它自己的证据区间；**写不进去也不能让整轮扫描挂掉**（一条脏数据不该拖垮反思）
            String evidence = SelfText.isBlank(stance.getEvidenceIds())
                    ? null : SelfCoreService.EVIDENCE_EVENT + firstId(stance.getEvidenceIds());
            if (evidence != null) {
                try {
                    core.appendEvent(AgentSelfEvent.KIND_STANCE_RETIRED,
                            "降级倾向（" + stance.getTopic() + "）：" + Math.max(1, idleDays) + " 天没有新证据支撑："
                                    + SelfText.clipLine(stance.getContent(), 500),
                            evidence, stance.getTopic(), stance.getDirection(), 2);
                } catch (RuntimeException exception) {
                    log.warn("降级倾向 #{} 的审计事件没写进去：{}", stance.getId(), exception.getMessage());
                }
            }
            affected++;
        }
        return affected;
    }

    // ---------------------------------------------------------------- 内部

    private AgentStance newStance(String topic, String direction, String content, List<Long> evidenceIds,
                                  List<Long> counterIds, LocalDateTime now) {
        String clippedTopic = SelfText.clip(topic, 60);
        if (clippedTopic.isEmpty()) {
            throw new IllegalArgumentException("倾向必须带类别 topic");
        }
        AgentStance stance = new AgentStance();
        stance.setTopic(clippedTopic);
        stance.setDirection(SelfText.clip(direction, 16).isEmpty() ? "NEUTRAL" : SelfText.clip(direction, 16));
        stance.setContent(SelfText.clip(content, 600));
        stance.setEvidenceIds(SelfText.clip(joinIds(evidenceIds), 300));
        stance.setCounterIds(SelfText.clip(joinIds(counterIds), 300));
        stance.setSupportCount(size(evidenceIds));
        stance.setCounterCount(size(counterIds));
        stance.setReviseCount(0);
        stance.setStability(1.0);
        stance.setDifficulty(5.0);
        stance.setLastReviewAt(now);
        stance.setNextReviewAt(Fsrs.nextReviewAt(now, 1.0, decay, reviewTarget));
        stance.setStatus(AgentStance.STATUS_ACTIVE);
        stance.setFormedAt(now);
        stance.setUpdatedAt(now);
        return stance;
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().distinct().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String mergeIds(String existing, List<Long> added) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            merged.addAll(List.of(existing.split(",")));
        }
        if (added != null) {
            added.forEach(id -> merged.add(String.valueOf(id)));
        }
        while (merged.size() > 60) {
            merged.remove(merged.iterator().next());
        }
        return String.join(",", merged);
    }

    private int size(List<Long> ids) {
        return ids == null ? 0 : (int) ids.stream().distinct().count();
    }

    private String firstId(String ids) {
        int comma = ids.indexOf(',');
        return comma < 0 ? ids.trim() : ids.substring(0, comma).trim();
    }
}
