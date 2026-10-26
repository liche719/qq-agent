package com.liche.qqagent.tool;

import com.liche.qqagent.agent.TurnProgress;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 让它在干活途中**随口说一句**（2026-10-20 加）。
 *
 * <p>用户的原话：「可以先回复我一句，然后再去找，甚至找的半路途中也可以给我发消息／汇报进度」。
 *
 * <p>为什么不是"把回复拆成几段发"：那样是**排版问题**；这里要的是**时间问题**——
 * 一个要翻十个网页、读几个视频的活儿，十几二十秒里它一声不吭，用户会以为死了。
 * 说一句"行，我去翻翻"，再继续跑，观感完全不同。
 *
 * <p>所以这个工具只在**长活儿**里值得调：要调两个以上工具、或者明显要等十几秒的时候。
 * 一问一答的闲聊、查个天气、记个提醒，本来就快，说了反而啰嗦。
 */
@Component
public class SayTool implements AgentToolProvider {

    @Tool(value = "干活途中有话说时先发一句（不中止任务）。一问一答或闲聊**别用**；一轮最多 2 次。")
    public ToolBusinessResult sayToUser(
            @P("要说给他听的那句话，像平时聊天，别超过 60 字") String text) {
        String outcome = TurnProgress.emit(text);
        boolean ok = outcome.startsWith("已经发出去");
        return ok ? ToolBusinessResult.success(outcome) : ToolBusinessResult.failure(outcome);
    }
}
