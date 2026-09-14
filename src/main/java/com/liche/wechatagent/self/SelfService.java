package com.liche.wechatagent.self;

import com.liche.wechatagent.memory.ConversationMemory;
import com.liche.wechatagent.memory.ConversationMemoryRepository;
import com.liche.wechatagent.memory.MemoryTextSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 自主模块的**唯一写入入口**。
 *
 * <p>三条硬约束（都写在服务层，不靠提示词）：
 * <ol>
 *   <li><b>证据强制</b>：任何写入都必须带 {@code evidence}，且必须能解析成**真实存在**的对话记录或事件；
 *       解析不出来就拒。这条直接针对上次"归纳"翻车（模型把两条原文用「；」拼起来当结论）。</li>
 *   <li><b>上限</b>：块内容不许超过自己的 {@code char_limit}；未结承诺有条数上限。</li>
 *   <li><b>归属</b>：这一侧属于"它自己"，但**只对机主生效**（fail-closed：没配归属人就整个模块不工作，
 *       避免别人的对话污染它、也避免它把机主的事带到别人那里）。</li>
 * </ol>
 */
@Service
public class SelfService {

    private static final Logger log = LoggerFactory.getLogger(SelfService.class);

    /** 证据前缀：对话记录 */
    public static final String EVIDENCE_CONVERSATION = "conv:";
    /** 证据前缀：自主模块自己的事件 */
    public static final String EVIDENCE_EVENT = "event:";

    private final AgentSelfBlockRepository blockRepository;
    private final AgentSelfEventRepository eventRepository;
    private final AgentCommitmentRepository commitmentRepository;
    private final AgentStanceRepository stanceRepository;
    private final AgentReflectionRepository reflectionRepository;
    private final AgentLessonRepository lessonRepository;
    private final ConversationMemoryRepository conversationMemoryRepository;
    private final boolean enabled;
    private final String ownerOpenId;
    private final int defaultBlockCharLimit;
    private final int maxOpenCommitments;
    private final int maxActiveStances;
    private final int maxLessons;
    private final double lessonMergeSimilarity;
    private final int cleanReviewsToClose;
    private final double decay;
    private final double reviewTarget;

    public SelfService(AgentSelfBlockRepository blockRepository,
                       AgentSelfEventRepository eventRepository,
                       AgentCommitmentRepository commitmentRepository,
                       AgentStanceRepository stanceRepository,
                       AgentReflectionRepository reflectionRepository,
                       AgentLessonRepository lessonRepository,
                       ConversationMemoryRepository conversationMemoryRepository,
                       @Value("${memory.self-enabled:true}") boolean enabled,
                       @Value("${memory.self-owner-openid:}") String ownerOpenId,
                       @Value("${memory.self-block-char-limit:1200}") int defaultBlockCharLimit,
                       @Value("${memory.self-max-commitments:20}") int maxOpenCommitments,
                       @Value("${memory.self-stance-max-active:5}") int maxActiveStances,
                       @Value("${memory.self-max-lessons:30}") int maxLessons,
                       @Value("${memory.self-lesson-merge-similarity:0.72}") double lessonMergeSimilarity,
                       @Value("${memory.self-lesson-clean-reviews-to-close:3}") int cleanReviewsToClose,
                       @Value("${memory.self-fsrs-decay:-0.1542}") double decay,
                       @Value("${memory.self-review-target:0.8}") double reviewTarget) {
        this.blockRepository = blockRepository;
        this.eventRepository = eventRepository;
        this.commitmentRepository = commitmentRepository;
        this.stanceRepository = stanceRepository;
        this.lessonRepository = lessonRepository;
        this.reflectionRepository = reflectionRepository;
        this.conversationMemoryRepository = conversationMemoryRepository;
        this.enabled = enabled;
        this.ownerOpenId = ownerOpenId == null ? "" : ownerOpenId.trim();
        this.defaultBlockCharLimit = Math.max(100, defaultBlockCharLimit);
        this.maxOpenCommitments = Math.max(1, maxOpenCommitments);
        this.maxActiveStances = Math.max(1, maxActiveStances);
        this.maxLessons = Math.max(5, maxLessons);
        this.lessonMergeSimilarity = Math.min(0.95, Math.max(0.5, lessonMergeSimilarity));
        this.cleanReviewsToClose = Math.max(1, cleanReviewsToClose);
        this.decay = decay > 0 ? -decay : (decay == 0 ? -0.1542 : decay);
        this.reviewTarget = Math.min(0.98, Math.max(0.5, reviewTarget));
    }

    /** 归属人（模块没配归属人时返回 null）——反思定时任务要用它 */
    public String owner() {
        return ownerOpenId.isBlank() ? null : ownerOpenId;
    }

    /** 模块是否处于工作状态：开关打开 **且** 配了归属人。 */
    public boolean isActive() {
        return enabled && !ownerOpenId.isBlank();
    }

    /** 归属人判定——fail-closed：没配归属人时谁都别想读写。 */
    public boolean isOwner(String userId) {
        return isActive() && ownerOpenId.equals(userId);
    }

