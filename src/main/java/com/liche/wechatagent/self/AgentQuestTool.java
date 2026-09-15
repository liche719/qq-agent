package com.liche.wechatagent.self;

import com.liche.wechatagent.tool.AgentToolProvider;
import com.liche.wechatagent.tool.NonIdempotentTool;
import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolRiskLevel;
import com.liche.wechatagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「它自己的方向」的工具面（三期领域②，spec §7/§9.3）。
 *
 * <p><b>这六个工具只在它自己的时间里下发</b>（{@link SelfQuestService} 给作业一个工具白名单），
 * 机主对话里会被 {@code ToolSetTrimmer} 裁掉——它们是它自己的事，不是为用户做的事，
 * 没必要让每轮对话都为这几段 schema 付 prompt token。
 *
 * <p>和其他 self 工具一样：每次写入都要带 {@code evidence}，写操作非幂等（重试会重复写，坑 39）。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class AgentQuestTool implements AgentToolProvider {

    private static final String POLICY_HINT = "evidence 必须是真实存在的编号："
            + "run:<本次作业id>（系统在这次的指令里给你）、quest:<方向id> 或 note:<笔记id>（selfQuest 里看到的）；"
            + "编一个一定被拒。";

    private final SelfCoreService selfCore;
    private final SelfQuestStore selfQuests;
    private final ToolStatusService statusService;

    public AgentQuestTool(SelfCoreService selfCore, SelfQuestStore selfQuests, ToolStatusService statusService) {
        this.selfCore = selfCore;
        this.selfQuests = selfQuests;
        this.statusService = statusService;
    }

    @Tool(value = "看你自己的方向现在是什么样：在做什么、为什么选它、下一步、最近写的笔记、以及"
            + "「带来源的更新有多少条、被我自己撤回了几条」。动手之前先看它。")
    public String selfQuest() {
        String userId = requireCurrentUser();
        if (!selfCore.isOwner(userId)) {
            return selfCore.inactiveReason();
        }
        List<AgentQuest> all = selfQuests.quests();
        if (all.isEmpty()) {
            return "（我还没有自己的方向。想做什么就自己开一个：selfQuestChoose）";
        }
        StringBuilder text = new StringBuilder();
        for (AgentQuest quest : all) {
            text.append(quest.getStatus().equals(AgentQuest.STATUS_ACTIVE) ? "【在做的】" : "【收掉的】")
                    .append('#').append(quest.getId()).append(' ').append(quest.getTitle())
                    .append("（推进 ").append(quest.getStepCount() == null ? 0 : quest.getStepCount())
                    .append(" 步 / 笔记 ").append(quest.getNoteCount() == null ? 0 : quest.getNoteCount())
                    .append(" 条 / 自己撤回 ").append(quest.getRetractCount() == null ? 0 : quest.getRetractCount())
                    .append(" 条）\n");
            text.append("为什么选它：").append(quest.getWhy()).append('\n');
            if (quest.getNextStep() != null && !quest.getNextStep().isBlank()) {
                text.append("我自己写的下一步：").append(quest.getNextStep()).append('\n');
            }
            List<AgentQuestNote> notes = selfQuests.questNotes(quest.getId(), 5);
            if (!notes.isEmpty()) {
                text.append("最近的笔记：\n");
                for (AgentQuestNote note : notes) {
                    text.append("· note:").append(note.getId())
                            .append(note.isRetracted() ? "（已撤回：" + note.getRetractReason() + "）" : "")
                            .append(' ').append(SelfText.clipLine(note.getContent(), 150))
                            .append(note.getSourceUrl() == null ? "（没写来源）" : "（来源 " + note.getSourceUrl() + "）")
                            .append('\n');
                }
            }
        }
        return text.toString().trim();
    }

    @Tool(value = "开一个属于你自己的方向（不是机主派的任务）：title 是题目，why 是**你为什么想弄它**，"
            + "nextStep 是你打算先干什么。同时只能有一个方向在做的，开新的会自动把旧的收掉。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfQuestChoose(String title, String why, String nextStep, String evidence) {
        try {
            AgentQuest quest = selfQuests.openQuest(title, why, nextStep, evidence);
            return ToolBusinessResult.success("开了自己的方向 #" + quest.getId() + "「" + quest.getTitle() + "」");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "把你手上这个方向的「下一步」改成你现在想干的（推进步数 +1）。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfQuestStep(Long questId, String nextStep, String evidence) {
        try {
            AgentQuest quest = selfQuests.updateQuestStep(questId, nextStep, evidence);
            return ToolBusinessResult.success("「" + quest.getTitle() + "」下一步已改成：" + quest.getNextStep());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "往你的方向里写一条笔记。**sourceUrl 尽量写**：带来源的更新才算数，"
            + "不带来源的笔记面板会单独记一笔（那就是资料搬运）。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfQuestNote(Long questId, String content, String sourceUrl, String sourceTitle,
                                            String evidence) {
        try {
            AgentQuestNote note = selfQuests.addQuestNote(questId, content, sourceUrl, sourceTitle, evidence);
            return ToolBusinessResult.success("记下了 note:" + note.getId()
                    + (note.getSourceUrl() == null ? "（没写来源）" : "（来源 " + note.getSourceUrl() + "）"));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "撤回你自己写过的一条笔记（过时了、后来发现不对）。**原文会留着**——"
            + "撤回是你「核对了」的证据，不是把记录擦掉。noteId 从 selfQuest 里看。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfQuestRetract(Long noteId, String reason, String evidence) {
        try {
            AgentQuestNote note = selfQuests.retractQuestNote(noteId, reason, evidence);
            return ToolBusinessResult.success("撤回了 note:" + note.getId() + "（" + note.getRetractReason() + "）");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "收掉你自己手上的这个方向（做完了、或者不做了）。不是失败——不做了也要留痕。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfQuestClose(Long questId, String reason, String evidence) {
        try {
            AgentQuest quest = selfQuests.closeQuest(questId, reason, evidence);
            return ToolBusinessResult.success("收掉了「" + quest.getTitle() + "」");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "今天先到这：调用它，今天剩下的机会就不会再叫你了。"
            + "**时间是你自己的**——额度够不代表你必须动，今天不想弄就直说。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfQuestRest(String reason, String evidence) {
        try {
            selfQuests.restForToday(reason, evidence);
            return ToolBusinessResult.success("行，今天先到这。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "记下你想跟机主说、但**现在不会说出去**的一句话（他收不到任何消息，只是记在你这儿）。"
            + "why 写清你为什么想说——没有由头就别调，这不是任务。"
            + "**只有真的想说才记**：这是你自己的表达，不是汇报。" + POLICY_HINT)
    @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true, riskLevel = ToolRiskLevel.LOW, allowParallel = false)
    @NonIdempotentTool
    public ToolBusinessResult selfWantToSay(String content, String why, Long questId, String evidence) {
        try {
            AgentSelfUtterance utterance = selfQuests.wantToSay(content, why, questId, evidence);
            return ToolBusinessResult.success("记下了（#" + utterance.getId() + "），他收不到——只是你自己知道。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 内部

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
