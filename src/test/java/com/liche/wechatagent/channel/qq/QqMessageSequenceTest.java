package com.liche.wechatagent.channel.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QqMessageSequenceTest {

    @Test
    void incrementsAtomicallyAndWrapsWithoutReturningZero() {
        assertEquals(1, new QqMessageSequence().next());
        assertEquals(QqMessageSequence.MAX_VALUE, new QqMessageSequence(QqMessageSequence.MAX_VALUE - 1).next());
        assertEquals(1, new QqMessageSequence(QqMessageSequence.MAX_VALUE).next());
    }
}