    /** 给模型看的拒绝理由（工具会把它回给模型）。 */
    public String inactiveReason() {
        if (!enabled) {
            return "自主模块已关闭";
        }
        return "未配置归属人（memory.self-owner-openid），自主模块暂不工作";
    }

    // ---------------------------------------------------------------- 读

    @Transactional(readOnly = true)
    public List<AgentSelfBlock> blocks() {
        return blockRepository.findAllByOrderByBlockTypeAscLabelAsc();
    }

    @Transactional(readOnly = true)
    public Optional<AgentSelfBlock> block(String blockType) {
        return blockRepository.findByBlockTypeOrderByLabelAsc(blockType).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<AgentCommitment> openCommitments() {
        return commitmentRepository.findByStatusOrderByDueAtAsc(AgentCommitment.STATUS_OPEN);
    }

    @Transactional(readOnly = true)
    public List<AgentSelfEvent> recentEvents(int limit) {
        return limit <= 20 ? eventRepository.findTop20ByOrderByIdDesc() : eventRepository.findTop200ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public Optional<AgentSelfEvent> latestEvent() {
        return eventRepository.findTop20ByOrderByIdDesc().stream().findFirst();
    }

    /**
     * 最近的对话记录（带真实 id）——**模型唯一能拿到合法证据编号的来源**。
     *
     * <p>为什么要这个：模型在任何地方都看不到 {@code conversation_memory} 的行号，而写入要求
     * {@code conv:<id>} 必须真实存在 → 没有这个读工具，第一次写入永远拿不到证据（死锁）。
     * 本轮消息要等回复完才落库，所以这里最新的一条是**上一轮**。
     */
    @Transactional(readOnly = true)
    public List<ConversationMemory> recentConversation(String userId, int limit) {
        int size = Math.min(20, Math.max(1, limit));
        return conversationMemoryRepository.findByUserIdAndRoleInOrderByCreatedAtDesc(
                userId, List.of("user", "assistant"), org.springframework.data.domain.PageRequest.of(0, size));
    }

    // ---------------------------------------------------------------- 写

    /** 随手记：低门槛，进事件、不进块。 */
    @Transactional
    public AgentSelfEvent note(String kind, String content, String evidence, String topic, String stance) {
        return appendEvent(kind, content, evidence, topic, stance, 0);
    }

    /**
     * 追加到某个块；超 {@code char_limit} 直接拒绝（要求先 summarize）。
     * 块不存在时按 {@code block_type} 建一个。
     */
    @Transactional
    public AgentSelfBlock appendBlock(String blockType, String text, String evidence) {
        requireEvidence(evidence);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        AgentSelfBlock block = block(blockType).orElseGet(() -> newBlock(blockType));
        String current = block.getValue() == null ? "" : block.getValue();
        String merged = current.isBlank() ? text.trim() : current + "\n" + text.trim();
        if (merged.length() > block.getCharLimit()) {
            throw new IllegalArgumentException("块 " + blockType + " 已到上限（" + block.getCharLimit()
                    + " 字），先调用 self_summarize 压一压再写");
        }
        block.setValue(merged);
        block.setVersion(block.getVersion() == null ? 1 : block.getVersion() + 1);
        block.setUpdatedAt(LocalDateTime.now());
        if (block.getCreatedAt() == null) {
            block.setCreatedAt(LocalDateTime.now());
        }
        AgentSelfBlock saved = blockRepository.save(block);
        appendEvent(AgentSelfEvent.KIND_NOTE, "更新了「" + blockType + "」：" + abbreviate(text), evidence, null, null, 0);
        return saved;
    }

    /** 替换式修改：旧值进事件历史，可回溯。 */
    @Transactional
    public AgentSelfBlock replaceBlock(String blockType, String oldText, String newText, String evidence) {
        requireEvidence(evidence);
        AgentSelfBlock block = block(blockType).orElseThrow(
                () -> new IllegalArgumentException("还没有 " + blockType + " 块，先用 self_append 建立"));
        String current = block.getValue() == null ? "" : block.getValue();
        if (oldText == null || !current.contains(oldText)) {
            throw new IllegalArgumentException("在 " + blockType + " 块里找不到要替换的内容");
        }
        String updated = current.replace(oldText, newText == null ? "" : newText);
        if (updated.length() > block.getCharLimit()) {
            throw new IllegalArgumentException("替换后超过上限（" + block.getCharLimit() + " 字），先 summarize");
        }
        block.setValue(updated);
        block.setVersion(block.getVersion() == null ? 1 : block.getVersion() + 1);
        block.setUpdatedAt(LocalDateTime.now());
        AgentSelfBlock saved = blockRepository.save(block);
        appendEvent(AgentSelfEvent.KIND_NOTE, "改写了「" + blockType + "」：" + abbreviate(oldText)
                + " → " + abbreviate(newText), evidence, null, null, 0);
        return saved;
    }

    /**
     * 压缩：**纯程序**做的保守压缩——保留最近的整行，丢掉较早的行并标明。
     * （真正"总结"要花一次模型调用，属于二期反思流程；这里不做，避免又出现"模型记账"那类问题。）
     */
    @Transactional
    public AgentSelfBlock summarizeBlock(String blockType, String evidence) {
        requireEvidence(evidence);
        AgentSelfBlock block = block(blockType).orElseThrow(
                () -> new IllegalArgumentException("还没有 " + blockType + " 块"));
        String current = block.getValue() == null ? "" : block.getValue();
        int keep = Math.max(100, block.getCharLimit() / 2);
        String compressed = current.length() <= keep ? current : "（较早内容已压缩）\n" + tailLines(current, keep);
        block.setValue(compressed);
        block.setVersion(block.getVersion() == null ? 1 : block.getVersion() + 1);
        block.setUpdatedAt(LocalDateTime.now());
        AgentSelfBlock saved = blockRepository.save(block);
        appendEvent(AgentSelfEvent.KIND_NOTE, "压缩了「" + blockType + "」：" + current.length()
                + " → " + compressed.length() + " 字", evidence, null, null, 0);
        return saved;
    }

    /** 立一个自己的目标：写进 TASK 块 + 记一条 GOAL_SET。 */
    @Transactional
    public AgentSelfEvent openGoal(String content, String why, String evidence) {
        requireEvidence(evidence);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("目标内容不能为空");
        }
        AgentSelfBlock block = block(AgentSelfBlock.TYPE_TASK).orElseGet(() -> newBlock(AgentSelfBlock.TYPE_TASK));
        String line = "· " + content.trim() + (why == null || why.isBlank() ? "" : "（因为：" + why.trim() + "）");
        String current = block.getValue() == null ? "" : block.getValue();
        String merged = current.isBlank() ? line : current + "\n" + line;
        if (merged.length() > block.getCharLimit()) {
            throw new IllegalArgumentException("「在做的事」块已满，先 self_summarize 再立新目标");
        }
        block.setValue(merged);
        block.setVersion(block.getVersion() == null ? 1 : block.getVersion() + 1);
        block.setUpdatedAt(LocalDateTime.now());
        if (block.getCreatedAt() == null) {
            block.setCreatedAt(LocalDateTime.now());
        }
        blockRepository.save(block);
        return appendEvent(AgentSelfEvent.KIND_GOAL_SET, content.trim(), evidence, null, null, 5);
    }

    /** 关掉一个自己的目标（goalEventId = goal_open 返回的事件 id）。 */
    @Transactional
    public AgentSelfEvent closeGoal(Long goalEventId, String outcome, String evidence) {
        requireEvidence(evidence);
        AgentSelfEvent goal = eventRepository.findById(goalEventId).orElseThrow(
                () -> new IllegalArgumentException("找不到那个目标事件：" + goalEventId));
        if (!AgentSelfEvent.KIND_GOAL_SET.equals(goal.getKind())) {
            throw new IllegalArgumentException("那条不是自己立的目标");
        }
        return appendEvent(AgentSelfEvent.KIND_GOAL_CLOSED, "目标收尾：「" + abbreviate(goal.getContent())
                + "」" + (outcome == null || outcome.isBlank() ? "" : " → " + outcome.trim()), evidence, null, null, 3);
    }

    /** 立诺 / 做预测。 */
    @Transactional
    public AgentCommitment commit(String content, LocalDateTime dueAt, String evidence) {
        requireEvidence(evidence);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("承诺内容不能为空");
        }
        long open = commitmentRepository.countByStatus(AgentCommitment.STATUS_OPEN);
        if (open >= maxOpenCommitments) {
            throw new IllegalArgumentException("未结的承诺已经有 " + open + " 条，先把旧的处理掉");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentCommitment commitment = new AgentCommitment();
        commitment.setContent(clip(content, 500));
        commitment.setDueAt(dueAt);
        commitment.setStatus(AgentCommitment.STATUS_OPEN);
        commitment.setEvidence(evidence.trim());
        commitment.setCreatedAt(now);
        commitment.setUpdatedAt(now);
        AgentCommitment saved = commitmentRepository.save(commitment);
        appendEvent(AgentSelfEvent.KIND_COMMIT, "立下：" + abbreviate(content)
                + (dueAt == null ? "" : "（截止 " + dueAt.toLocalDate() + "）"), evidence, null, null, 3);
        return saved;
    }

    /** 兑现 / 认欠。 */
    @Transactional
    public AgentCommitment resolveCommitment(Long commitmentId, String status, String evidence) {
        requireEvidence(evidence);
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!List.of(AgentCommitment.STATUS_KEPT, AgentCommitment.STATUS_BROKEN, AgentCommitment.STATUS_ABANDONED)
                .contains(normalized)) {
            throw new IllegalArgumentException("status 只能是 KEPT / BROKEN / ABANDONED");
        }
        AgentCommitment commitment = commitmentRepository.findById(commitmentId).orElseThrow(
                () -> new IllegalArgumentException("找不到那条承诺：" + commitmentId));
        commitment.setStatus(normalized);
        commitment.setResolvedAt(LocalDateTime.now());
        commitment.setUpdatedAt(LocalDateTime.now());
        AgentCommitment saved = commitmentRepository.save(commitment);
        appendEvent(AgentSelfEvent.KIND_COMMIT_RESOLVED, "「" + abbreviate(commitment.getContent()) + "」→ " + normalized,
                evidence, null, null, normalized.equals(AgentCommitment.STATUS_KEPT) ? 2 : 5);
        return saved;
    }

