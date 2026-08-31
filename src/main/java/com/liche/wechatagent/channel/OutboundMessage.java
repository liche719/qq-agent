package com.liche.wechatagent.channel;

/** 出站消息（模拟器 outbox 用） */
public record OutboundMessage(String userId, String text, long timestamp) {
}
