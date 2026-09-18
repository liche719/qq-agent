package com.liche.wechatagent.memory;

import com.liche.wechatagent.config.EmbeddingClient;
import com.liche.wechatagent.tool.AgentToolProvider;
import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 「翻旧账」工具（2026-09-18，P3）：按语义搜**原始对话**（用户说过 / 助手回过的话）。
 *
 * <p>为什么要它：注入的记忆是"被挑过的摘要与事实"，**原始一轮话**以前只有系统按字面词兜底捞一点
 * （那条路对无关问题也会塞满 1500 字，已经删掉）。现在模型可以自己按语义搜历史对话——
 * "上次你说的那个 X 是什么""我们之前是不是聊过 Y"这类问题有据可查。
 *
 * <p>只读、无副作用（{@link ToolExecutionClass#FAST}）。工具描述写得啰嗦一点，因为**模型得知道它存在**。
 */
@Component
public class ConversationSearchTool implements AgentToolProvider {

    /** 一次给模型看的总字数上限（只截，不改写） */
    private static final int TOTAL_CHARS = 1800;
    /** 单条片段上限 */
    private static final int ITEM_CHARS = 300;

    private final ConversationMemoryService conversationMemoryService;
    private final EmbeddingClient embeddingClient;
    private final ToolStatusService statusService;
    private final int limit;
    private final double minScore;

    public ConversationSearchTool(ConversationMemoryService conversationMemoryService,
                                  EmbeddingClient embeddingClient,
                                  ToolStatusService statusService,
                                  @Value("${memory.conversation-tool-recall-limit:8}") int limit,
                                  @Value("${memory.conversation-tool-min-score:0.45}") double minScore) {
        this.conversationMemoryService = conversationMemoryService;
        this.embeddingClient = embeddingClient;
        this.statusService = statusService;
        this.limit = Math.max(1, Math.min(20, limit));
        this.minScore = Math.max(0d, Math.min(0.99d, minScore));
    }

    @Tool(value = "按语义搜「以前的原话」：用户说过什么、助手当时怎么回的。"
            + "用户问「上次/之前/那天我们说的那个…」「你当时是怎么说的」「我们是不是聊过…」这类**要翻旧账**的问题时用它；"
            + "query 用他现在的说法就行（不用一模一样，按意思搜）。"
            + "注意分工：查「会变的信息现在是什么值」用 recallMemoryFacts；"
            + "这里的返回是**原始对话片段**（带时间），可能已经过时或被后来的话推翻——引用时要说清是哪天说的，别当成当前结论。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, allowParallel = true)
    public ToolBusinessResult searchConversation(String query) {
        String userId = requireCurrentUser();
        String text = query == null ? "" : query.trim();
        if (text.isBlank()) {
            return ToolBusinessResult.success("要搜什么？把你想找的那件事说一句就行。");
        }
        if (embeddingClient == null || !embeddingClient.isEnabled()) {
            return ToolBusinessResult.success("现在没法按语义搜历史对话（向量服务不可用），换个说法我再试试别的。");
        }
        float[] vector = embeddingClient.embedOne(text);
        if (vector == null) {
            return ToolBusinessResult.success("这次没搜成（向量服务没返回结果），稍后再试。");
        }
        List<ConversationMemoryService.ConversationHit> hits =
                conversationMemoryService.searchByVector(userId, vector, limit, minScore);
        if (hits.isEmpty()) {
            return ToolBusinessResult.success("没搜到和「" + clip(text, 40) + "」相关的旧对话。"
                    + "要么真的没聊过，要么当时说的话和这个说法差太远——可以换个说法再搜一次。");
        }
        StringBuilder out = new StringBuilder("搜到 ").append(hits.size()).append(" 条相关旧对话（新→旧）：\n");
        for (ConversationMemoryService.ConversationHit hit : hits) {
            ConversationMemory record = hit.record();
            if (record == null) {
                continue;
            }
            String who = switch (record.getRole() == null ? "" : record.getRole().toLowerCase()) {
                case "assistant" -> "我";
                case "system" -> "工具记录";
                default -> "你";
            };
            String when = record.getCreatedAt() == null ? "-"
                    : record.getCreatedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            String line = "· [" + when + "] " + who + "：" + clip(record.getContent(), ITEM_CHARS);
            if (out.length() + line.length() > TOTAL_CHARS) {
                break;
            }
            out.append(line).append('\n');
        }
        return ToolBusinessResult.success(out.toString().trim());
    }

    private String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replace('\n', ' ').trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, Math.max(1, max - 1)) + "…";
    }

    private String requireCurrentUser() {
        String userId = statusService.currentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
        return userId;
    }
}
