package com.liche.qqagent.voice;

import com.liche.qqagent.self.SelfTurnMark;
import com.liche.qqagent.tool.AgentToolProvider;
import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import com.liche.qqagent.tool.ToolStatusService;
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

    /** 唱歌用（2026-11-03）：邀测模型，没开通时自动降级成"念歌词" */
    private final MusicClient musicClient;
    private final ToolStatusService statusService;
    /** 它自己主动发语音的每日上限（用户明确要听的不算） */
    private final int selfDailyLimit;
    /** 记账出口：语音没有 token，按字符折钱，不然面板上这笔支出永远是 0 */
    private final com.liche.qqagent.config.LlmCallSink auditSink;
    /** 单价（元/万字符）。qwen3-tts-flash 北京地域 0.8 元/万字符（2026-10-21 查） */
    private final java.math.BigDecimal pricePer10kChars;
    private final ConcurrentHashMap<String, AtomicInteger> selfUsedToday = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDate> usedDates = new ConcurrentHashMap<>();

    public SendVoiceTool(TtsClient ttsClient, MusicClient musicClient,
                         ToolStatusService statusService,
                         @Value("${voice.tts.self-daily-limit:10}") int selfDailyLimit,
                         @Value("${voice.tts.price-yuan-per-10k-chars:0.8}") double pricePer10kChars,
                         org.springframework.beans.factory.ObjectProvider<com.liche.qqagent.config.LlmCallSink> auditSink) {
        this.ttsClient = ttsClient;
        this.musicClient = musicClient;
        this.statusService = statusService;
        this.selfDailyLimit = Math.max(0, selfDailyLimit);
        this.pricePer10kChars = java.math.BigDecimal.valueOf(Math.max(0, pricePer10kChars));
        this.auditSink = auditSink.getIfAvailable();
    }

    /**
     * 唱歌（2026-11-03）。
     *
     * <p>和 {@code sendVoice} 的区别：那边是"把话念出来"，这边是"按歌词谱曲并唱"。
     * 用阿里云 {@code fun-music-v1} 生成，返回 mp3 地址后走同一条语音发送链路。
     *
     * <p><b>失败一定降级、而且要说实话</b>：没开通/超时/额度不足时改成念歌词，
     * 并且明确告诉他"这次没唱成"——绝不能假装唱了。
     */
    @Tool(value = "唱一首歌给他听。他说「唱首歌」「唱一段」，或你想用一首歌回应时用。"
            + "lyrics 是你写的歌词（中文 5~350 字，可以带 [verse]/[chorus] 标记）；"
            + "style 是风格描述（如「温柔民谣，木吉他，慢速」），可省略。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult singSong(
            @dev.langchain4j.agent.tool.P("歌词，中文 5~350 字") String lyrics,
            @dev.langchain4j.agent.tool.P(value = "风格描述，可省略", required = false) String style) {
        String text = lyrics == null ? "" : Lyrics.clean(lyrics);
        if (text.isEmpty()) {
            return ToolBusinessResult.failure("没有歌词。");
        }
        if (quotaExceeded()) {
            return ToolBusinessResult.failure("今天主动发的语音到上限了（" + selfDailyLimit + " 条/天），这一首先别唱。");
        }
        if (musicClient.enabled()) {
            MusicClient.Result song = musicClient.sing(text, style);
            if (song.ok()) {
                if (SelfTurnMark.active()) {
                    countSelfUse();
                }
                log.info("唱歌完成 歌词={}字 时长={}s 用时={}ms", text.length(), song.durationSeconds(), song.costMs());
                boolean sent = statusService.sendVoice(song.url());
                if (!sent) {
                    com.liche.qqagent.agent.VoiceOutbox.put(song.url());
                    return ToolBusinessResult.success("歌已经写好了（" + song.durationSeconds()
                            + " 秒），**会在这一轮末尾补发**。别提前说「已经发了」。");
                }
                return ToolBusinessResult.success("唱好了，**已经发出去了**（" + song.durationSeconds()
                        + " 秒）。别再补一段歌词复述，顶多说一句你想表达的。");
            }
            log.warn("唱歌失败，降级为念歌词：{}", song.error());
        }
        // 降级：念歌词（并如实说明）
        String spoken = Lyrics.speakable(text);
        if (spoken.length() > ttsClient.maxChars()) {
            spoken = spoken.substring(0, ttsClient.maxChars());
        }
        TtsClient.Result speech = ttsClient.synthesize(spoken);
        if (!speech.ok()) {
            return ToolBusinessResult.failure("这次没唱成，语音也没合成出来。用文字跟他说，别装作唱了。");
        }
        if (SelfTurnMark.active()) {
            countSelfUse();
        }
        statusService.sendVoice(speech.url());
        return ToolBusinessResult.success("这次没能真唱（音乐生成没开通或暂时不可用），"
                + "我改成了把歌词念给他听。**要跟他说清楚这是念的、不是唱的**。");
    }

    @Tool(value = "把一句话念出来发语音。他说「说一句/念出来」，或这句话用声音更合适时用。语音就是回复，发完别再补一篇文字。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult sendVoice(
            @dev.langchain4j.agent.tool.P("要念出来的那一两句话") String text,
            @dev.langchain4j.agent.tool.P(value = "是否排到本轮文字之后再发；他说「最后发语音」时传 true，否则省略",
                    required = false) Boolean atEnd) {
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
        if (SelfTurnMark.active()) {
            countSelfUse();
        }
        recordCost(speakable, speech);

        // 发送时机由模型自己决定（2026-10-22 用户澄清：语音不必排最后，但**不能言不由衷**）：
        // 默认调用即发；只有它显式说"最后再发"时，才暂存到本轮文字之后。
        if (Boolean.TRUE.equals(atEnd)) {
            com.liche.qqagent.agent.VoiceOutbox.put(speech.url());
            log.info("语音已合成，排在本轮最后 voice={} 字数={} 用时={}ms", ttsClient.voice(),
                    speakable.length(), speech.costMs());
            return ToolBusinessResult.success("语音合成好了（" + ttsClient.voice()
                    + " 音色），**会在这一轮的文字之后发出去**。"
                    + "你刚才要是说了「最后发」，这样就对得上；一句话都不必再解释。");
        }
        boolean sent = statusService.sendVoice(speech.url());
        if (!sent) {
            // 主动任务（定时/后台/自主）里通道可能拒收：排到本轮末尾，由 orchestrator 走主动发送
            com.liche.qqagent.agent.VoiceOutbox.put(speech.url());
            log.info("语音即时发送未成功，改为本轮末尾补发 voice={} 字数={}ms", ttsClient.voice(),
                    speakable.length(), speech.costMs());
            return ToolBusinessResult.success("语音合成好了（" + ttsClient.voice()
                    + " 音色），**会在这一轮末尾补发**。别提前说「已经发你了」，说「这就发」就行。");
        }
        log.info("语音已发送 voice={} 字数={} 用时={}ms", ttsClient.voice(),
                speakable.length(), speech.costMs());
        return ToolBusinessResult.success("语音已经发出去了（" + ttsClient.voice()
                + " 音色），**就是现在**。别再补一句「稍后/最后发你」——那样会和他看到的时间对不上。"
                + "真要再补话，最多一两句（≤60 字），别把语音内容重复一遍。");
    }

    /**
     * 记一笔账：语音**没有 token**，按字符折钱（0.8 元/万字符）。
     *
     * <p>不记这一步的后果很具体：面板「今日花费」永远看不见语音这笔支出，
     * 明明在花钱却显示 ¥0——和当初生图那个坑一样。
     */
    private void recordCost(String speakable, TtsClient.Result speech) {
        if (auditSink == null) {
            return;
        }
        try {
            int chars = speakable.codePointCount(0, speakable.length());
            java.math.BigDecimal yuan = pricePer10kChars
                    .multiply(java.math.BigDecimal.valueOf(chars))
                    .divide(java.math.BigDecimal.valueOf(10000), 4, java.math.RoundingMode.HALF_UP);
            auditSink.acceptCall(new com.liche.qqagent.config.LlmCallEvent(
                    "voice", false, true, speech.costMs(),
                    com.liche.qqagent.config.LlmUsage.EMPTY, null, yuan));
        } catch (RuntimeException ignored) {
            // 记账失败不影响发语音（同 LlmCallSink 的约定）
        }
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
