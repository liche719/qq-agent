package com.liche.wechatagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把一条最终回复拆成几个气泡（2026-10-21 用户要求）。
 *
 * <p>用户的原话：<i>「笨鱼在任何时候都可以分多条消息回复，可以适当分开，这样更像人更合理，
 * 比如一个气泡就一两句话」</i>。所以这里做两件事：**按长度/段落自动切**，
 * 以及支持模型自己用 {@code |||} 显式指定断点（它想怎么分就怎么分）。
 *
 * <p><b>三条硬约束（别在这里想当然）</b>：
 * <ol>
 *   <li>QQ 对同一条用户消息只允许被动回复 5 次（{@code msg_seq} 1~5）。途中播报
 *       （{@link TurnProgress}）最多占 3 次，最终回复至少留 1 次——所以这里拿到的
 *       {@code budget} 是"还能发几条"，超了就必须并回最后一条，不能硬发。</li>
 *   <li>代码块、表格、工具尾注拆开就废了——检测到这些一律原样发一条。</li>
 *   <li>切出来太短（只剩标点/几个字）要并回前一条，别出现"嗯。"单独一个气泡。</li>
 * </ol>
 */
@Component
public class ReplySplitter {

    private static final Logger log = LoggerFactory.getLogger(ReplySplitter.class);

