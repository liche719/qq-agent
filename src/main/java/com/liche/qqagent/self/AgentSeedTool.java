package com.liche.qqagent.self;

import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 「念头」的工具（2026-10-20 加）：记一个、关一个。
 *
 * <p>为什么单独给两个工具：它是**活动之间的接头**。刷视频时想到什么记一条，
 * 下一轮研究会看到它；研究出的新疑点再记一条，下一轮可能引导它去定向找视频。
 * 没有这两个工具，"相辅相成"就只是提示词里的一句空话。
 *
 * <p>只在**它自己的时间**里下发（前缀 {@code selfSeed} 已加进 {@code SelfToolVisibilityRule}）。
 */
@Component
@ConditionalOnProperty(name = "memory.self-enabled", havingValue = "true", matchIfMissing = true)
public class AgentSeedTool implements com.liche.qqagent.tool.AgentToolProvider {

    private final AgentSeedService seeds;

    public AgentSeedTool(AgentSeedService seeds) {
        this.seeds = seeds;
    }

    @Tool(value = "记下一个你自己的念头：一句话，你刚想到「我想搞清楚什么」或者「我想去干点什么」。"
            + "比如刷到一个视频觉得有意思、或者读资料时冒出一个还没验证的疑点。"
            + "**轻一点**：这不是开方向（那是 selfQuestChoose），只是一句待办的想法，写清来源；"
            + "以后任何一轮（研究、刷视频、随便看看）都能接着它往下走。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult selfSeedNote(String content, String source) {
        try {
            AgentSeed seed = seeds.note(content, source);
            return ToolBusinessResult.success("记下了 #" + seed.getId() + "：「" + seed.getContent() + "」"
                    + "（现在一共 " + seeds.openCount() + " 个没消化的念头）");
        } catch (IllegalArgumentException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }

    @Tool(value = "关掉一个念头：搞清了（DONE）／不做了（DROPPED）／已经升格成方向了（QUEST，同时把 questId 填上）。"
            + "一个念头一直挂着不关，会让下一轮总被它拽回去，所以想清楚了就关掉。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult selfSeedClose(Long seedId, String status, Long questId) {
        if (seedId == null || seedId <= 0) {
            return ToolBusinessResult.failure("没给念头编号（selfSeed 里能看到 #号）。");
        }
        try {
            AgentSeed seed = seeds.close(seedId, status, questId);
            return ToolBusinessResult.success("念头 #" + seed.getId() + " 收口了：" + seed.getStatus());
        } catch (IllegalArgumentException exception) {
            return ToolBusinessResult.failure(exception.getMessage());
        }
    }
}


