package com.liche.wechatagent.self;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 自主模块的**三期领域②：它自己的想法**——方向、笔记、作业记录，以及它的「口」。
 *
 * <p>这一侧的重心是它自己（"甚至没有我的也可以"）：方向是它自己选的（{@link #openQuest}），
 * 笔记是它自己查证后写的（{@link #addQuestNote}），撤回说明它在核对（{@link #retractQuestNote}），
 * 作业记录是"它自己的时间"的账（{@link #startQuestRun}/{@link #finishQuestRun}）。
 * 「口」在这里是因为它们同属这一侧：想说什么、为什么想说，都挂在方向上（{@link #wantToSay}）。
 *
 * <p>为什么这几件事放一个数据层：它们共享同一套"同时只有一个方向 / 笔记标尺 / 预算账"的约束，
 * 拆开只会让约束散掉；跑作业的**流程**在 {@link SelfQuestService}，说话的**流程**在
 * {@link SelfSpeakService}。
 */
@Service
public class SelfQuestStore {

    private static final Logger log = LoggerFactory.getLogger(SelfQuestStore.class);

    private final AgentQuestRepository questRepository;
    private final AgentQuestNoteRepository questNoteRepository;
    private final AgentQuestRunRepository questRunRepository;
    private final AgentSelfUtteranceRepository utteranceRepository;
    private final SelfCoreService core;
    private final int maxActiveQuests;

    public SelfQuestStore(AgentQuestRepository questRepository,
                          AgentQuestNoteRepository questNoteRepository,
                          AgentQuestRunRepository questRunRepository,
                          AgentSelfUtteranceRepository utteranceRepository,
                          SelfCoreService core,
                          @Value("${memory.self-max-active-quests:2}") int maxActiveQuests) {
        this.questRepository = questRepository;
        this.questNoteRepository = questNoteRepository;
        this.questRunRepository = questRunRepository;
        this.utteranceRepository = utteranceRepository;
        this.core = core;
        this.maxActiveQuests = Math.max(1, maxActiveQuests);
    }

    // ---------------------------------------------------------------- 方向

    /**
     * 它现在在做的方向（同时最多一个 {@code ACTIVE}）。
     *
     * <p>为什么只能有一个：稀缺才有取舍（spec §9，和"倾向 ≤5"同理）。想开新的，旧的自动关掉。
     */
    @Transactional(readOnly = true)
    public Optional<AgentQuest> activeQuest() {
        return questRepository.findFirstByStatusOrderByUpdatedAtDesc(AgentQuest.STATUS_ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<AgentQuest> quest(Long questId) {
        return questId == null ? Optional.empty() : questRepository.findById(questId);
    }

    @Transactional(readOnly = true)
    public List<AgentQuest> quests() {
        return questRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<AgentQuestNote> questNotes(Long questId, int limit) {
        return questNoteRepository.findByQuestIdOrderByCreatedAtDesc(questId).stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * 开一个属于它自己的方向。
     *
     * <p>已经有一个 {@code ACTIVE} 时**自动关掉它**并留痕：换方向是它的自由，
     * 但"同时只有一个"这件事由程序保证，不靠它自觉。{@code why} 必填——
     * 偏好是**稀缺下的选择模式被记录下来**（§7），不写理由的选题就没有观察价值。
     */
    @Transactional
    public AgentQuest openQuest(String title, String why, String nextStep, String evidence) {
        core.requireEvidence(evidence);
        if (SelfText.isBlank(title) || SelfText.isBlank(why)) {
            throw new IllegalArgumentException("题目和「为什么选这个」都必须写：不写理由的选题看不出偏好");
        }
        LocalDateTime now = LocalDateTime.now();
        // 同时开着的方向有上限（§9 写的是 1–2 个：稀缺才有取舍，但也没必要只许做一件事）。
        // 超了就**关掉最旧的**——换方向是它的自由，可"同时几个"由程序保证，不靠它自觉。
        List<AgentQuest> active = questRepository.findByStatusOrderByUpdatedAtDesc(AgentQuest.STATUS_ACTIVE);
        for (int index = maxActiveQuests - 1; index < active.size(); index++) {
            AgentQuest oldest = active.get(index);
            oldest.setStatus(AgentQuest.STATUS_CLOSED);
            oldest.setClosedAt(now);
            oldest.setUpdatedAt(now);
            oldest.setEvidence(evidence.trim());
            questRepository.save(oldest);
            log.info("领域 #{}「{}」被新方向顶掉，自动关闭", oldest.getId(), oldest.getTitle());
        }
        AgentQuest quest = new AgentQuest();
        quest.setTitle(SelfText.clip(title, 200));
        quest.setWhy(SelfText.clip(why, 600));
        quest.setNextStep(SelfText.isBlank(nextStep) ? null : SelfText.clip(nextStep, 600));
        quest.setStatus(AgentQuest.STATUS_ACTIVE);
        quest.setCreatedAt(now);
        quest.setUpdatedAt(now);
        quest.setEvidence(evidence.trim());
        AgentQuest saved = questRepository.save(quest);
        core.appendEvent(AgentSelfEvent.KIND_QUEST_OPENED,
                "开了个自己的方向：「" + saved.getTitle() + "」——" + SelfText.clipLine(saved.getWhy(), 500),
                evidence, saved.getTitle(), null, 3);
        return saved;
    }

    /** 记下它自己写的"下一步"（会注入到它自己的上下文，所以短） */
    @Transactional
    public AgentQuest updateQuestStep(Long questId, String nextStep, String evidence) {
        core.requireEvidence(evidence);
        AgentQuest quest = requireQuest(questId);
        LocalDateTime now = LocalDateTime.now();
        quest.setNextStep(SelfText.isBlank(nextStep) ? null : SelfText.clip(nextStep, 600));
        quest.setStepCount((quest.getStepCount() == null ? 0 : quest.getStepCount()) + 1);
        quest.setUpdatedAt(now);
        quest.setEvidence(evidence.trim());
        AgentQuest saved = questRepository.save(quest);
        core.appendEvent(AgentSelfEvent.KIND_QUEST_STEP,
                "下一步：" + (saved.getNextStep() == null ? "（还没想好）" : saved.getNextStep()),
                evidence, saved.getTitle(), null, 2);
        return saved;
    }

    /** 收掉一个方向：不是失败，是"不做了"（允许失败、允许放弃，都要留痕——§9 第 5 条）。 */
    @Transactional
    public AgentQuest closeQuest(Long questId, String reason, String evidence) {
        core.requireEvidence(evidence);
        AgentQuest quest = requireQuest(questId);
        LocalDateTime now = LocalDateTime.now();
        quest.setStatus(AgentQuest.STATUS_CLOSED);
        quest.setClosedAt(now);
        quest.setUpdatedAt(now);
        quest.setEvidence(evidence.trim());
        if (!SelfText.isBlank(reason)) {
            quest.setNextStep(SelfText.clip("已收掉：" + reason, 600));
        }
        AgentQuest saved = questRepository.save(quest);
        core.appendEvent(AgentSelfEvent.KIND_QUEST_CLOSED,
                "收了自己的方向「" + saved.getTitle() + "」：" + (SelfText.isBlank(reason) ? "不做了" : reason),
                evidence, saved.getTitle(), null, 2);
        return saved;
    }

    // ---------------------------------------------------------------- 笔记

    /**
     * 写一条笔记（§9.3 的标尺就在这张表上）。
     *
     * <p>{@code sourceUrl} 允许为空，但面板按"有没有来源"分开计数——因为领域最容易退化成
     * **资料搬运**，而带来源是它跟搬运之间唯一可检验的分界。
     */
    @Transactional
    public AgentQuestNote addQuestNote(Long questId, String content, String sourceUrl, String sourceTitle,
                                       String evidence) {
        core.requireEvidence(evidence);
        if (SelfText.isBlank(content)) {
            throw new IllegalArgumentException("笔记内容不能为空");
        }
        AgentQuest quest = requireQuest(questId);
        LocalDateTime now = LocalDateTime.now();
        AgentQuestNote note = new AgentQuestNote();
        note.setQuestId(quest.getId());
        note.setContent(SelfText.clip(content, 2000));
        note.setSourceUrl(SelfText.isBlank(sourceUrl) ? null : SelfText.clip(sourceUrl, 1000));
        note.setSourceTitle(SelfText.isBlank(sourceTitle) ? null : SelfText.clip(sourceTitle, 300));
        note.setEvidence(evidence.trim());
        note.setCreatedAt(now);
        AgentQuestNote saved = questNoteRepository.save(note);
        quest.setNoteCount((quest.getNoteCount() == null ? 0 : quest.getNoteCount()) + 1);
        quest.setUpdatedAt(now);
        questRepository.save(quest);
        core.appendEvent(AgentSelfEvent.KIND_QUEST_NOTE, "「" + quest.getTitle() + "」记了一条："
                + SelfText.clipLine(saved.getContent(), 500), evidence, quest.getTitle(), null, 2);
        return saved;
    }

    /**
     * 撤回自己写过的结论（过时了）。
     *
     * <p>**保留原文**：撤回不是删除，撤回比例本身就是"它在核对"的证据（§9.3）。
     */
    @Transactional
    public AgentQuestNote retractQuestNote(Long noteId, String reason, String evidence) {
        core.requireEvidence(evidence);
        AgentQuestNote note = requireQuestNote(noteId);
        if (note.isRetracted()) {
            throw new IllegalArgumentException("那条笔记已经撤回过了：" + noteId);
        }
        LocalDateTime now = LocalDateTime.now();
        note.setRetractedAt(now);
        note.setRetractReason(SelfText.isBlank(reason) ? "过时了" : SelfText.clip(reason, 300));
        questNoteRepository.save(note);
        questRepository.findById(note.getQuestId()).ifPresent(quest -> {
            quest.setRetractCount((quest.getRetractCount() == null ? 0 : quest.getRetractCount()) + 1);
            quest.setUpdatedAt(now);
            questRepository.save(quest);
            core.appendEvent(AgentSelfEvent.KIND_QUEST_RETRACT, "「" + quest.getTitle() + "」撤回了一条旧结论："
                    + note.getRetractReason(), evidence, quest.getTitle(), null, 2);
        });
        return note;
    }

    /** 标尺用的全量笔记（跨方向，按时间倒序） */
    @Transactional(readOnly = true)
    public List<AgentQuestNote> recentQuestNotes(LocalDateTime since) {
        return questNoteRepository.findByCreatedAtAfterOrderByCreatedAtDesc(since);
    }

    // ---------------------------------------------------------------- 作业记录与预算

    /**
     * 开一次"自己的时间"的作业（**程序写**，不是模型写），并返回它的 id。
     *
     * <p>为什么先建行：作业里所有写入都要带证据，而它自己的会话里没有对话行可引用——
     * 这条记录的 id（{@code run:<id>}）就是**本次作业的锚点证据**，
     * 否则"第一次开方向"永远没有合法证据可用（坑 64 的死锁原样重演）。
     */
    @Transactional
    public AgentQuestRun startQuestRun(Long questId, BigDecimal budgetYuan, boolean extended) {
        AgentQuestRun run = new AgentQuestRun();
        run.setQuestId(questId);
        run.setStatus(AgentQuestRun.STATUS_RUNNING);
        run.setCreatedAt(LocalDateTime.now());
        run.setBudgetYuan(budgetYuan == null ? BigDecimal.ZERO : budgetYuan);
        run.setExtended(extended);
        // 第一次跑的时候还没有方向，questId 是 null —— findById(null) 会直接抛，不能靠 ifPresent 兜
        copyQuestCounters(questId, run);
        return questRunRepository.save(run);
    }

    /**
     * 结算这次作业：状态、它自己写下的东西、以及成本（坑 60：思考 token 也算，别只记正文）。
     */
    @Transactional
    public AgentQuestRun finishQuestRun(AgentQuestRun run, String status, String reason, String summary,
                                        RunCost cost) {
        if (run == null) {
            return null;
        }
        RunCost settled = cost == null ? RunCost.ZERO : cost;
        run.setStatus(status);
        run.setReason(SelfText.isBlank(reason) ? null : SelfText.clip(reason, 500));
        run.setSummary(SelfText.isBlank(summary) ? null : SelfText.clip(summary, 2000));
        run.setPromptTokens(Math.max(0, settled.promptTokens()));
        run.setCompletionTokens(Math.max(0, settled.completionTokens()));
        run.setDurationMs(Math.max(0, settled.durationMs()));
        run.setCostYuan(settled.yuan() == null ? BigDecimal.ZERO : settled.yuan());
        run.setCacheHitTokens(Math.max(0, settled.cacheHitTokens()));
        run.setCacheMissTokens(Math.max(0, settled.cacheMissTokens()));
        // 计数在**结算时**重新取：作业开始时那份是"开工前"的，面板上会变成
        // "这一轮写了 0 条笔记"而它其实写了一条（实测踩到）。
        copyQuestCounters(run.getQuestId(), run);
        return questRunRepository.save(run);
    }

    /** 一次作业的成本账（钱按 cache 命中/未命中分开记，才能和账单对得上） */
    public record RunCost(int promptTokens, int completionTokens, int durationMs,
                          BigDecimal yuan, long cacheHitTokens, long cacheMissTokens) {

        public static final RunCost ZERO = new RunCost(0, 0, 0, BigDecimal.ZERO, 0, 0);
    }

    /** 今天在"它自己的时间"上花了多少元——预算闸的判据（**落库算**，重启不丢账） */
    @Transactional(readOnly = true)
    public BigDecimal questCostToday() {
        BigDecimal sum = questRunRepository.sumCostSince(LocalDate.now().atStartOfDay());
        return sum == null ? BigDecimal.ZERO : sum;
    }

    @Transactional(readOnly = true)
    public List<AgentQuestRun> recentQuestRuns(int limit) {
        return questRunRepository.findAll(PageRequest.of(0, Math.max(1, limit),
                Sort.by(Sort.Direction.DESC, "id"))).getContent();
    }

    @Transactional(readOnly = true)
    public Optional<AgentQuestRun> lastQuestRun() {
        return Optional.ofNullable(questRunRepository.findFirstByOrderByCreatedAtDesc());
    }

    // ---------------------------------------------------------------- 触发判据（它自己那一侧）

    /**
     * 自上次"它自己的时间"以来，它自己事件的兴趣累积。
     *
     * <p>这是**触发下一次作业**的判据（不是"到点了"）：它自己的事在推进（新笔记、新一步、新判断），
     * 攒够了就说明"这里还有东西可弄"。
     */
    @Transactional(readOnly = true)
    public int interestSinceLastQuest() {
        LocalDateTime since = lastQuestRun().map(AgentQuestRun::getCreatedAt)
                .orElse(LocalDateTime.now().minusDays(2));
        return core.eventsSince(since).stream()
                .filter(event -> !AgentSelfEvent.KIND_REFLECT.equals(event.getKind()))
                .mapToInt(event -> event.getImportance() == null ? 0 : event.getImportance())
                .sum();
    }

    /** 距上次"它自己的时间"多久了（null = 还从没动过） */
    @Transactional(readOnly = true)
    public Duration sinceLastQuest() {
        return lastQuestRun()
                .map(run -> Duration.between(run.getCreatedAt(), LocalDateTime.now()))
                .orElse(null);
    }

    /**
     * 它今天说了"先到这"吗。
     *
     * <p>时间归它自己的另一半：**额度够不代表它必须动**。一天的额度是上限，不是任务；
     * 它可以今天用满、也可以今天一次都不动（后者靠 {@link #restForToday} 明确表态）。
     */
    @Transactional(readOnly = true)
    public boolean restedToday() {
        return core.eventsSince(LocalDate.now().atStartOfDay()).stream()
                .anyMatch(event -> AgentSelfEvent.KIND_QUEST_REST.equals(event.getKind()));
    }

    /** 它自己决定"今天先到这"（写一条事件，今天剩下的机会就不再叫它）。 */
    @Transactional
    public AgentSelfEvent restForToday(String reason, String evidence) {
        return core.appendEvent(AgentSelfEvent.KIND_QUEST_REST,
                SelfText.isBlank(reason) ? "今天先到这" : "今天先到这：" + reason, evidence, null, null, 1);
    }

    /**
     * 手上还有没有没结的事：欠着的承诺、或自己方向里还写着"下一步"。
     *
     * <p>闲置触发的**第二个条件**——只有"很久没动"还不够，还得"有料可想"，
     * 否则就是空转烧钱（spec §8 的 open loop：没做完的事会被反复想起）。
     */
    @Transactional(readOnly = true)
    public boolean hasOpenLoops() {
        if (core.countOpenCommitments() > 0) {
            return true;
        }
        return activeQuest()
                .map(quest -> quest.getNextStep() != null && !quest.getNextStep().isBlank())
                .orElse(false);
    }

    // ---------------------------------------------------------------- 它的「口」

    /**
     * 记下它想说、但**现在不说**的一句话。
     *
     * <p>这是"它有自己的表达"的第三种状态：既不憋着（等于没有表达），也不打扰机主（用户明确不要）。
     * {@code why} 必填——面板上只看句子看不出它在想什么，"为什么想说"才是有信息量的那一半，
     * 也是以后决定要不要开口时唯一的判据来源（**它自己想说的**，不是"对机主有没有用"）。
     */
    @Transactional
    public AgentSelfUtterance wantToSay(String content, String why, Long questId, String evidence) {
        core.requireEvidence(evidence);
        if (SelfText.isBlank(content) || SelfText.isBlank(why)) {
            throw new IllegalArgumentException("想说什么、以及为什么想说，都要写（只写句子的话，看不出你在想什么）");
        }
        AgentSelfUtterance utterance = new AgentSelfUtterance();
        utterance.setContent(SelfText.clip(content, 1000));
        utterance.setWhy(SelfText.clip(why, 500));
        utterance.setQuestId(questId);
        utterance.setStatus(AgentSelfUtterance.STATUS_PENDING);
        utterance.setEvidence(evidence.trim());
        utterance.setCreatedAt(LocalDateTime.now());
        return utteranceRepository.save(utterance);
    }

    @Transactional(readOnly = true)
    public List<AgentSelfUtterance> recentUtterances(int limit) {
        return utteranceRepository.findAllByOrderByCreatedAtDesc().stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    /** 攒着还没说的里面最早那条（先想先说的先发） */
    @Transactional(readOnly = true)
    public Optional<AgentSelfUtterance> oldestPendingUtterance() {
        return utteranceRepository.findFirstByStatusOrderByCreatedAtAsc(AgentSelfUtterance.STATUS_PENDING);
    }

    /** 今天已经跟他说出去几条（额度账：**发出去才算**） */
    @Transactional(readOnly = true)
    public long countUtterancesSentToday() {
        return utteranceRepository.countByStatusAndSentAtAfter(AgentSelfUtterance.STATUS_SENT,
                LocalDate.now().atStartOfDay());
    }

    @Transactional
    public AgentSelfUtterance markUtteranceSent(AgentSelfUtterance utterance) {
        utterance.setStatus(AgentSelfUtterance.STATUS_SENT);
        utterance.setSentAt(LocalDateTime.now());
        return utteranceRepository.save(utterance);
    }

    /** 被闸拦下（额度用完、通道没送出去）——留着记录，面板上能看见"它想说但没说出来" */
    @Transactional
    public AgentSelfUtterance markUtteranceSuppressed(AgentSelfUtterance utterance) {
        utterance.setStatus(AgentSelfUtterance.STATUS_SUPPRESSED);
        return utteranceRepository.save(utterance);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 取方向 / 笔记：id 为空或查不到都当"找不到"。
     *
     * <p>为什么不直接 {@code findById}：模型（或第一次跑的作业）可能给 null，
     * 而 {@code findById(null)} 抛的是 {@code InvalidDataAccessApiUsageException}，
     * 会一路冒到面板变成一个没有信息量的"出错了"。
     */
    private AgentQuest requireQuest(Long questId) {
        if (questId == null) {
            throw new IllegalArgumentException("没给方向 id");
        }
        return questRepository.findById(questId).orElseThrow(
                () -> new IllegalArgumentException("找不到那个方向：" + questId));
    }

    private AgentQuestNote requireQuestNote(Long noteId) {
        if (noteId == null) {
            throw new IllegalArgumentException("没给笔记 id");
        }
        return questNoteRepository.findById(noteId).orElseThrow(
                () -> new IllegalArgumentException("找不到那条笔记：" + noteId));
    }

    /** 把方向上"到现在为止写了多少"抄进作业记录（开工与结算各取一次，见 finishQuestRun 的注释）。 */
    private void copyQuestCounters(Long questId, AgentQuestRun run) {
        if (questId == null) {
            return;
        }
        questRepository.findById(questId).ifPresent(quest -> {
            run.setStepCount(SelfText.nz(quest.getStepCount()));
            run.setNoteCount(SelfText.nz(quest.getNoteCount()));
            run.setRetractCount(SelfText.nz(quest.getRetractCount()));
        });
    }
}
