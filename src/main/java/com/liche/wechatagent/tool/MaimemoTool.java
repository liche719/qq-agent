package com.liche.wechatagent.tool;

import com.liche.wechatagent.maimemo.MaimemoService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 工具：查询墨墨背单词的今日进度。
 *
 * <p>这里只提供"能力"，**要不要调用完全由模型根据用户那句话的意图决定**（不做任何关键词硬编码）：
 * 工具描述里写清典型说法，模型自己判断「今天背了多少单词」「还剩多少没背」「背单词进度怎么样」是在问这个。
 */
@Component
public class MaimemoTool implements AgentToolProvider {

    private final MaimemoService maimemoService;
    private final ToolStatusService statusService;

    public MaimemoTool(MaimemoService maimemoService, ToolStatusService statusService) {
        this.maimemoService = maimemoService;
        this.statusService = statusService;
    }

    @Tool(value = "查询用户墨墨背单词的当日进度与待背单词。用户问「我今天背了多少单词」「还剩多少没背」"
            + "「背单词进度怎么样」「今天墨墨的任务做完了吗」这类问题时调用。返回今日已完成/总数/剩余、"
            + "新学与复习数量、学习时长、还没背的单词，以及反复忘记的顽固单词。"
            + "如果返回里说 Token 未配置或已失效，就照实告诉用户去运维面板「背单词」页更新 Token，不要编造数字；"
            + "如果说这个墨墨账号不属于当前用户，就照实说明，不要试图绕过。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, hasSideEffect = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = true)
    public ToolBusinessResult getMaimemoStudyProgress() {
        String userId = statusService.currentUserId();
        // 墨墨是单账号接口：数据只属于绑定的那位用户，别人的进度查不到也不该给。
        // 这里必须 fail-closed——没有用户上下文时不能当成"机主本人"把真实学习数据吐出去。
        if (userId == null || userId.isBlank() || !maimemoService.isMaimemoOwner(userId)) {
            return ToolBusinessResult.success("墨墨背单词只绑定了机主本人的账号，我这里没有你的背单词数据。"
                    + "如果你想用自己的墨墨账号，需要在部署侧单独配置一个 Token。");
        }
        return ToolBusinessResult.success(maimemoService.chatSummary());
    }
}
