package com.bhavin.orders.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TraceContextCodecTest {

    private final TraceContextCodec codec = TraceContextCodec.disabled();

    @Test
    void disabledCodecStillRunsTheActionWithAStoredContext() {
        AtomicBoolean ran = new AtomicBoolean(false);
        codec.runInContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", () -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void disabledCodecRunsTheActionWithoutOneAndCapturesNothing() {
        AtomicBoolean ran = new AtomicBoolean(false);
        codec.runInContext(null, () -> ran.set(true));
        assertThat(ran).isTrue();
        assertThat(codec.capture()).isNull();
    }
}
