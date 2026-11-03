package com.liche.qqagent.media;

import com.liche.qqagent.tool.AgentToolProvider;
import com.liche.qqagent.channel.OutboundMedia;
import com.liche.qqagent.tool.ToolBusinessResult;
import com.liche.qqagent.tool.ToolExecutionClass;
import com.liche.qqagent.tool.ToolExecutionPolicy;
import com.liche.qqagent.tool.ToolStatusService;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 画图工具（2026-10-21）—— 让笨鱼能真的"画"一张出来发给用户。
 *
 * <p>以前它只能"描述"一张图；现在用户说「画一只橘猫」它能真画一张发过去。
 * 成本 **0.2 元/张**，所以有每日上限（{@code llm.image-daily-limit}），免得被反复点着玩。
 *
 * <p>流程：调接口拿 OSS 地址 → 立刻下载到本地临时文件 → 通过通道发出去 → 删临时文件。
 * 之所以不存媒体库：生成的图和"用户发来的素材"是两类东西，混在一起会让媒体列表更难读。
 */
@Component
public class DrawPictureTool implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(DrawPictureTool.class);

    private final ImageGenClient imageClient;
    private final ToolStatusService statusService;
    /**
     * **自主画**的每日额度（2026-10-21 定）：
     * 她自己主动画一天最多 5 张；**用户让她画的不算**。
     *
     * <p>怎么区分：{@code SelfTurnMark.active()} —— 自主轮在跑时为 true。
     * 用户对话里调这个工具永远是 false，所以不受限。
     */
    private final int selfDailyLimit;
    private final java.util.concurrent.atomic.AtomicInteger selfUsedToday = new java.util.concurrent.atomic.AtomicInteger();
    private volatile java.time.LocalDate usedDate = java.time.LocalDate.now();
    private final HttpClient downloader;
    /** 审计出口：生图没有 token，但也要留一条记录，面板才能数出今天画了几张 */
    private final com.liche.qqagent.config.LlmCallSink auditSink;

    /** 一轮最多画几张（2026-10-24）：他"没让你画"的时候连着画了 4 张，用硬上限兜住 */
    private final int maxPerTurn;
    /** 他明确要的画，一轮最多几张（0 = 不限制） */
    private final int userMaxPerTurn;
    /** 先把要求改写成规范提示词，再交给生图（2026-10-24 用户要求） */
    /** 上一次画了什么（2026-11-03）：用户说"不像/重画/改成…"时，要在上一版基础上改，而不是从头来 */
    private volatile LastDraw lastDraw;

    /** 上一版的信息：他的原始要求、最终提示词、尺寸 */
    private record LastDraw(String rawPrompt, String finalPrompt, String size) {
    }

    private final boolean designPrompt;
    /** 笨鱼自己的固定形象：写死在配置里，画"你自己"时自动带上，省得每次瞎编 */
    private final String persona;
    private final dev.langchain4j.model.chat.ChatModel chatModel;

    public DrawPictureTool(ImageGenClient imageClient,
                           ToolStatusService statusService,
                           @Value("${llm.image-self-daily-limit:5}") int selfDailyLimit,
                           @Value("${llm.image-max-per-turn:1}") int maxPerTurn,
                           @Value("${llm.image-user-max-per-turn:0}") int userMaxPerTurn,
                           @Value("${llm.image-prompt-design:true}") boolean designPrompt,
                           @Value("${llm.image-persona:}") String persona,
                           dev.langchain4j.model.chat.ChatModel chatModel,
                           org.springframework.beans.factory.ObjectProvider<com.liche.qqagent.config.LlmCallSink> auditSink) {
        this.imageClient = imageClient;
        this.statusService = statusService;
        this.selfDailyLimit = Math.max(0, selfDailyLimit);
        this.maxPerTurn = Math.max(1, Math.min(3, maxPerTurn));
        this.userMaxPerTurn = userMaxPerTurn <= 0 ? Integer.MAX_VALUE : Math.max(1, Math.min(10, userMaxPerTurn));
        this.designPrompt = designPrompt;
        this.persona = persona == null ? "" : persona.trim();
        this.chatModel = chatModel;
        this.auditSink = auditSink.getIfAvailable();
        this.downloader = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /**
     * 两段式（2026-10-24）：先把用户的大白话改写成画图模型能用的规范提示词。
     *
     * <p>为什么加：用户说"按你这个形象画你在逗猫"，模型只拿到一句话就得凭空补全形象，
     * 结果三张都不是那个样子。现在先让文字模型补全（形象、动作、场景、构图、光线），
     * 再交给生图；成本约 0.01 元/次。
     */

    /**
     * 选尺寸（2026-11-03）。
     *
     * <p>优先级：调用方显式给的合法尺寸 > 按他的用词推断 > 方图。
     * 之前的实现**从来不传 size**，所以不管要头像还是壁纸，永远出 1024×1024。
     */

    /**
     * 正向约束（2026-11-03）。
     *
     * <p>这个模型没有 negative_prompt，只能把"不要什么"写进正向提示词。但**不能一刀切**：
     * 实测赛博朋克那张要求"不要出现文字"，霓虹招牌照样长出字——因为招牌本身就要字形，
     * 强行压制只会让画面变糊。所以按题材分两档：
     * 带招牌/屏幕/书页/海报这类"文字本来就是内容"的题材，只要它清晰、不加乱码；
     * 其他题材才禁止出现文字与水印。
     */
    private String constraintFor(String text) {
        String lower = text == null ? "" : text;
        boolean textNative = lower.matches("(?s).*(招牌|霓虹|广告牌|路牌|屏幕|界面|UI|海报|封面|书|报纸|漫画|字幕|标牌|店招|菜单).*");
        if (textNative) {
            return "。画面干净、主体结构完整；牌面与屏幕上的文字要清晰自然、不要乱码或错别字，不要水印或多余肢体。";
        }
        return "。画面干净、主体结构完整，不要出现文字、水印、logo 或多余肢体。";
    }

    /**
     * 按用途选尺寸（2026-11-03 细化）。
     *
     * <p>百炼实测任何比例都接受（512~4096），但**极端比例质量会掉**——4:1 的图容易拉伸变形。
     * 所以按用途给几档"常见且质量稳定"的尺寸，而不是让模型随便选。
     *
     * <p>优先级：调用方显式指定 > 按用词推断 > 方图。
     */
    /**
     * 尺寸是否"说不清"（2026-11-03）。
     *
     * <p>说了壁纸/海报/封面，但没提手机还是电脑、横还是竖——这时候猜错就是白画一张，
     * 不如让模型问一句。只在**第一次**画时判断；他已经在改上一张时不问。
     */
    private boolean sizeAmbiguous(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        boolean wantsCanvas = prompt.matches("(?s).*(壁纸|海报|封面|头图|横幅).*");
        if (!wantsCanvas) {
            return false;
        }
        boolean saysOrientation = prompt.matches("(?s).*(手机|竖|横|电脑|桌面|宽屏|长图|条漫|banner|方图|头像).*");
        return !saysOrientation;
    }

    private String resolveSize(String explicit, String rawPrompt, String designedPrompt) {
        if (com.liche.qqagent.media.ImageGenClient.validSize(explicit)) {
            return explicit.trim();
        }
        String text = (rawPrompt == null ? "" : rawPrompt) + " " + (designedPrompt == null ? "" : designedPrompt);
        // 超宽/超高（横幅、长图、漫画条）——比例极端，单独一档
        if (text.matches("(?s).*(长图|条漫|横幅|超宽|全景图|滚动长图).*")) {
            return "1024x2048";
        }
        if (text.matches("(?s).*(banner|横幅图|头图|封面图|公众号封面).*")) {
            return "2048x512";
        }
        // 手机壁纸 / 竖版海报：2:3 比 9:16 更稳，裁到手机上也不难看
        if (text.matches("(?s).*(手机壁纸|竖屏|竖版|海报|手机壳|锁屏).*")) {
            return "1024x1536";
        }
        // 头像是方图；表情包也要方图
        if (text.matches("(?s).*(头像|图标|logo|表情包|贴纸).*")) {
            return "1024x1024";
        }
        // 电脑壁纸 / 横版
        if (text.matches("(?s).*(电脑壁纸|桌面|横屏|横版|宽幅|宽屏).*")) {
            return "1536x1024";
        }
        return "1024x1024";
    }

    /**
     * 按反馈改写上一版提示词（2026-11-03）。
     *
     * <p>为什么需要：用户说"不像""眼睛太大"时，如果当新请求处理，模型会重新编一套描述，
     * 上一版的构图、光线、风格全丢——结果越改越差。这里要求"在上一版基础上只改他说的那点"。
     */
    private String revise(String previousFinalPrompt, String feedback, String rawPrompt) {
        try {
            String ask = "这是给画图模型用的上一版提示词：\n"
                    + previousFinalPrompt + "\n\n"
                    + "用户看了图之后说：「" + feedback + "」\n"
                    + "用户这次的整体要求（可能只是重复）：" + rawPrompt + "\n\n"
                    + "请在**保留主体、构图、光线、风格**的前提下，只按反馈修改需要改的地方，"
                    + "输出一段新的画图提示词。要求：80~150 字；只输出提示词本身，不要解释、不要引号、不要分点。";
            String revised = com.liche.qqagent.config.LlmScenario.run(
                    com.liche.qqagent.config.LlmScenario.IMAGE_PROMPT, () -> chatModel.chat(ask));
            if (revised == null || revised.isBlank()) {
                return design(rawPrompt);
            }
            String cleaned = revised.strip();
            cleaned = cleaned.replaceAll("^[\\p{Punct}「」“”]+", "");
            cleaned = cleaned.replaceAll("[\\p{Punct}「」“”]+$", "");
            return cleaned.length() < 10 ? design(rawPrompt)
                    : (cleaned.length() > 400 ? cleaned.substring(0, 400) : cleaned);
        } catch (RuntimeException exception) {
            log.warn("按反馈改写失败，退回重新设计：{}", exception.getMessage());
            return design(rawPrompt);
        }
    }

    private String design(String raw) {
        if (!designPrompt) {
            return raw;
        }
        try {
            String ask = "把下面这个画图要求，改写成一段给画图模型用的中文提示词。\n"
                    + "要求：主体与外观细节、动作、场景、构图/视角、光线、风格都要写清楚；80~150 字；"
                    + "**不要添加他没提到的关键元素**（多出来的人物、道具、文字），只在光线、构图、质感、细节层次上扩写；"
                    + "只输出提示词本身，不要解释、不要引号、不要分点。\n"
                    + (persona.isBlank() ? "" : "如果画的是「笨鱼」自己，固定形象是：" + persona + "\n")
                    + "用户要求：" + raw;
            String designed = com.liche.qqagent.config.LlmScenario.run(
                    com.liche.qqagent.config.LlmScenario.IMAGE_PROMPT, () -> chatModel.chat(ask));
            if (designed == null || designed.isBlank()) {
                return raw;
            }
            String cleaned = designed.strip().replaceAll("^[\"「”']+|[\"」”']+$", "").strip();
            if (cleaned.length() < 10) {
                return raw;
            }
            return cleaned.length() > 400 ? cleaned.substring(0, 400) : cleaned;
        } catch (RuntimeException exception) {
            log.warn("提示词设计失败，退回原文：{}", exception.getMessage());
            return raw;
        }
    }

    private static String clip(String text, int max) {
        return com.liche.qqagent.support.Texts.clipWide(text, max);
    }

    /** 只有**自主画**才受额度限制；用户明确让它画的一律放行（2026-10-21 用户要求） */
    private boolean quotaExceeded() {
        if (!com.liche.qqagent.self.SelfTurnMark.active()) {
            return false;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        if (!today.equals(usedDate)) {
            usedDate = today;
            selfUsedToday.set(0);
        }
        return selfDailyLimit > 0 && selfUsedToday.get() >= selfDailyLimit;
    }

    @Tool(value = "画一张图发给他（「画个…」「做个图」）。**一轮只画一张**；他说不像/没让画就停手，先问清楚哪里不对。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult drawPicture(
            @dev.langchain4j.agent.tool.P("要画什么（用自然语言描述）；如果是在上一张基础上改，这里仍写主体的整体要求")
            String prompt,
            @dev.langchain4j.agent.tool.P(value = "尺寸，形如 768x1344；不确定就别传，会按内容自动选",
                    required = false) String size,
            @dev.langchain4j.agent.tool.P(value = "他对上一张图不满意的地方（如「不像」「眼睛太大」「要橘猫」）。"
                    + "**只要是在上一张的基础上改，就一定要传这个**，否则会从头画、丢掉原来的构图",
                    required = false) String feedback) {
        if (!imageClient.enabled()) {
            return ToolBusinessResult.failure("生图能力没配置，画不了。如实跟他说。");
        }
        if (prompt == null || prompt.isBlank()) {
            return ToolBusinessResult.failure("没说要画什么。问他要画什么。");
        }
        // 2026-11-03（用户要求）：**他明确要的画不设额度**——只有"自己主动画"才受限制。
        // 原来这条不分来源一律 1 张/轮，导致他说"再画一张"被拒，看着像被限流。
        int turnCap = com.liche.qqagent.self.SelfTurnMark.active() ? maxPerTurn : userMaxPerTurn;
        if (!com.liche.qqagent.media.DrawBudget.tryConsume(turnCap)) {
            return ToolBusinessResult.failure("这一轮已经画过 " + turnCap
                    + " 张了，先别接着画。要是他觉得不对，先问清楚哪里不对再画下一张。");
        }
        if (quotaExceeded()) {
            return ToolBusinessResult.failure("今天你自己主动画的张数到上限了（" + selfDailyLimit
                    + " 张/天）。**他要你画的不受影响**——这一轮就别画了，或者跟他说一声"
                    + "\"今天我自己画满了，你要我画就说\"。");
        }

        // 2026-11-03：说"壁纸/海报/封面"但没说是手机还是电脑时，**先问一句**，别猜错尺寸白画一张
        if (!com.liche.qqagent.media.ImageGenClient.validSize(size)
                && feedback == null && lastDraw == null && sizeAmbiguous(prompt)) {
            return ToolBusinessResult.needsInput(
                    "他要的是横版还是竖版？比如手机壁纸（竖）还是电脑壁纸（横）？先问清楚再画。");
        }

        String finalPrompt;
        if (feedback != null && !feedback.isBlank() && lastDraw != null) {
            // 2026-11-03：在**上一版**基础上改，而不是当新请求重画
            finalPrompt = revise(lastDraw.finalPrompt(), feedback.trim(), prompt.trim());
            log.info("按反馈改写 上一版={} 反馈={} → 新提示词={}", clip(lastDraw.finalPrompt(), 40),
                    clip(feedback.trim(), 20), clip(finalPrompt, 60));
        } else {
            finalPrompt = design(prompt.trim());
        }
        // 2026-11-03：这个模型没有 negative_prompt，只能把"不要什么"写进正向提示词里
        finalPrompt = finalPrompt + constraintFor(prompt + " " + finalPrompt);
        String chosenSize = resolveSize(size, prompt, finalPrompt);
        lastDraw = new LastDraw(prompt.trim(), finalPrompt, chosenSize);
        log.info("画图提示词 原文={} → 设计后={} 尺寸={}", clip(prompt.trim(), 40), clip(finalPrompt, 60), chosenSize);
        ImageGenClient.Result result = imageClient.generate(finalPrompt, chosenSize);
        if (!result.ok()) {
            return ToolBusinessResult.failure("没画成：" + result.url() + "。跟他说清楚没画出来。");
        }

        Path file = null;
        try {
            // OSS 地址带签名、有时效，拿到就立刻落盘
            file = Files.createTempFile("va-draw-", ".png");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(result.url()))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = downloader.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return ToolBusinessResult.failure("图生成了但下载失败（HTTP " + response.statusCode() + "）。");
            }
            Files.write(file, response.body());
            long bytes = Files.size(file);
            if (bytes < 1024) {
                return ToolBusinessResult.failure("下回来的图不对劲（只有 " + bytes + " 字节）。");
            }

            String fileName = "画的图-" + System.currentTimeMillis() + ".png";
            boolean sent = statusService.sendMedia(new OutboundMedia(file, fileName, "image/png"));
            if (com.liche.qqagent.self.SelfTurnMark.active()) {
                selfUsedToday.incrementAndGet();
            }
            if (auditSink != null) {
                try {
                    auditSink.acceptCall(new com.liche.qqagent.config.LlmCallEvent(
                            "image", false, true, result.costMs(),
                            com.liche.qqagent.config.LlmUsage.EMPTY, null));
                } catch (RuntimeException ignored) {
                    // 记账失败不影响画图
                }
            }
            log.info("画图完成 发送={} 大小={}KB 提示词={}", sent, bytes / 1024, prompt.substring(0, Math.min(40, prompt.length())));

            if (sent) {
                return ToolBusinessResult.success("画好了，已经发给他了。图是照着「" + prompt.trim()
                        + "」画的。别再说一遍你画了什么——他看得到，简单一句「画好了」就行。");
            }
            return ToolBusinessResult.success("画好了（图存在本地临时文件里，但发送通道这会儿没把图送出去）。"
                    + "如实跟他说：画出来了但没发出去。");
        } catch (Exception e) {
            log.warn("画图流程出错：{}", e.toString());
            return ToolBusinessResult.failure("画图流程出错（" + e.getMessage() + "）。");
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // 临时文件删不掉不影响结果
                }
            }
        }
    }
}
