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
public class MaimemoTool {

    private final MaimemoService maimemoService;

    public MaimemoTool(MaimemoService maimemoService) {
        this.maimemoService = maimemoService;
    }

    @Tool(value = "查询用户墨墨背单词的当日进度与待背单词。用户问「我今天背了多少单词」「还剩多少没背」"
            + "「背单词进度怎么样」「今天墨墨的任务做完了吗」这类问题时调用。返回今日已完成/总数/剩余、"
            + "新学与复习数量、学习时长、还没背的单词，以及反复忘记的顽固单词。"
            + "如果返回里说 Token 未配置或已失效，就照实告诉用户去运维面板「背单词」页更新 Token，不要编造数字。")
    @ToolExecutionPolicy(value = ToolExecutionClass.FAST, hasSideEffect = false,
            riskLevel = ToolRiskLevel.LOW, allowParallel = true)
    public ToolBusinessResult getMaimemoStudyProgress() {
        return ToolBusinessResult.success(maimemoService.chatSummary());
    }
}
