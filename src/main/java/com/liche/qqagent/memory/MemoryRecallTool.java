package com.liche.qqagent.memory;

import com.liche.qqagent.tool.AgentToolProvider;
import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「翻记忆」工具（2026-11-04）。
 *
 * <p><b>为什么要它</b>：记忆原来每轮自动注入，但从实测看两件事：
 * ① 轻量上下文（画像 + STATE）每轮给是对的，能让它知道"你是谁、最近在干嘛"；
 * ② **重检索（向量 + 时间跨度大的经历）每轮都跑是浪费**——一句"哈哈"也要查 3 秒。
 *
 * <p>所以把重的这部分做成工具，让它按需调用。工具描述里写死触发条件：
 * 他提到「上次/之前/那次/还记得」这类**指代性**问法时，必须查一次再回答，
 * 不能凭印象说"不知道"。
 */
@Component
public class MemoryRecallTool implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallTool.class);

    private final MemoryRetrievalService retrievalService;
    private final com.liche.qqagent.tool.ToolStatusService statusService;

    public MemoryRecallTool(MemoryRetrievalService retrievalService,
                            com.liche.qqagent.tool.ToolStatusService statusService) {
        this.retrievalService = retrievalService;
        this.statusService = statusService;
    }

    @Tool(value = "翻找他以前说过的事/经历。他问「上次那个」「之前说的」「你还记得吗」「那次…」"
            + "这类**指代过去**的问题时，先调这个再回答；**不要凭印象说不知道**。"
            + "query 写你理解的具体关键词（人名、时间、事件都带上）。")
    @ToolExecutionPolicy(ToolExecutionClass.FAST)
    public ToolBusinessResult recallMemory(
            @dev.langchain4j.agent.tool.P("要翻找什么，写成具体关键词") String query) {
        String userId = statusService == null ? null : statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            return ToolBusinessResult.failure("拿不到当前用户，翻不了记忆。");
        }
        if (query == null || query.isBlank()) {
            return ToolBusinessResult.failure("没说要找什么。");
        }
        try {
            var retrieved = retrievalService.retrieve(userId, query, 40, 4000, 40, 5000);
            String core = retrieved.coreSection();
            String work = retrieved.workSection();
            String body = (core == null || core.isBlank() ? "" : core)
                    + (work == null || work.isBlank() || "（暂无）".equals(work) ? "" : "\n" + work);
            if (body.isBlank() || "（暂无）".equals(body.trim())) {
                return ToolBusinessResult.success("翻了一遍，没有相关的记录。可以如实跟他说没找到，"
                        + "或者问清楚是哪个时间点的。");
            }
            return ToolBusinessResult.success("找到这些相关的：\n" + body);
        } catch (RuntimeException exception) {
            log.warn("翻记忆失败：{}", exception.getMessage());
            return ToolBusinessResult.failure("这次没翻到（" + exception.getMessage() + "）。跟他说一声。");
        }
    }
}