    // ---------------------------------------------------------------- 二期：判断 → 倾向

    /** 记一条判断——倾向的原料。topic 是类别（归不了类就不算），direction 是方向。 */
    @Transactional
    public AgentSelfEvent recordJudge(String topic, String direction, String content, String evidence) {
        requireEvidence(evidence);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("判断内容不能为空");
        }
        String normalizedTopic = clip(topic, 60);
        if (normalizedTopic.isEmpty()) {
            throw new IllegalArgumentException("判断必须带类别 topic（例如「学习安排」「该不该答应」），否则归不了类");
        }
        String normalizedDirection = clip(direction, 16);
        return appendEvent(AgentSelfEvent.KIND_JUDGE, content, evidence, normalizedTopic,
                normalizedDirection.isEmpty() ? "NEUTRAL" : normalizedDirection, 2);
    }

    /** 记一次分歧：它的意见和用户的不一样（默认只讲一次，记一笔；spec §6 档 1）。 */
    @Transactional
    public AgentSelfEvent recordDisagree(String topic, String direction, String content, String evidence) {
        requireEvidence(evidence);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("分歧要写清「我主张什么、他主张什么」");
        }
        String normalizedTopic = clip(topic, 60);
        if (normalizedTopic.isEmpty()) {
            throw new IllegalArgumentException("分歧必须带类别 topic");
        }
        return appendEvent(AgentSelfEvent.KIND_DISAGREE, content, evidence, normalizedTopic,
                clip(direction, 16), 3);
    }

    @Transactional(readOnly = true)
    public List<AgentSelfEvent> judgesFor(String topic) {
        if (topic == null || topic.isBlank()) {
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

    @Transactional(readOnly = true)
    public List<AgentStance> activeStances() {
        return stanceRepository.findByStatusOrderByUpdatedAtDesc(AgentStance.STATUS_ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<AgentStance> stanceFor(String topic) {
        if (topic == null || topic.isBlank()) {
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
        requireEvidence(evidence);
        long active = countActiveStances();
        if (active >= maxActiveStances) {
            throw new IllegalArgumentException("活跃倾向已经有 " + active + " 条（上限 " + maxActiveStances
                    + "）：先退役或合并一条再加");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentStance stance = newStance(topic, direction, content, evidenceIds, counterIds, now);
        AgentStance saved = stanceRepository.save(stance);
        appendEvent(AgentSelfEvent.KIND_STANCE_FORMED,
                "形成倾向（" + saved.getTopic() + "）：" + saved.getContent(), evidence,
                saved.getTopic(), saved.getDirection(), 4);
        return saved;
    }

    /** 修订：旧倾向留档（REVISED 不删），新倾向接替，修订次数 +1。 */
    @Transactional
    public AgentStance reviseStance(Long stanceId, String direction, String content, List<Long> evidenceIds,
                                    List<Long> counterIds, String evidence) {
        requireEvidence(evidence);
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
        appendEvent(AgentSelfEvent.KIND_STANCE_REVISED,
                "修订倾向（" + old.getTopic() + "）：旧「" + abbreviate(old.getContent()) + "」→ 新「"
                        + abbreviate(saved.getContent()) + "」", evidence, saved.getTopic(), saved.getDirection(), 4);
        return saved;
    }

    /** 复习成功：按这条倾向做事、结果被证实 → S 变长、复查推后。 */
    @Transactional
    public AgentStance supportStance(Long stanceId, List<Long> newEvidenceIds, String evidence) {
        requireEvidence(evidence);
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
        requireEvidence(evidence);
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

    /** 退役（逃生门/降级都用它）：不删除，留档可回溯。 */
    @Transactional
    public AgentStance retireStance(Long stanceId, String reason, String evidence) {
        requireEvidence(evidence);
        AgentStance stance = stanceRepository.findById(stanceId).orElseThrow(
                () -> new IllegalArgumentException("找不到那条倾向：" + stanceId));
        LocalDateTime now = LocalDateTime.now();
        stance.setStatus(AgentStance.STATUS_RETIRED);
        stance.setUpdatedAt(now);
        AgentStance saved = stanceRepository.save(stance);
        appendEvent(AgentSelfEvent.KIND_STANCE_RETIRED,
                "退役倾向（" + saved.getTopic() + "）：" + abbreviate(saved.getContent())
                        + (reason == null || reason.isBlank() ? "" : "（原因：" + abbreviate(reason) + "）"),
                evidence, saved.getTopic(), saved.getDirection(), 3);
        return saved;
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
            String evidence = stance.getEvidenceIds() == null || stance.getEvidenceIds().isBlank()
                    ? null : "event:" + firstId(stance.getEvidenceIds());
            if (evidence != null) {
                try {
                    appendEvent(AgentSelfEvent.KIND_STANCE_RETIRED,
                            "降级倾向（" + stance.getTopic() + "）：" + Math.max(1, idleDays) + " 天没有新证据支撑："
                                    + abbreviate(stance.getContent()),
                            evidence, stance.getTopic(), stance.getDirection(), 2);
                } catch (RuntimeException exception) {
                    log.warn("降级倾向 #{} 的审计事件没写进去：{}", stance.getId(), exception.getMessage());
                }
            }
            affected++;
        }
        return affected;
    }

    // ---------------------------------------------------------------- 二期：反思

    @Transactional(readOnly = true)
    public List<AgentSelfEvent> eventsSince(LocalDateTime since) {
        return since == null ? eventRepository.findTop200ByOrderByIdDesc()
                : eventRepository.findByCreatedAtAfterOrderByIdAsc(since);
    }

    /** 到期还没兑现的承诺（反思流程把它们判成 BROKEN——"得失"的落点）。 */
    @Transactional(readOnly = true)
    public List<AgentCommitment> dueOpenCommitments(LocalDateTime now) {
        return commitmentRepository.findByStatusOrderByDueAtAsc(AgentCommitment.STATUS_OPEN).stream()
                .filter(commitment -> commitment.getDueAt() != null && !commitment.getDueAt().isAfter(now))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<AgentReflection> lastReflection() {
        return reflectionRepository.findTop1ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<AgentReflection> recentReflections(int limit) {
        return reflectionRepository.findTop50ByOrderByIdDesc().stream().limit(Math.max(1, limit)).toList();
    }

    @Transactional(readOnly = true)
    public long reflectionsToday() {
        return reflectionRepository.countByCreatedAtAfter(LocalDate.now().atStartOfDay());
    }

    /** 距上次反思以来机主说了多少轮（"攒够 N 轮"的判据）。 */
    @Transactional(readOnly = true)
    public long turnsSinceLastReflection() {
        if (!isActive()) {
            return 0;
        }
        LocalDateTime since = lastReflection().map(AgentReflection::getCreatedAt).orElse(null);
        return conversationMemoryRepository
                .findByUserIdAndRoleInOrderByCreatedAtDesc(ownerOpenId, List.of("user"), PageRequest.of(0, 200))
                .stream()
                .filter(row -> since == null || (row.getCreatedAt() != null && row.getCreatedAt().isAfter(since)))
                .count();
    }

    /**
     * 反思落库。**必须带证据链**（读了哪几条事件），否则整条拒绝——
     * 对应 spec §4 的"合成失败就丢弃，不写半成品"。
     */
    @Transactional
    public AgentReflection recordReflection(int level, String trigger, List<Long> inputEventIds, String conclusion,
                                            int importance, Long writtenBack, int calls, int promptChars,
                                            int responseChars, int promptTokens, int completionTokens,
                                            int durationMs) {
        if (inputEventIds == null || inputEventIds.isEmpty()) {
            throw new IllegalArgumentException("反思必须带证据链（inputEventIds 不能为空）");
        }
        String evidence = inputEventIds.stream().map(id -> "event:" + id).collect(Collectors.joining(","));
        requireEvidence(evidence);
        AgentReflection reflection = new AgentReflection();
        reflection.setLevel(Math.max(1, level));
        reflection.setTriggerType(clip(trigger, 16));
        reflection.setInputEventIds(clip(evidence, 500));
        reflection.setConclusion(clip(conclusion, 1000));
        reflection.setImportance(importance);
        reflection.setWrittenBack(writtenBack);
        reflection.setCalls(Math.max(1, calls));
        reflection.setPromptChars(Math.max(0, promptChars));
        reflection.setResponseChars(Math.max(0, responseChars));
        reflection.setPromptTokens(Math.max(0, promptTokens));
        reflection.setCompletionTokens(Math.max(0, completionTokens));
        reflection.setDurationMs(Math.max(0, durationMs));
        reflection.setCreatedAt(LocalDateTime.now());
        AgentReflection saved = reflectionRepository.save(reflection);
        appendEvent(AgentSelfEvent.KIND_REFLECT, saved.getConclusion(), "event:" + inputEventIds.get(0),
                null, null, importance);
        return saved;
    }

    /** 整块改写（反思流程用；模型侧走 selfAppend/selfReplace）。 */
    @Transactional
    public AgentSelfBlock setBlockValue(String blockType, String value, String evidence) {
        requireEvidence(evidence);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        AgentSelfBlock block = block(blockType).orElseGet(() -> newBlock(blockType));
        String clipped = value.trim();
        if (clipped.length() > block.getCharLimit()) {
            throw new IllegalArgumentException("块 " + blockType + " 超上限（" + block.getCharLimit() + " 字）");
        }
        String current = block.getValue() == null ? "" : block.getValue();
        block.setValue(clipped);
        block.setVersion(block.getVersion() == null ? 1 : block.getVersion() + 1);
        block.setUpdatedAt(LocalDateTime.now());
        if (block.getCreatedAt() == null) {
            block.setCreatedAt(LocalDateTime.now());
        }
        AgentSelfBlock saved = blockRepository.save(block);
        appendEvent(AgentSelfEvent.KIND_NOTE,
                "改写了「" + blockType + "」：" + abbreviate(current) + " → " + abbreviate(clipped),
                evidence, null, null, 0);
        return saved;
    }

    private AgentStance newStance(String topic, String direction, String content, List<Long> evidenceIds,
                                  List<Long> counterIds, LocalDateTime now) {
        String clippedTopic = clip(topic, 60);
        if (clippedTopic.isEmpty()) {
            throw new IllegalArgumentException("倾向必须带类别 topic");
        }
        AgentStance stance = new AgentStance();
        stance.setTopic(clippedTopic);
        stance.setDirection(clip(direction, 16).isEmpty() ? "NEUTRAL" : clip(direction, 16));
        stance.setContent(clip(content, 600));
        stance.setEvidenceIds(clip(joinIds(evidenceIds), 300));
        stance.setCounterIds(clip(joinIds(counterIds), 300));
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

    /** 按列宽截断，**为省略号留一位**（坑 40：多一个字符就是 Data too long，整条写入失败）。 */
    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (max <= 0) {
            return "";
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, Math.max(0, max - 1)) + "…";
    }

    // ---------------------------------------------------------------- 三期：教训清单（它自己的可靠性）

    /** 教训的五个类别（spec §9.1） */
    public static final List<String> LESSON_CATEGORIES = List.of(
            AgentLesson.CATEGORY_TIME, AgentLesson.CATEGORY_COMMITMENT, AgentLesson.CATEGORY_GUESS,
            AgentLesson.CATEGORY_FORMAT, AgentLesson.CATEGORY_TOOL);

    /** 类别 → 触发词：只在**同类场景**注入教训，不做全局唠叨（§9.1 硬规则 3） */
    private static final Map<String, List<String>> LESSON_KEYWORDS = Map.of(
            AgentLesson.CATEGORY_TIME, List.of("时间", "日程", "提醒", "几点", "明天", "后天", "下周"),
            AgentLesson.CATEGORY_COMMITMENT, List.of("答应", "承诺", "说好", "欠着", "之前说", "记得"),
            AgentLesson.CATEGORY_GUESS, List.of("是不是", "为什么", "哪年", "版本", "网址", "政策"),
            AgentLesson.CATEGORY_FORMAT, List.of("格式", "字数", "太长", "表格", "排版", "简洁"),
            AgentLesson.CATEGORY_TOOL, List.of("工具", "下载", "搜索", "发文件", "图片", "读取"));

    /** ADD 的结果：新记一条，还是"又犯了"并进了已有那条 */
    public record LessonOutcome(AgentLesson lesson, boolean recurred) {
    }

    /**
     * 记一条教训（ExpeL 的 ADD）。三段必须齐、correction 必须可执行；
     * 同类（同 category + 文本足够像）**不新增**，按"又犯了"处理（DOWNVOTE）。
     */
    @Transactional
    public LessonOutcome addLesson(String category, String trigger, String whatIDid, String expected,
                                   String whatHappened, String correction, String evidence) {
        requireEvidence(evidence);
        String normalized = clip(category, 24).toUpperCase(Locale.ROOT);
        if (!LESSON_CATEGORIES.contains(normalized)) {
            throw new IllegalArgumentException("category 只能是 " + String.join(" / ", LESSON_CATEGORIES));
        }
        if (isBlank(whatIDid) || isBlank(expected) || isBlank(whatHappened)) {
            throw new IllegalArgumentException("三段必须齐：我做了什么 / 我当时预期 / 实际发生了什么（缺一段不算教训）");
        }
        if (isBlank(correction)) {
            throw new IllegalArgumentException("必须给可执行的 correction（以后怎么做），不要写感悟");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentLesson similar = findSimilarLesson(normalized, whatHappened, correction);
        if (similar != null) {
            similar.setRecurrenceCount((similar.getRecurrenceCount() == null ? 1 : similar.getRecurrenceCount()) + 1);
            similar.setLastSeenAt(now);
            similar.setWhatHappened(clip(whatHappened, 500));
            similar.setStability(Fsrs.stabilityOnFailure(similar.getStability()));
            similar.setDifficulty(Fsrs.difficultyOnFailure(similar.getDifficulty()));
            similar.setCleanReviews(0);
            similar.setStatus(AgentLesson.STATUS_OPEN);
            similar.setLastReviewAt(now);
            similar.setNextReviewAt(Fsrs.nextReviewAt(now, similar.getStability(), decay, reviewTarget));
            similar.setEvidence(evidence.trim());
            similar.setUpdatedAt(now);
            AgentLesson saved = lessonRepository.save(similar);
            appendEvent(AgentSelfEvent.KIND_LESSON, "又犯了一次（" + saved.getCategory() + "，第 "
                    + saved.getRecurrenceCount() + " 次）：" + abbreviate(saved.getCorrection()),
                    evidence, saved.getCategory(), null, 3);
            return new LessonOutcome(saved, true);
        }
        long active = countActiveLessons();
        if (active >= maxLessons) {
            throw new IllegalArgumentException("教训清单已有 " + active + " 条（上限 " + maxLessons
                    + "）：先合并或关掉旧的——清单越来越长却没变化，那是在写作文");
        }
        AgentLesson lesson = new AgentLesson();
        lesson.setCategory(normalized);
        String normalizedTrigger = clip(trigger, 24).toUpperCase(Locale.ROOT);
        lesson.setTriggerType(normalizedTrigger.isEmpty() ? AgentLesson.TRIGGER_SELF_CHECK : normalizedTrigger);
        lesson.setWhatIDid(clip(whatIDid, 500));
        lesson.setExpectedResult(clip(expected, 500));
        lesson.setWhatHappened(clip(whatHappened, 500));
        lesson.setCorrection(clip(correction, 500));
        lesson.setFirstSeenAt(now);
        lesson.setLastSeenAt(now);
        lesson.setRecurrenceCount(1);
        lesson.setCleanReviews(0);
        lesson.setStability(1.0);
        lesson.setDifficulty(5.0);
        lesson.setLastReviewAt(now);
        lesson.setNextReviewAt(Fsrs.nextReviewAt(now, 1.0, decay, reviewTarget));
        lesson.setStatus(AgentLesson.STATUS_OPEN);
        lesson.setEvidence(evidence.trim());
        lesson.setCreatedAt(now);
        lesson.setUpdatedAt(now);
        AgentLesson saved = lessonRepository.save(lesson);
        appendEvent(AgentSelfEvent.KIND_LESSON, "记了一条教训（" + saved.getCategory() + "）："
                + abbreviate(saved.getCorrection()), evidence, saved.getCategory(), null, 3);
        return new LessonOutcome(saved, false);
    }

    /** EDIT：把教训改得更可执行（不是写感悟）。 */
    @Transactional
    public AgentLesson editLessonCorrection(Long lessonId, String correction, String evidence) {
        requireEvidence(evidence);
        if (isBlank(correction)) {
            throw new IllegalArgumentException("correction 不能为空");
        }
        AgentLesson lesson = lessonRepository.findById(lessonId).orElseThrow(
                () -> new IllegalArgumentException("找不到那条教训：" + lessonId));
        lesson.setCorrection(clip(correction, 500));
        lesson.setUpdatedAt(LocalDateTime.now());
        lesson.setEvidence(evidence.trim());
        AgentLesson saved = lessonRepository.save(lesson);
        appendEvent(AgentSelfEvent.KIND_LESSON, "改了教训的做法（" + saved.getCategory() + "）："
                + abbreviate(saved.getCorrection()), evidence, saved.getCategory(), null, 2);
        return saved;
    }

    /**
     * 复查（spec §9.2）：**程序驱动**——模型不能给自己点赞。
     * 到期的教训，若"上次复查之后该类别没有新教训" → UPVOTE（没再犯）：S 变长，连续若干次干净就关闭。
     * "又犯了"由 {@link #addLesson} 合并时记账，那才是 DOWNVOTE。
     */
    @Transactional
    public int reviewLessons(LocalDateTime now) {
        int reviewed = 0;
        for (AgentLesson lesson : lessonRepository.findByStatusInAndNextReviewAtBeforeOrderByNextReviewAtAsc(
                List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING), now)) {
            LocalDateTime since = lesson.getLastReviewAt() == null ? lesson.getFirstSeenAt() : lesson.getLastReviewAt();
            boolean clean = lessonRepository.findByCategoryAndLastSeenAtAfter(lesson.getCategory(), since).stream()
                    .noneMatch(other -> !java.util.Objects.equals(other.getId(), lesson.getId()));
            if (!clean) {
                continue;
            }
            int cleanReviews = (lesson.getCleanReviews() == null ? 0 : lesson.getCleanReviews()) + 1;
            lesson.setCleanReviews(cleanReviews);
            lesson.setStability(Fsrs.stabilityOnSuccess(lesson.getStability(), lesson.getDifficulty()));
            lesson.setLastReviewAt(now);
            lesson.setUpdatedAt(now);
            lesson.setNextReviewAt(Fsrs.nextReviewAt(now, lesson.getStability(), decay, reviewTarget));
            lesson.setStatus(cleanReviews >= cleanReviewsToClose
                    ? AgentLesson.STATUS_CLOSED : AgentLesson.STATUS_IMPROVING);
            lessonRepository.save(lesson);
            reviewed++;
        }
        return reviewed;
    }

    @Transactional(readOnly = true)
    public List<AgentLesson> activeLessons() {
        return lessonRepository.findByStatusInOrderByLastSeenAtDesc(
                List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING));
    }

    @Transactional(readOnly = true)
    public long countActiveLessons() {
        return lessonRepository.countByStatusIn(List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING));
    }

    /** 这条消息踩到了哪些类别的场景——用于"只在同类场景提示教训" */
    @Transactional(readOnly = true)
    public List<AgentLesson> lessonsInPlay(String userMessage, int limit) {
        if (isBlank(userMessage)) {
            return List.of();
        }
        List<AgentLesson> picked = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : LESSON_KEYWORDS.entrySet()) {
            if (entry.getValue().stream().noneMatch(userMessage::contains)) {
                continue;
            }
            for (AgentLesson lesson : lessonRepository.findByCategoryAndStatusInOrderByLastSeenAtDesc(
                    entry.getKey(), List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING))) {
                if (picked.stream().noneMatch(existing -> existing.getId().equals(lesson.getId()))) {
                    picked.add(lesson);
                }
            }
        }
        return picked.stream()
                .sorted(Comparator.comparingInt((AgentLesson lesson) ->
                                lesson.getRecurrenceCount() == null ? 0 : lesson.getRecurrenceCount()).reversed()
                        .thenComparing(AgentLesson::getLastSeenAt, Comparator.reverseOrder()))
                .limit(Math.max(1, limit))
                .toList();
    }

    private AgentLesson findSimilarLesson(String category, String whatHappened, String correction) {
        String candidate = whatHappened + " " + correction;
        for (AgentLesson lesson : lessonRepository.findByCategoryAndStatusInOrderByLastSeenAtDesc(category,
                List.of(AgentLesson.STATUS_OPEN, AgentLesson.STATUS_IMPROVING))) {
            double similarity = MemoryTextSimilarity.similarity(candidate,
                    lesson.getWhatHappened() + " " + lesson.getCorrection());
            if (similarity >= lessonMergeSimilarity) {
                return lesson;
            }
        }
        return null;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    // ---------------------------------------------------------------- 面板
    @Transactional(readOnly = true)
    public long countOpenCommitments() {
        return commitmentRepository.countByStatus(AgentCommitment.STATUS_OPEN);
    }

    @Transactional(readOnly = true)
    public Duration sinceLastEvent() {
        return latestEvent().map(event -> Duration.between(event.getCreatedAt(), LocalDateTime.now())).orElse(null);
    }

    // ---------------------------------------------------------------- 内部

    private AgentSelfBlock newBlock(String blockType) {
        AgentSelfBlock block = new AgentSelfBlock();
        block.setBlockType(blockType);
        block.setLabel(blockType.toLowerCase());
        block.setCharLimit(defaultBlockCharLimit);
        block.setVersion(1);
        block.setCreatedAt(LocalDateTime.now());
        block.setUpdatedAt(LocalDateTime.now());
        block.setDescription(switch (blockType) {
            case AgentSelfBlock.TYPE_PERSONA -> "我是谁：稳定的人设与说话方式";
            case AgentSelfBlock.TYPE_TASK -> "我现在在做的事（自己的目标，不是用户的任务）";
            case AgentSelfBlock.TYPE_PROJECT -> "我长期在做的一个东西";
            case AgentSelfBlock.TYPE_STANCE -> "我一贯的样子（程序按证据提升，不是我随手写的）";
            default -> "随手记";
        });
        return block;
    }

    private AgentSelfEvent appendEvent(String kind, String content, String evidence, String topic, String stance,
                                       int importance) {
        requireEvidence(evidence);
        AgentSelfEvent event = new AgentSelfEvent();
        event.setKind(kind);
        event.setContent(abbreviate(content));
        event.setEvidence(evidence.trim());
        event.setTopic(topic);
        event.setStance(stance);
        event.setImportance(importance);
        event.setCreatedAt(LocalDateTime.now());
        return eventRepository.save(event);
    }

    /**
     * 证据校验：`conv:123` 必须能在对话记录里查到，`event:45` 必须在事件里查到；
     * 多个用逗号分隔，**至少一条成立**才放行。
     */
    private void requireEvidence(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("必须带证据（evidence）：引用真实的对话记录 conv:<id> 或事件 event:<id>");
        }
        boolean any = false;
        StringBuilder invalid = new StringBuilder();
        for (String raw : evidence.split("[,，;；\\s]+")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith(EVIDENCE_CONVERSATION)) {
                Long id = parseId(token.substring(EVIDENCE_CONVERSATION.length()));
                if (id != null && conversationMemoryRepository.existsById(id)) {
                    any = true;
                    continue;
                }
            } else if (token.startsWith(EVIDENCE_EVENT)) {
                Long id = parseId(token.substring(EVIDENCE_EVENT.length()));
                if (id != null && eventRepository.existsById(id)) {
                    any = true;
                    continue;
                }
            }
            invalid.append(invalid.length() == 0 ? "" : "、").append(token);
        }
        if (!any) {
            throw new IllegalArgumentException("证据对不上任何真实记录：" + invalid
                    + "（格式：conv:<对话id> 或 event:<事件id>）");
        }
    }

    private Long parseId(String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 499) + "…";
    }

    private String tailLines(String text, int keepChars) {
        String[] lines = text.split("\n");
        StringBuilder builder = new StringBuilder();
        for (int index = lines.length - 1; index >= 0; index--) {
            if (builder.length() + lines[index].length() + 1 > keepChars) {
                break;
            }
            builder.insert(0, lines[index] + "\n");
        }
        return builder.toString().trim();
    }
}
