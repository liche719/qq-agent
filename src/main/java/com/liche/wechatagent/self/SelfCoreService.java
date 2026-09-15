package com.liche.wechatagent.self;

import com.liche.wechatagent.memory.ConversationMemory;
import com.liche.wechatagent.memory.ConversationMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 自主模块的**内核**：归属、证据门、事件、块、承诺、目标。
 *
 * <p>这一层是"它自己"的最小可写状态，也是另外三块（倾向 / 教训 / 领域）共同依赖的地基：
 * <ul>
 *   <li><b>证据强制</b>：{@link #requireEvidence} 要求 {@code evidence} 能解析成**真实存在**的对话记录、
 *       事件或领域记录，解析不出来就拒——这条直接针对上次"归纳"翻车（模型把两条原文用「；」拼起来当结论），
 *       也是死锁的解药（坑 64：模型看不到行号，所以 {@link #recentConversation} 要递真实编号）。</li>
 *   <li><b>上限</b>：块内容不许超过自己的 {@code char_limit}；未结承诺有条数上限。</li>
 *   <li><b>归属</b>：这一侧属于"它自己"，但**只对机主生效**（fail-closed：没配归属人就整个模块不工作，
 *       避免别人的对话污染它、也避免它把机主的事带到别人那里）。</li>
 * </ul>
 *
 * <p>事件写入（{@link #appendEvent}）也在这里：四块里的每一次改动都要在 {@code agent_self_event}
 * 留一行可回溯的账，账本就该只有一处。
 */
@Service
public class SelfCoreService {

    /** 证据前缀：对话记录 */
    public static final String EVIDENCE_CONVERSATION = "conv:";
    /** 证据前缀：自主模块自己的事件 */
    public static final String EVIDENCE_EVENT = "event:";
    /** 证据前缀：它自己的方向（三期领域②） */
    public static final String EVIDENCE_QUEST = "quest:";
    /** 证据前缀：它自己写的笔记 */
    public static final String EVIDENCE_NOTE = "note:";
    /** 证据前缀：一次"自己的时间"的作业记录 */
    public static final String EVIDENCE_RUN = "run:";

    /**
     * "它自己的时间"里的**身份**（三期领域②）。
     *
     * <p>为什么必须有这个：领域作业跑在它自己的会话里，**不能借机主的 userId**——
     * 那条路会 `userService.getOrCreate` + 写 `conversation_memory` + 调度记忆提取，
     * 等于把它自己在夜里想的事灌进机主的用户档案与长期记忆（第一优先级是"用户长期记忆不丢失"，
     * 污染它就是损坏它）。所以作业带一个独立作用域，工具、记忆、工具事件全部落在这一侧。
     *
     * <p>它必须被 {@link #isOwner} 认成"自己"，否则它自己的工具在自己的时间里全被拒绝。
     */
    public static final String SELF_SCOPE = "__self__";

    private final AgentSelfBlockRepository blockRepository;
    private final AgentSelfEventRepository eventRepository;
    private final AgentCommitmentRepository commitmentRepository;
    private final ConversationMemoryRepository conversationMemoryRepository;
    private final AgentQuestRepository questRepository;
    private final AgentQuestNoteRepository questNoteRepository;
    private final AgentQuestRunRepository questRunRepository;
    private final boolean enabled;
    private final String ownerOpenId;
    private final int defaultBlockCharLimit;
    private final int maxOpenCommitments;

    public SelfCoreService(AgentSelfBlockRepository blockRepository,
                           AgentSelfEventRepository eventRepository,
                           AgentCommitmentRepository commitmentRepository,
                           ConversationMemoryRepository conversationMemoryRepository,
                           AgentQuestRepository questRepository,
                           AgentQuestNoteRepository questNoteRepository,
                           AgentQuestRunRepository questRunRepository,
                           @Value("${memory.self-enabled:true}") boolean enabled,
                           @Value("${memory.self-owner-openid:}") String ownerOpenId,
                           @Value("${memory.self-block-char-limit:1200}") int defaultBlockCharLimit,
                           @Value("${memory.self-max-commitments:20}") int maxOpenCommitments) {
        this.blockRepository = blockRepository;
        this.eventRepository = eventRepository;
        this.commitmentRepository = commitmentRepository;
        this.conversationMemoryRepository = conversationMemoryRepository;
        this.questRepository = questRepository;
        this.questNoteRepository = questNoteRepository;
        this.questRunRepository = questRunRepository;
        this.enabled = enabled;
        this.ownerOpenId = ownerOpenId == null ? "" : ownerOpenId.trim();
        this.defaultBlockCharLimit = Math.max(100, defaultBlockCharLimit);
        this.maxOpenCommitments = Math.max(1, maxOpenCommitments);
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
        if (!isActive() || userId == null) {
            return false;
        }
        // "它自己的时间"里带的是独立作用域（见 SELF_SCOPE），它也是"自己"——
        // 否则它自己的工具在自己的时间里会全被拒绝（换了身份就不认自己，那是 bug 不是安全）。
        return ownerOpenId.equals(userId) || SELF_SCOPE.equals(userId.trim());
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
    public long countOpenCommitments() {
        return commitmentRepository.countByStatus(AgentCommitment.STATUS_OPEN);
    }

    @Transactional(readOnly = true)
    public Duration sinceLastEvent() {
        return latestEvent().map(event -> Duration.between(event.getCreatedAt(), LocalDateTime.now())).orElse(null);
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
                userId, List.of("user", "assistant"), PageRequest.of(0, size));
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
        if (SelfText.isBlank(text)) {
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
        appendEvent(AgentSelfEvent.KIND_NOTE, "更新了「" + blockType + "」：" + SelfText.clipLine(text, 500),
                evidence, null, null, 0);
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
        appendEvent(AgentSelfEvent.KIND_NOTE, "改写了「" + blockType + "」：" + SelfText.clipLine(oldText, 500)
                + " → " + SelfText.clipLine(newText, 500), evidence, null, null, 0);
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

    /** 整块改写（反思流程用；模型侧走 selfAppend/selfReplace）。 */
    @Transactional
    public AgentSelfBlock setBlockValue(String blockType, String value, String evidence) {
        requireEvidence(evidence);
        if (SelfText.isBlank(value)) {
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
                "改写了「" + blockType + "」：" + SelfText.clipLine(current, 500) + " → " + SelfText.clipLine(clipped, 500),
                evidence, null, null, 0);
        return saved;
    }

    /** 立一个自己的目标：写进 TASK 块 + 记一条 GOAL_SET。 */
    @Transactional
    public AgentSelfEvent openGoal(String content, String why, String evidence) {
        requireEvidence(evidence);
        if (SelfText.isBlank(content)) {
            throw new IllegalArgumentException("目标内容不能为空");
        }
        AgentSelfBlock block = block(AgentSelfBlock.TYPE_TASK).orElseGet(() -> newBlock(AgentSelfBlock.TYPE_TASK));
        String line = "· " + content.trim() + (SelfText.isBlank(why) ? "" : "（因为：" + why.trim() + "）");
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
        return appendEvent(AgentSelfEvent.KIND_GOAL_CLOSED,
                "目标收尾：「" + SelfText.clipLine(goal.getContent(), 500) + "」"
                        + (SelfText.isBlank(outcome) ? "" : " → " + outcome.trim()), evidence, null, null, 3);
    }

    /** 立诺 / 做预测。 */
    @Transactional
    public AgentCommitment commit(String content, LocalDateTime dueAt, String evidence) {
        requireEvidence(evidence);
        if (SelfText.isBlank(content)) {
            throw new IllegalArgumentException("承诺内容不能为空");
        }
        long open = commitmentRepository.countByStatus(AgentCommitment.STATUS_OPEN);
        if (open >= maxOpenCommitments) {
            throw new IllegalArgumentException("未结的承诺已经有 " + open + " 条，先把旧的处理掉");
        }
        LocalDateTime now = LocalDateTime.now();
        AgentCommitment commitment = new AgentCommitment();
        commitment.setContent(SelfText.clip(content, 500));
        commitment.setDueAt(dueAt);
        commitment.setStatus(AgentCommitment.STATUS_OPEN);
        commitment.setEvidence(evidence.trim());
        commitment.setCreatedAt(now);
        commitment.setUpdatedAt(now);
        AgentCommitment saved = commitmentRepository.save(commitment);
        appendEvent(AgentSelfEvent.KIND_COMMIT, "立下：" + SelfText.clipLine(content, 500)
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
        appendEvent(AgentSelfEvent.KIND_COMMIT_RESOLVED,
                "「" + SelfText.clipLine(commitment.getContent(), 500) + "」→ " + normalized,
                evidence, null, null, normalized.equals(AgentCommitment.STATUS_KEPT) ? 2 : 5);
        return saved;
    }

    // ---------------------------------------------------------------- 证据与账本（其余三块共用）

    /**
     * 证据校验：`conv:123` 必须能在对话记录里查到，`event:45` 必须在事件里查到；
     * 多个用逗号分隔，**至少一条成立**才放行。
     */
    public void requireEvidence(String evidence) {
        if (SelfText.isBlank(evidence)) {
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
            } else if (token.startsWith(EVIDENCE_QUEST)) {
                // 领域②：它自己的方向 / 笔记 / 作业记录同样是**真实存在的记录**，可以当证据。
                // 没有这三个前缀，"它自己的时间"里第一次写入永远失败（坑 64 的证据死锁原样重演）——
                // 作业的会话里没有它自己的对话行，它拿不到 conv:<id>。
                Long id = parseId(token.substring(EVIDENCE_QUEST.length()));
                if (id != null && questRepository.existsById(id)) {
                    any = true;
                    continue;
                }
            } else if (token.startsWith(EVIDENCE_NOTE)) {
                Long id = parseId(token.substring(EVIDENCE_NOTE.length()));
                if (id != null && questNoteRepository.existsById(id)) {
                    any = true;
                    continue;
                }
            } else if (token.startsWith(EVIDENCE_RUN)) {
                Long id = parseId(token.substring(EVIDENCE_RUN.length()));
                if (id != null && questRunRepository.existsById(id)) {
                    any = true;
                    continue;
                }
            }
            invalid.append(invalid.length() == 0 ? "" : "、").append(token);
        }
        if (!any) {
            throw new IllegalArgumentException("证据对不上任何真实记录：" + invalid
                    + "（格式：conv:<对话id> / event:<事件id> / quest:<方向id> / note:<笔记id> / run:<作业id>）");
        }
    }

    /** 记一条事件（{@code agent_self_event.content} 列宽 500，换行压成一行）——四块共用这一个写入点。 */
    @Transactional
    public AgentSelfEvent appendEvent(String kind, String content, String evidence, String topic, String stance,
                                      int importance) {
        requireEvidence(evidence);
        AgentSelfEvent event = new AgentSelfEvent();
        event.setKind(kind);
        event.setContent(SelfText.clipLine(content, 500));
        event.setEvidence(evidence.trim());
        event.setTopic(topic);
        event.setStance(stance);
        event.setImportance(importance);
        event.setCreatedAt(LocalDateTime.now());
        return eventRepository.save(event);
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

    private Long parseId(String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
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
