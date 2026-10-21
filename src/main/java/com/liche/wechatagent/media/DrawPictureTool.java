package com.liche.wechatagent.media;

import com.liche.wechatagent.tool.AgentToolProvider;
import com.liche.wechatagent.channel.OutboundMedia;
import com.liche.wechatagent.tool.ToolBusinessResult;
import com.liche.wechatagent.tool.ToolExecutionClass;
import com.liche.wechatagent.tool.ToolExecutionPolicy;
import com.liche.wechatagent.tool.ToolStatusService;
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
    private final com.liche.wechatagent.config.LlmCallSink auditSink;

    public DrawPictureTool(ImageGenClient imageClient,
                           ToolStatusService statusService,
                           @Value("${llm.image-self-daily-limit:5}") int selfDailyLimit,
                           org.springframework.beans.factory.ObjectProvider<com.liche.wechatagent.config.LlmCallSink> auditSink) {
        this.imageClient = imageClient;
        this.statusService = statusService;
        this.selfDailyLimit = Math.max(0, selfDailyLimit);
        this.auditSink = auditSink.getIfAvailable();
        this.downloader = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    /** 只有**自主画**才受额度限制；用户明确让它画的一律放行（2026-10-21 用户要求） */
    private boolean quotaExceeded() {
        if (!com.liche.wechatagent.self.SelfTurnMark.active()) {
            return false;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        if (!today.equals(usedDate)) {
            usedDate = today;
            selfUsedToday.set(0);
        }
        return selfDailyLimit > 0 && selfUsedToday.get() >= selfDailyLimit;
    }

    @Tool(value = "画一张图并发给用户。用户说「给我画个…」「画一张…」「做个图」时调用。"
            + "prompt 用中文描述画面，越具体越好（主体、动作、场景、风格、光线）；"
            + "别把「画一个」这类指令本身写进去。"
            + "**你自己主动画有每日上限**（用户明确要求的不受限）；没想法时别为了显得有产出而画。"
            + "返回是否成功；失败时如实告诉用户没画成、别假装画了。")
    @ToolExecutionPolicy(ToolExecutionClass.SLOW_EXTERNAL)
    public ToolBusinessResult drawPicture(String prompt) {
        if (!imageClient.enabled()) {
            return ToolBusinessResult.failure("生图能力没配置，画不了。如实跟他说。");
        }
        if (prompt == null || prompt.isBlank()) {
            return ToolBusinessResult.failure("没说要画什么。问他要画什么。");
        }
        if (quotaExceeded()) {
            return ToolBusinessResult.failure("今天你自己主动画的张数到上限了（" + selfDailyLimit
                    + " 张/天）。**他要你画的不受影响**——这一轮就别画了，或者跟他说一声"
                    + "\"今天我自己画满了，你要我画就说\"。");
        }

        ImageGenClient.Result result = imageClient.generate(prompt.trim());
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
            long size = Files.size(file);
            if (size < 1024) {
                return ToolBusinessResult.failure("下回来的图不对劲（只有 " + size + " 字节）。");
            }

            String fileName = "画的图-" + System.currentTimeMillis() + ".png";
            boolean sent = statusService.sendMedia(new OutboundMedia(file, fileName, "image/png"));
            if (com.liche.wechatagent.self.SelfTurnMark.active()) {
                selfUsedToday.incrementAndGet();
            }
            if (auditSink != null) {
                try {
                    auditSink.acceptCall(new com.liche.wechatagent.config.LlmCallEvent(
                            "image", false, true, result.costMs(),
                            com.liche.wechatagent.config.LlmUsage.EMPTY, null));
                } catch (RuntimeException ignored) {
                    // 记账失败不影响画图
                }
            }
            log.info("画图完成 发送={} 大小={}KB 提示词={}", sent, size / 1024, prompt.substring(0, Math.min(40, prompt.length())));

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
