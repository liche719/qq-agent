package com.liche.wechatagent.voice;

import com.liche.wechatagent.self.SelfTurnMark;
import com.liche.wechatagent.tool.AgentToolProvider;
import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 发语音（2026-10-21）—— 把一句话念出来，以 QQ 语音条的形式发给他。
 *
 * <p><b>什么时候用由模型自己判断</b>：工具描述只写语义（"他想听你的声音时"），
 * **代码里不出现任何"发语音 / 语音条"这类关键词判断**——用户换个说法照样能触发，
 * 也不会因为他说了"语音"两个字就误发。
 *
 * <p>为什么不让它把整段回复念一遍：语音是另一条消息，会把同一句话变成两遍；
 * 而且 TTS 按字符计费。所以工具参数就是要念的那一句话，由模型自己挑。
 */
@Component
public class SendVoiceTool implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SendVoiceTool.class);

    private final TtsClient ttsClient;
    private final ToolStatusService statusService;
    /** 它自己主动发语音的每日上限（用户明确要听的不算） */
    private final int selfDailyLimit;
    private final ConcurrentHashMap<String, AtomicInteger> selfUsedToday = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDate> usedDates = new ConcurrentHashMap<>();

    public SendVoiceTool(TtsClient ttsClient,
                         ToolStatusService statusService,
                         @Value("${voice.tts.self-daily-limit:10}") int selfDailyLimit) {
        this.ttsClient = ttsClient;
        this.statusService = statusService;
        this.selfDailyLimit = Math.max(0, selfDailyLimit);
    }

    @Tool(value = "把一句话念出来，以语音的形式发给他（他那边会看到一条语音条）。"
            + "**他想听你的声音**、让你「说一句」「念出来」「发条语音」，或者你觉得这件事用声音说更合适时调用。"
            + "text 是要念出来的那句话：口语、简短（默认不超过 120 字）、不要 emoji、不要链接、不要 markdown；"
            + "**只放要念的内容**，不要把整段文字回复再重复一遍。"
            + "返回是否发出去；没发出去就如实说，不要假装发了。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult sendVoice(String text) {
        if (!ttsClient.enabled()) {
            return ToolBusinessResult.failure("语音能力没配置，发不了。跟他说一声，用文字回。");
        }
        String speakable = plain(speakable(text));
        if (speakable.isEmpty()) {
            return ToolBusinessResult.failure("没有要念的内容。");
        }
        if (speakable.codePointCount(0, speakable.length()) > ttsClient.maxChars()) {
            return ToolBusinessResult.failure("这句话太长了（上限 " + ttsClient.maxChars()
                    + " 字）。挑最要紧的一两句念，剩下的用文字说。");
        }
        if (quotaExceeded()) {
            return ToolBusinessResult.failure("今天你自己主动发的语音到上限了（" + selfDailyLimit
                    + " 条/天）。**他明确要听的这一轮不受影响**——但这一条先别发，用文字回。");
        }

        TtsClient.Result speech = ttsClient.synthesize(speakable);
        if (!speech.ok()) {
            return ToolBusinessResult.failure("没合成出来：" + speech.url() + "。用文字回他，别说发了语音。");
        }
        boolean sent = statusService.sendVoice(speech.url());
        if (!sent) {
            return ToolBusinessResult.failure("语音合成好了，但发送通道没送出去。"
                    + "如实跟他说：这次语音没发成，用文字说了。");
        }
        if (SelfTurnMark.active()) {
            countSelfUse();
        }
        log.info("语音已发送 voice={} 字数={} 用时={}ms", ttsClient.voice(),
                speakable.length(), speech.costMs());
        return ToolBusinessResult.success("语音发出去了（" + ttsClient.voice()
                + " 音色）。他听得到，**别再把这句话用文字重复一遍**，用一句别的接住就行。");
    }

    /** 去掉 markdown 记号：这些念出来只是噪音 */
    static String plain(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")
                .replaceAll("[*_`#>~|]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    /** 只留能念出来的字符：去掉 emoji/颜文字/表情标记 */
    static String speakable(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                kept.append(' ');
            } else if (Character.isISOControl(cp)) {
                // 控制字符直接丢
            } else if (Character.isEmoji(cp) || Character.isEmojiModifier(cp)
                    || Character.isEmojiPresentation(cp) || Character.isExtendedPictographic(cp)) {
                kept.append(' ');
            } else {
                kept.appendCodePoint(cp);
            }
        });
        return kept.toString().replaceAll("\\s+", " ").trim();
    }

    private boolean quotaExceeded() {
        if (!SelfTurnMark.active() || selfDailyLimit <= 0) {
            return false;
        }
        String user = statusService.currentUserId();
        if (user == null) {
            return false;
        }
        LocalDate today = LocalDate.now();
        LocalDate seen = usedDates.get(user);
        if (seen == null || !seen.equals(today)) {
            usedDates.put(user, today);
            selfUsedToday.put(user, new AtomicInteger());
            return false;
        }
        return selfUsedToday.getOrDefault(user, new AtomicInteger()).get() >= selfDailyLimit;
    }

    private void countSelfUse() {
        String user = statusService.currentUserId();
        if (user == null) {
            return;
        }
        usedDates.putIfAbsent(user, LocalDate.now());
        selfUsedToday.computeIfAbsent(user, key -> new AtomicInteger()).incrementAndGet();
    }
}
