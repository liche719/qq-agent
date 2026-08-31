package com.liche.wechatagent.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 本地模拟通道：把出站消息存进 per-user outbox，供 REST 接口查询（开发期代替真实微信） */
@Component
@ConditionalOnProperty(name = "wechat.channel.mode", havingValue = "simulator", matchIfMissing = true)
public class SimulatorChannel implements WeChatChannel {

    private static final Logger log = LoggerFactory.getLogger(SimulatorChannel.class);

    private final ConcurrentMap<String, List<OutboundMessage>> outbox = new ConcurrentHashMap<>();

    @Override
    public void sendText(String userId, String text) {
        OutboundMessage msg = new OutboundMessage(userId, text, System.currentTimeMillis());
        outbox.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(msg);
        log.info("[simulator] 推送 -> {} ({} chars)", userId, text == null ? 0 : text.length());
    }

    public List<OutboundMessage> recent(String userId, int limit) {
        List<OutboundMessage> all = outbox.getOrDefault(userId, List.of());
        List<OutboundMessage> result = new ArrayList<>(all);
        if (result.size() > limit) {
            return result.subList(result.size() - limit, result.size());
        }
        return result;
    }
}
