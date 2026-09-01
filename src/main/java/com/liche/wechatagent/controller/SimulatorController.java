package com.liche.wechatagent.controller;

import com.liche.wechatagent.agent.AgentOrchestrator;
import com.liche.wechatagent.channel.InboundAttachment;
import com.liche.wechatagent.channel.InboundMessage;
import com.liche.wechatagent.channel.OutboundMessage;
import com.liche.wechatagent.channel.SimulatorChannel;
import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.memory.MemoryArchiveRepository;
import com.liche.wechatagent.memory.MemoryArchiveService;
import com.liche.wechatagent.memory.UserCoreMemory;
import com.liche.wechatagent.memory.UserCoreMemoryRepository;
import com.liche.wechatagent.memory.UserWorkMemory;
import com.liche.wechatagent.memory.UserWorkMemoryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 本地模拟器（开发期代替真实微信，模拟器模式下启用）：
 * POST /api/sim/send    发送一条模拟微信消息（同步返回回复）
 * GET  /api/sim/replies 查询该用户被推送的出站消息
 * GET  /api/sim/memories 查看该用户三层记忆
 * POST /api/sim/archive  手动触发归档压缩
 */
@RestController
@RequestMapping("/api/sim")
@ConditionalOnProperty(name = "wechat.channel.mode", havingValue = "simulator", matchIfMissing = true)
public class SimulatorController {

    private final AgentOrchestrator orchestrator;
    private final SimulatorChannel simulatorChannel;
    private final UserCoreMemoryRepository coreRepository;
    private final UserWorkMemoryRepository workRepository;
    private final MemoryArchiveRepository archiveRepository;
    private final MemoryArchiveService archiveService;

    public SimulatorController(AgentOrchestrator orchestrator,
                               SimulatorChannel simulatorChannel,
                               UserCoreMemoryRepository coreRepository,
                               UserWorkMemoryRepository workRepository,
                               MemoryArchiveRepository archiveRepository,
                               MemoryArchiveService archiveService) {
        this.orchestrator = orchestrator;
        this.simulatorChannel = simulatorChannel;
        this.coreRepository = coreRepository;
        this.workRepository = workRepository;
        this.archiveRepository = archiveRepository;
        this.archiveService = archiveService;
    }

    @PostMapping("/send")
    public Map<String, Object> send(@RequestBody Map<String, Object> body) {
        String userId = body.get("userId") == null ? "" : String.valueOf(body.get("userId"));
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        String msgId = body.containsKey("msgId") ? String.valueOf(body.get("msgId")) : UUID.randomUUID().toString();
        if (userId.isBlank()) {
            throw new BizException("userId 不能为空");
        }
        if (content.isBlank()) {
            throw new BizException("content 不能为空");
        }
        String reply = orchestrator.onInboundSync(InboundMessage.text(msgId, userId, content));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply == null ? "" : reply);
        result.put("pushed", simulatorChannel.recent(userId, 20));
        return result;
    }

    /** Sends an asynchronous QQ-shaped message, including media and quoted content. */
    @PostMapping("/qq/send")
    public Map<String, Object> sendQq(@RequestBody Map<String, Object> body) {
        String userId = textValue(body, "userId");
        String content = textValue(body, "content");
        String msgId = body.containsKey("msgId") ? textValue(body, "msgId") : UUID.randomUUID().toString();
        List<String> images = stringList(body.get("images"));
        List<InboundAttachment> attachments = attachments(body.get("attachments"));
        if (userId.isBlank()) {
            throw new BizException("userId 不能为空");
        }
        if (content.isBlank() && images.isEmpty() && attachments.isEmpty()) {
            throw new BizException("content、images、attachments 至少提供一项");
        }
        InboundMessage message = InboundMessage.textWithQuote(msgId, userId, content, "qq-simulator", "simulator",
                images, attachments, textValue(body, "quotedContent"),
                stringList(body.get("quotedImages")), attachments(body.get("quotedAttachments")));
        orchestrator.onInbound(message);
        return Map.of("accepted", true, "userId", userId, "msgId", msgId,
                "poll", "/api/sim/replies?userId=" + userId);
    }

    @GetMapping("/replies")
    public List<OutboundMessage> replies(@RequestParam String userId,
                                         @RequestParam(defaultValue = "50") int limit) {
        return simulatorChannel.recent(userId, limit);
    }

    @GetMapping("/memories")
    public Map<String, Object> memories(@RequestParam String userId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("core", coreRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(UserCoreMemory::getContent).toList());
        map.put("work", workRepository.findByUserIdAndArchivedFalse(userId).stream()
                .map(w -> "[" + w.getPriority() + "] " + w.getContent()).toList());
        map.put("archives", archiveRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(a -> a.getSummary() + " (原始IDs: " + a.getOriginalIds() + ")").toList());
        return map;
    }

    @PostMapping("/archive")
    public Map<String, String> archive(@RequestParam String userId) {
        archiveService.compressIfNeeded(userId);
        return Map.of("status", "done");
    }

    private String textValue(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(item -> item != null).map(String::valueOf)
                .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private List<InboundAttachment> attachments(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(item -> new InboundAttachment(String.valueOf(item.getOrDefault("name", "附件")),
                        String.valueOf(item.getOrDefault("contentType", "application/octet-stream")),
                        String.valueOf(item.getOrDefault("url", ""))))
                .filter(item -> !item.url().isBlank()).toList();
    }
}
