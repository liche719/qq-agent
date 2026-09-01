package com.liche.wechatagent.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MessageIdempotencyTest {

    @Test
    void scopesDeduplicationByChannelBotUserAndMessageWithoutDelimiterCollisions() {
        String qq = MessageIdempotency.keyFor("open:id", "message:id", "qq", "bot-a");
        String wechat = MessageIdempotency.keyFor("open:id", "message:id", "wechat", "bot-a");
        String otherBot = MessageIdempotency.keyFor("open:id", "message:id", "qq", "bot-b");
        String ambiguousLegacyPair = MessageIdempotency.keyFor("open", "id:message:id", "qq", "bot-a");

        assertNotEquals(qq, wechat);
        assertNotEquals(qq, otherBot);
        assertNotEquals(qq, ambiguousLegacyPair);
    }
}
