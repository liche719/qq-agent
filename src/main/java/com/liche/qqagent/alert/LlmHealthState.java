package com.liche.qqagent.alert;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「模型服务明确拒绝我们」这件事的共享状态（2026-10-15 加）。
 *
 * <p><b>为什么需要它</b>：2026-10-15 上午 DeepSeek 连续返回 503，还夹了一条 402
 * {@code Insufficient Balance}；错误只在日志里滚过去，**用户是靠自己发现"怎么老报错"才知道的**。
 * 余额不足、密钥失效这类问题不会自愈，必须有人去处理，所以要在面板与 QQ 告警里冒出来。
 *
 * <p><b>为什么不做成告警器直接调用</b>：模型层在 {@code config} 包，让它注入 {@code AlertNotifier}
 * 会把"低层调用"和"QQ 通道"绑在一起（{@code AlertNotifier → QqChannel → AgentOrchestrator → AgentLoop}），
 * 容易绕成循环依赖。这里只做**一个无依赖的状态记录点**，由 {@link AlertNotifier} 每轮检查时顺手读取，
 * 既拿到了新问题/恢复的推送（复用它的去重与恢复逻辑），又没有新增调用链。
 *
 * <p>判定很简单：**最近一次"拒绝"之后没有再成功过**，且没过太久（{@link #WINDOW_MS}），就算问题仍在。
 * 一旦模型重新调通，下一次检查就会自动发一条"已恢复"。
 */
@Component
public class LlmHealthState {

    /** 多久没有成功才算"还在故障"——太长了会把早就恢复的问题一直挂着 */
    private static final long WINDOW_MS = 30 * 60_000L;

    private volatile long lastRejectedAt;
    private volatile String lastRejection = "";
    private volatile long lastSuccessAt;

    /** 模型被明确拒绝（401 / 402）时记一笔；detail 只放上游给的原文（会截断） */
    public void recordRejection(int status, String detail) {
        lastRejectedAt = System.currentTimeMillis();
        lastRejection = "HTTP " + status + (detail == null || detail.isBlank() ? "" : "：" + clip(detail));
    }

    /** 任何一次调用成功都算恢复 */
    public void recordSuccess() {
        lastSuccessAt = System.currentTimeMillis();
    }

    /** 给 {@link AlertNotifier} 用：还在故障就返回一个问题；正常时返回空 */
    public Map<String, String> problems() {
        if (lastRejectedAt == 0 || lastSuccessAt > lastRejectedAt) {
            return Map.of();
        }
        if (System.currentTimeMillis() - lastRejectedAt > WINDOW_MS) {
            return Map.of();
        }
        Map<String, String> problems = new LinkedHashMap<>();
        problems.put("llm-rejected", "模型调用被上游拒绝：" + lastRejection
                + "（余额或密钥的问题，去 DeepSeek 控制台看一眼；用户那边只会看到一句提示）");
        return problems;
    }

    private static String clip(String detail) {
        String text = detail.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
}
