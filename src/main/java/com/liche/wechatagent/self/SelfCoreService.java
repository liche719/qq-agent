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
 * 它自己那侧的唯一入口（2026-10-20 **大改**：块与事件合并成一张 {@code agent_self_note}）。
 *
 * <p><b>为什么并</b>：块的当前值和它的历史流水本来是同一件事的两个投影——块每次更新都会写一条事件。
 * 两张表各带一个 repo、各一套方法，工具层因此长出 {@code selfAppend/selfReplace/selfSummarize}
 * 三个"差不多"的东西（实测三者合计只被调用 2 次）。
 *
 * <p><b>为什么还留着 {@link AgentSelfBlock} / {@link AgentSelfEvent} 两个类型</b>：
 * 有 8 个类在读这些字段（面板、注入、倾向、反思）。让它们**一行逻辑都不用改**，
 * 这次大改的风险就只关在这个类里——这是这次能一次改完的前提。
 *
 * <p><b>删掉的东西</b>（不是合并）：目标（{@code goalOpen/goalClose}）与承诺
 * （{@code commit/commitResolve}）两套整块移除——实测 0 次调用、0 行数据，语义已被
 * "开方向 + 写下一步"覆盖。{@code replaceBlock/summarizeBlock/setBlockValue} 也一并去掉：
 * 块实测只有 180 字，一个追加入口就够，等真超长了再加。
 */
@Service
public class SelfCoreService {

    /** 证据前缀：对话记录 */
    public static final String EVIDENCE_CONVERSATION = "conv:";
    /** 证据前缀：自主模块自己的记录（{@code agent_self_note}） */
    public static final String EVIDENCE_EVENT = "event:";
    /** 证据前缀：它自己的方向 */
    public static final String EVIDENCE_QUEST = "quest:";
    /** 证据前缀：它自己写的笔记 */
    public static final String EVIDENCE_NOTE = "note:";
    /** 证据前缀：一次"自己的时间"的作业记录 */
    public static final String EVIDENCE_RUN = "run:";

    /**
     * "它自己的时间"里的**身份**。
     *
     * <p>为什么必须有：领域作业跑在它自己的会话里，**不能借机主的 userId**——
     * 那条路会 `userService.getOrCreate` + 写 `conversation_memory` + 调度记忆提取，
     * 等于把它自己在夜里想的事灌进机主的用户档案。所以作业带一个独立作用域。
     */
    public static final String SELF_SCOPE = "__self__";

    private static final String KIND_BLOCK = AgentSelfNote.KIND_BLOCK;

    private final AgentSelfNoteRepository notes;
    private final ConversationMemoryRepository conversationMemoryRepository;
    private final AgentQuestRepository questRepository;
    private final AgentQuestNoteRepository questNoteRepository;
    private final AgentQuestRunRepository questRunRepository;
    private final boolean enabled;
    private final String ownerOpenId;
    private final int blockCharLimit;

