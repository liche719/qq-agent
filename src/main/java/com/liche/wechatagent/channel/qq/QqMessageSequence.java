package com.liche.wechatagent.channel.qq;

import java.util.concurrent.atomic.AtomicInteger;

final class QqMessageSequence {

    static final int MAX_VALUE = 65_535;

    private final AtomicInteger value;

    QqMessageSequence() {
        this(0);
    }

    QqMessageSequence(int initialValue) {
        this.value = new AtomicInteger(Math.max(0, Math.min(initialValue, MAX_VALUE)));
    }

    int next() {
        return value.updateAndGet(current -> current >= MAX_VALUE ? 1 : current + 1);
    }
}
