package com.bhavin.orders.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    void percentilesUseTheNearestRank() {
        long[] values = LongStream.rangeClosed(1, 100).toArray();

        assertThat(Metrics.percentile(values, 50)).isEqualTo(50);
        assertThat(Metrics.percentile(values, 95)).isEqualTo(95);
        assertThat(Metrics.percentile(values, 99)).isEqualTo(99);
        assertThat(Metrics.percentile(values, 100)).isEqualTo(100);
    }

    @Test
    void emptyAndSingleValueInputsAreSafe() {
        assertThat(Metrics.percentile(new long[0], 95)).isZero();
        assertThat(Metrics.percentile(new long[] {7}, 99)).isEqualTo(7);
    }

    @Test
    void dueTimeIsTheRealGapDividedBySpeed() {
        LocalDateTime start = LocalDateTime.parse("2017-11-24T21:00:00");
        LocalDateTime later = LocalDateTime.parse("2017-11-24T21:01:00");

        assertThat(Replayer.dueNanos(start, later, 1)).isEqualTo(60_000_000_000L);
        assertThat(Replayer.dueNanos(start, later, 60)).isEqualTo(1_000_000_000L);
        assertThat(Replayer.dueNanos(start, later, 600)).isEqualTo(100_000_000L);
        assertThat(Replayer.dueNanos(start, start, 60)).isZero();
    }
}