    public SelfCoreService(AgentSelfNoteRepository notes,
                           ConversationMemoryRepository conversationMemoryRepository,
                           AgentQuestRepository questRepository,
                           AgentQuestNoteRepository questNoteRepository,
                           AgentQuestRunRepository questRunRepository,
                           @Value("${memory.self-enabled:true}") boolean enabled,
                           @Value("${memory.self-owner-openid:}") String ownerOpenId,
                           @Value("${memory.self-block-char-limit:1200}") int blockCharLimit) {
        this.notes = notes;
        this.conversationMemoryRepository = conversationMemoryRepository;
        this.questRepository = questRepository;
        this.questNoteRepository = questNoteRepository;
        this.questRunRepository = questRunRepository;
        this.enabled = enabled;
        this.ownerOpenId = ownerOpenId == null ? "" : ownerOpenId.trim();
        this.blockCharLimit = Math.max(100, blockCharLimit);
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
        // "它自己的时间"里带的是独立作用域，它也是"自己"
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

    /** 当前生效的块（每个槽位最多一条） */
    @Transactional(readOnly = true)
    public List<AgentSelfBlock> blocks() {
        return notes.findByKindAndActiveTrueOrderByIdAsc(KIND_BLOCK).stream().map(this::toBlock).toList();
    }

    /**
     * 最近的事件（不含块），**按时间正序**返回（老的在前）——它是"时间线"，
     * 注入到提示词里要顺着读。原来那张表是倒序取的，显示出来是反的。
     */
    @Transactional(readOnly = true)
    public List<AgentSelfEvent> recentEvents(int limit) {
        int size = Math.min(200, Math.max(1, limit));
        List<AgentSelfNote> desc = notes.findByKindNotOrderByIdDesc(KIND_BLOCK, PageRequest.of(0, size));
        return desc.reversed().stream().map(this::toEvent).toList();
    }

    @Transactional(readOnly = true)
    public Optional<AgentSelfEvent> latestEvent() {
        List<AgentSelfNote> latest = notes.findByKindNotOrderByIdDesc(KIND_BLOCK, PageRequest.of(0, 1));
        return latest.isEmpty() ? Optional.empty() : Optional.of(toEvent(latest.get(0)));
    }

    @Transactional(readOnly = true)
    public List<AgentSelfEvent> eventsSince(LocalDateTime since) {
        return since == null ? recentEvents(200)
                : notes.findByKindNotAndCreatedAtAfterOrderByIdAsc(KIND_BLOCK, since).stream()
                .map(this::toEvent).toList();
    }

    @Transactional(readOnly = true)
    public Duration sinceLastEvent() {
        return latestEvent().map(event -> Duration.between(event.getCreatedAt(), LocalDateTime.now())).orElse(null);
    }

    /**
     * 最近的对话记录（带真实 id）——**模型唯一能拿到合法证据编号的来源**。
     *
     * <p>为什么要有：模型看不到 {@code conversation_memory} 的行号，而写入要求
     * {@code conv:<id>} 真实存在 → 没有这个读工具，第一次写入永远拿不到证据（死锁）。
     */
    @Transactional(readOnly = true)
    public List<ConversationMemory> recentConversation(String userId, int limit) {
        int size = Math.min(20, Math.max(1, limit));
        return conversationMemoryRepository.findByUserIdAndRoleInOrderByCreatedAtDesc(
                userId, List.of("user", "assistant"), PageRequest.of(0, size));
    }

    // ---------------------------------------------------------------- 写

    /** 随手记：低门槛，进流水、不进块。 */
    @Transactional
    public AgentSelfEvent note(String kind, String content, String evidence, String topic, String stance) {
        return appendEvent(kind, content, evidence, topic, stance, 0);
    }

    /**
     * 往某个块追加一段；超上限直接拒绝（要求先想清楚写什么，而不是无脑堆）。
     *
     * <p>实现是**插新行 + 旧行标失效**（{@code active=false, superseded_by=新id}），
     * 所以历史自带，不需要另存——这正是原来两张表能合成一张的原因。
     */
    @Transactional
    public AgentSelfBlock appendBlock(String blockType, String text, String evidence) {
        requireEvidence(evidence);
        if (SelfText.isBlank(text)) {
            throw new IllegalArgumentException("内容不能为空");
        }
        AgentSelfNote current = currentBlock(blockType);
        String existing = current == null || current.getContent() == null ? "" : current.getContent();
        String merged = existing.isBlank() ? text.trim() : existing + "\n" + text.trim();
        if (merged.length() > blockCharLimit) {
            throw new IllegalArgumentException("「" + blockType + "」已到上限（" + blockCharLimit
                    + " 字），这次别加了——想清楚要留下哪句，或者先别写");
        }
        AgentSelfNote saved = notes.save(newNote(KIND_BLOCK, blockType, merged, evidence.trim(), null, null, 0));
        supersede(current, saved.getId());
        appendEvent(AgentSelfEvent.KIND_NOTE, "更新了「" + blockType + "」：" + SelfText.clipLine(text, 400),
                evidence, null, null, 0);
        return toBlock(saved);
    }

    /**
     * 记一条记录（唯一写入点）。
     *
     * <p>⚠️ **证据必须指向真实记录**（{@code conv:/event:/quest:/note:/run:}）——
     * 这是针对上次"归纳翻车"（模型把两条原文用「；」拼起来当结论）设的闸门。
     * 顺带一个真实教训：**别把这条校验放进"记账"路径**，记账失败会让护栏静默失效（见坑 73）。
     */
    @Transactional
    public AgentSelfEvent appendEvent(String kind, String content, String evidence, String topic, String stance,
                                      int importance) {
        requireEvidence(evidence);
        AgentSelfNote saved = notes.save(
                newNote(kind, "", content, evidence.trim(), topic, stance, importance));
        return toEvent(saved);
    }

    // ---------------------------------------------------------------- 证据

    /**
     * 证据校验：`conv:123` 必须能在对话记录里查到，`event:45` 必须在它自己的记录里查到；
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
            Long id = null;
            boolean ok = false;
            if (token.startsWith(EVIDENCE_CONVERSATION)) {
                id = parseId(token.substring(EVIDENCE_CONVERSATION.length()));
                ok = id != null && conversationMemoryRepository.existsById(id);
            } else if (token.startsWith(EVIDENCE_EVENT)) {
                id = parseId(token.substring(EVIDENCE_EVENT.length()));
                ok = id != null && notes.existsById(id);
            } else if (token.startsWith(EVIDENCE_QUEST)) {
                // 它自己的方向 / 笔记 / 作业记录同样是真实记录，可以当证据；
                // 没有这三个前缀，"它自己的时间"里第一次写入永远失败（坑 64 的证据死锁）
                id = parseId(token.substring(EVIDENCE_QUEST.length()));
                ok = id != null && questRepository.existsById(id);
            } else if (token.startsWith(EVIDENCE_NOTE)) {
                id = parseId(token.substring(EVIDENCE_NOTE.length()));
                ok = id != null && questNoteRepository.existsById(id);
            } else if (token.startsWith(EVIDENCE_RUN)) {
                id = parseId(token.substring(EVIDENCE_RUN.length()));
                ok = id != null && questRunRepository.existsById(id);
            }
            if (ok) {
                any = true;
                continue;
            }
            invalid.append(invalid.length() == 0 ? "" : "、").append(token);
        }
        if (!any) {
            throw new IllegalArgumentException("证据对不上任何真实记录：" + invalid
                    + "（格式：conv:<对话id> / event:<事件id> / quest:<方向id> / note:<笔记id> / run:<作业id>）");
        }
    }

    // ---------------------------------------------------------------- 内部

    private AgentSelfNote currentBlock(String blockType) {
        List<AgentSelfNote> found = notes.findByKindAndSlotAndActiveTrueOrderByIdDesc(
                KIND_BLOCK, blockType, PageRequest.of(0, 1));
        return found.isEmpty() ? null : found.get(0);
    }

    /** 旧的那条标失效并指回新的（历史自带） */
    private void supersede(AgentSelfNote previous, Long nextId) {
        if (previous == null) {
            return;
        }
        previous.setActive(false);
        previous.setSupersededBy(nextId == null ? 0 : nextId);
        previous.setUpdatedAt(LocalDateTime.now());
        notes.save(previous);
    }

    private AgentSelfNote newNote(String kind, String slot, String content, String evidence,
                                  String topic, String stance, int importance) {
        LocalDateTime now = LocalDateTime.now();
        AgentSelfNote note = new AgentSelfNote();
        note.setKind(kind);
        note.setSlot(slot == null ? "" : slot);
        note.setContent(SelfText.clipLine(content, 4000));
        note.setEvidence(evidence == null ? "" : evidence);
        note.setTopic(topic == null ? "" : SelfText.clipLine(topic, 120));
        note.setStance(stance == null ? "" : SelfText.clipLine(stance, 16));
        note.setImportance(importance);
        note.setActive(true);
        note.setSupersededBy(0);
        note.setCreatedAt(now);
        note.setUpdatedAt(now);
        return note;
    }

    private AgentSelfBlock toBlock(AgentSelfNote note) {
        AgentSelfBlock block = new AgentSelfBlock();
        block.setId(note.getId());
        block.setBlockType(note.getSlot());
        block.setValue(note.getContent());
        block.setLabel(note.getSlot() == null ? "" : note.getSlot().toLowerCase());
        block.setCharLimit(blockCharLimit);
        block.setVersion(1);
        block.setCreatedAt(note.getCreatedAt());
        block.setUpdatedAt(note.getUpdatedAt());
        return block;
    }

    private AgentSelfEvent toEvent(AgentSelfNote note) {
        AgentSelfEvent event = new AgentSelfEvent();
        event.setId(note.getId());
        event.setKind(note.getKind());
        event.setContent(note.getContent());
        event.setEvidence(note.getEvidence());
        event.setTopic(note.getTopic());
        event.setStance(note.getStance());
        event.setImportance(note.getImportance());
        event.setCreatedAt(note.getCreatedAt());
        return event;
    }

    private Long parseId(String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
