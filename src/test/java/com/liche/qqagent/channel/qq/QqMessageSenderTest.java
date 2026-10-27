package com.liche.qqagent.channel.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 端点选择（2026-10-27 抽出）：群消息打 /v2/groups/，私聊打 /v2/users/，选错就是 404。 */
class QqMessageSenderTest {

    @Test
    void picksEndpointByConversationType() {
        assertEquals("/v2/users/{openid}/messages", QqMessageSender.path("openid-1"));
        assertEquals("/v2/groups/{openid}/messages",
                QqMessageSender.path(QqMessages.groupConversationId("openid-1")));
    }
}
