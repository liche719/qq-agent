package com.liche.wechatagent.agent;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「这一轮它看到了什么 + 这一轮的调用链」——**进程内的上一轮记录**（spec §12 的上下文检查器）。
 *
 * <p>为什么要有它：先例（Langfuse 类 tracing）的结论是**失败通常藏在中间步骤**，
 * 只看最后那句话诊断不了。而"它这轮为什么这么说"的答案，永远在"它这轮看到了什么"里。
 *
 * <p>刻意做成**只留最近一轮、只存内存、重启即清零**：
 * <ul>
 *   <li>它是**诊断视图**，不是审计（审计走 `agent_self_event` 那种落库的）；</li>
 *   <li>存全量历史会让一个"看一眼"的功能变成又一个要维护的数据面；</li>
 *   <li>面板上要看的本来就是"**现在**它怎么了"（长期曲线是另一块）。</li>
 * </ul>
 *
 * <p>线程安全：对话在同一线程里同步跑完，但定时任务/手动触发的反思可能并发写 → 一律 synchronized。
 */
@Component
public class TurnTraceStore {

    private static final int MAX_USERS = 50;
    private static final int MAX_STEPS = 40;
    private static final int MAX_SECTIONS = 12;
    private static final int MAX_PREVIEW = 300;

    /** 注入上下文里的一段：字数 / 上限（0 = 没设上限）/ 原文预览 */
    public record Section(String label, int chars, int limit, String preview) {
    }

    /** 调用链上的一步：LLM 或工具 */
    public record Step(String kind, String name, long durationMs, boolean ok, String detail) {
    }

    public record Turn(String userId, LocalDateTime startedAt, LocalDateTime finishedAt, int promptChars,
                       List<Section> sections, List<Step> steps) {
    }

    private static final class Pending {
        private final String userId;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private LocalDateTime finishedAt;
        private int promptChars;
        private final List<Section> sections = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();

        private Pending(String userId) {
            this.userId = userId;
        }

        private Turn snapshot() {
            return new Turn(userId, startedAt, finishedAt, promptChars, List.copyOf(sections), List.copyOf(steps));
        }
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Turn> lastByUser = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Turn> eldest) {
            return size() > MAX_USERS;
        }
    });

    /** 一轮开始（每轮对话进来时调一次） */
    public synchronized void startTurn(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        Turn previous = pending.containsKey(userId) ? pending.get(userId).snapshot() : null;
        if (previous != null) {
            lastByUser.put(userId, previous);
        }
        pending.put(userId, new Pending(userId));
    }

    /** 记一段注入上下文；limit = 0 表示这一段没有声明上限 */
    public synchronized void addSection(String userId, String label, String text, int limit) {
        Pending current = pending.get(userId);
        if (current == null || current.sections.size() >= MAX_SECTIONS) {
            return;
        }
        String body = text == null ? "" : text;
        current.sections.add(new Section(label, body.length(), Math.max(0, limit), clip(body)));
    }

    /** 记一段"只有字数、没有正文"的上下文（例如固定的规则块：它不进段落表，但要占分母） */
    public synchronized void addSectionChars(String userId, String label, int chars, int limit) {
        Pending current = pending.get(userId);
        if (current == null || current.sections.size() >= MAX_SECTIONS) {
            return;
        }
        current.sections.add(new Section(label, Math.max(0, chars), Math.max(0, limit), "（固定说明，看提示词源码）"));
    }

    /** 记一次 LLM 调用 */
    public synchronized void addLlmStep(String userId, String scenario, long durationMs, int promptTokens,
                                        int completionTokens, int reasoningTokens) {
        Pending current = pending.get(userId);
        if (current == null || current.steps.size() >= MAX_STEPS) {
            return;
        }
        current.steps.add(new Step("LLM", scenario, durationMs, true,
                "prompt " + promptTokens + " / completion " + completionTokens
                        + (reasoningTokens > 0 ? "（含思考 " + reasoningTokens + "）" : "")));
    }

    /** 记一次工具调用 */
    public synchronized void addToolStep(String userId, String name, long durationMs, boolean ok, String detail) {
        Pending current = pending.get(userId);
        if (current == null || current.steps.size() >= MAX_STEPS) {
            return;
        }
        current.steps.add(new Step("工具", name, durationMs, ok, clip(detail)));
    }

    /**
     * 一轮结束。`promptChars` 传 0 就按各段之和算（占比的分母）——
     * **必须在整轮结束时才调**：调用链上的 LLM/工具步骤是这一轮中途才产生的，
     * 早调一次会把后面的步骤全丢掉（这个坑我踩过，症状是「段落有、调用链空」）。
     */
    public synchronized void finishTurn(String userId, int promptChars) {
        Pending current = pending.remove(userId);
        if (current == null) {
            return;
        }
        int sum = 0;
        for (Section section : current.sections) {
            sum += section.chars();
        }
        current.promptChars = promptChars > 0 ? promptChars : sum;
        current.finishedAt = LocalDateTime.now();
        lastByUser.put(userId, current.snapshot());
    }

    public Optional<Turn> lastTurn(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Turn done = lastByUser.get(userId);
        if (done != null) {
            return Optional.of(done);
        }
        Pending current = pending.get(userId);
        return current == null ? Optional.empty() : Optional.of(current.snapshot());
    }

    /** 面板用：没有指定用户时给最近一轮（机主就一个） */
    public Optional<Turn> latest() {
        synchronized (lastByUser) {
            if (!lastByUser.isEmpty()) {
                Turn newest = null;
                for (Turn turn : lastByUser.values()) {
                    if (newest == null || (turn.finishedAt() != null && newest.finishedAt() != null
                            && turn.finishedAt().isAfter(newest.finishedAt()))) {
                        newest = turn;
                    }
                }
                if (newest != null) {
                    return Optional.of(newest);
                }
            }
        }
        synchronized (this) {
            for (Pending value : pending.values()) {
                return Optional.of(value.snapshot());
            }
        }
        return Optional.empty();
    }

    private static String clip(String text) {
        String flat = text == null ? "" : text.replace('\n', ' ').trim();
        return flat.length() <= MAX_PREVIEW ? flat : flat.substring(0, MAX_PREVIEW - 1) + "…";
    }
}