    /** 模型显式断点：三根竖线，跟正文里的常见符号不冲突 */
    private static final String MARKER = "|||";
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?…；;])");
    private static final Pattern HAS_TABLE = Pattern.compile("(?m)^\\s*\\|.*\\|\\s*$");
    private static final Pattern HAS_CODE_FENCE = Pattern.compile("(?s)```.*```");
    private static final String TOOL_FOOTER = "\n\n> _调用工具：";

    private final boolean enabled;
    private final boolean disableStream;
    private final int maxBubbles;
    private final int maxCharsPerBubble;
    private final int splitThreshold;
    private final int minCharsPerBubble;
    private final long delayMillis;

    public ReplySplitter(@Value("${agent.reply-split.enabled:true}") boolean enabled,
                         @Value("${agent.reply-split.disable-stream:true}") boolean disableStream,
                         @Value("${agent.reply-split.max-bubbles:3}") int maxBubbles,
                         @Value("${agent.reply-split.max-chars-per-bubble:70}") int maxCharsPerBubble,
                         @Value("${agent.reply-split.split-threshold:60}") int splitThreshold,
                         @Value("${agent.reply-split.min-chars-per-bubble:4}") int minCharsPerBubble,
                         @Value("${agent.reply-split.delay-ms:900}") long delayMillis) {
        this.enabled = enabled;
        this.disableStream = disableStream;
        this.maxBubbles = Math.max(1, Math.min(3, maxBubbles));
        this.maxCharsPerBubble = Math.max(40, Math.min(500, maxCharsPerBubble));
        this.splitThreshold = Math.max(0, splitThreshold);
        this.minCharsPerBubble = Math.max(1, minCharsPerBubble);
        this.delayMillis = Math.max(0, Math.min(5000, delayMillis));
    }

    /** 两个气泡之间停多久（毫秒）——太快像刷屏，太慢像卡住 */
    public long delayMillis() {
        return delayMillis;
    }

    /** 拆分是否真的会生效（关掉开关或只允许 1 条时等于没开） */
    public boolean active() {
        return enabled && maxBubbles > 1;
    }

    /**
     * 是否要让通道别走流式。
     *
     * <p>QQ 的流式（{@code stream_messages}）**只能产出一条消息**，跟"分几个气泡"天然冲突：
     * 开了拆分还走流式，等于白拆。所以默认让流式让路；想保留流式就把
     * {@code agent.reply-split.disable-stream} 设成 false（那就只剩一条）。
     */
    public boolean disablesStream() {
        return disableStream;
    }

    /**
     * @param text   最终回复全文
     * @param budget 这一轮还能发几条（含这一条）；≤0 时按 1 处理
     */
    public List<String> split(String text, int budget) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(maxBubbles, budget));
        if (!enabled || limit == 1) {
            return List.of(text);
        }
        // 代码块 / 表格 / 工具尾注：拆开就碎了，原样一条
        if (HAS_CODE_FENCE.matcher(text).find() || HAS_TABLE.matcher(text).find()) {
            return List.of(text);
        }

        // 尾注单独拎出来，永远跟着最后一条（它不属于"要说的话"）
        String body = text;
        String footer = "";
        int footerAt = text.lastIndexOf(TOOL_FOOTER);
        if (footerAt > 0) {
            body = text.substring(0, footerAt);
            footer = text.substring(footerAt);
        }

        List<String> parts = splitBody(body, limit);
        if (parts.size() <= 1) {
            return List.of(text);
        }
        List<String> result = new ArrayList<>(parts);
        int last = result.size() - 1;
        result.set(last, result.get(last) + footer);
        log.debug("回复拆成 {} 个气泡，长度={}", result.size(),
                result.stream().map(String::length).toList());
        return result;
    }

    private List<String> splitBody(String body, int limit) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        // ① 模型自己指定了断点：尊重它的分法，只丢空段、不按长度合并
        if (trimmed.contains(MARKER)) {
            List<String> explicit = new ArrayList<>();
            for (String piece : trimmed.split(Pattern.quote(MARKER))) {
                appendNonEmpty(explicit, piece);
            }
            return packOverflow(explicit, limit, false);
        }
        // ② 太短就不用切，"一两句正好一个气泡"
        if (trimmed.length() <= splitThreshold) {
            return List.of(trimmed);
        }
        // ③ 先按空行分段，再按句末标点切，最后按长度装箱
        List<String> units = new ArrayList<>();
        for (String paragraph : trimmed.split("\\n{2,}")) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            for (String sentence : SENTENCE_END.split(p)) {
                String s = sentence.trim();
                if (!s.isEmpty()) {
                    // 一整句就比气泡上限还长（常见于不加标点的长句）：按逗号/顿号，再不行按长度硬切
                    units.addAll(breakLongUnit(s));
                }
            }
        }
        List<String> bubbles = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            String candidate = current.length() == 0 ? unit : joinText(current.toString(), unit);
            if (current.length() > 0 && candidate.length() > maxCharsPerBubble) {
                appendNonEmpty(bubbles, current.toString());
                current.setLength(0);
                candidate = unit;
            }
            current.setLength(0);
            current.append(candidate);
        }
        appendNonEmpty(bubbles, current.toString());
        return packOverflow(bubbles, limit, true);
    }

    /**
     * 把超出额度的气泡并进最后一条——**宁可少一个气泡，也不能超 QQ 的被动回复次数**。
     *
     * @param mergeShort 是否把过短的碎片并回前一条（自动切分时才需要；模型显式指定的断点不要动）
     */
    private List<String> packOverflow(List<String> bubbles, int limit, boolean mergeShort) {
        List<String> merged = new ArrayList<>();
        for (String bubble : bubbles) {
            String text = bubble == null ? "" : bubble.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (mergeShort && !merged.isEmpty() && text.length() < minCharsPerBubble) {
                int last = merged.size() - 1;
                String joined = joinText(merged.get(last), text);
                // 合并后别把气泡撑得比上限还长，否则等于白切
                if (joined.length() <= maxCharsPerBubble) {
                    merged.set(last, joined);
                    continue;
                }
            }
            merged.add(text);
        }
        if (merged.size() <= limit) {
            return merged;
        }
        List<String> capped = new ArrayList<>(merged.subList(0, limit));
        String tail = capped.get(limit - 1);
        for (int i = limit; i < merged.size(); i++) {
            tail = joinText(tail, merged.get(i));
        }
        capped.set(limit - 1, tail);
        return capped;
    }

    /**
     * 拼接两段话：中文之间直接连（"今天。明天。"），英文/数字之间补一个空格。
     * 之前统一补空格，中文里会出现"。 我看了"这种怪缝。
     */
    private static String joinText(String head, String tail) {
        if (head == null || head.isEmpty()) {
            return tail == null ? "" : tail;
        }
        if (tail == null || tail.isEmpty()) {
            return head;
        }
        char last = head.charAt(head.length() - 1);
        char first = tail.charAt(0);
        if ("。！？!?…；;，、：:）)》”’".indexOf(last) >= 0) {
            return head + tail;
        }
        if (isCjk(last) && isCjk(first)) {
            return head + tail;
        }
        return head + " " + tail;
    }

    private static boolean isCjk(char ch) {
        return (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF)
                || (ch >= 0xF900 && ch <= 0xFAFF) || (ch >= 0xFF00 && ch <= 0xFFEF);
    }

    private static void appendNonEmpty(List<String> target, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) {
            target.add(text);
        }
    }

    /**
     * 把超出单条上限的长句切成几段：优先在逗号/顿号处断（读起来不别扭），
     * 实在找不到标点才按长度硬切——总比一条 300 字的气泡强。
     *
     * <p>中文里 120 字已经是三四句话了，所以阈值就是气泡上限；英文按同样字符数会更长，但够用。
     */
    private List<String> breakLongUnit(String unit) {
        List<String> chunks = new ArrayList<>();
        String rest = unit == null ? "" : unit.trim();
        while (rest.length() > maxCharsPerBubble) {
            int cut = maxCharsPerBubble;
            int comma = Math.max(rest.lastIndexOf('，', cut), rest.lastIndexOf('、', cut));
            if (comma > maxCharsPerBubble / 2) {
                cut = comma + 1;
            }
            String head = rest.substring(0, cut).trim();
            if (!head.isEmpty()) {
                chunks.add(head);
            }
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) {
            chunks.add(rest);
        }
        return chunks;
    }
}
