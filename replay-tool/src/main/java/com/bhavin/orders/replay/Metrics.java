package com.bhavin.orders.replay;

import java.util.List;

final class Metrics {

    private Metrics() {
    }

    /** Nearest-rank percentile of an ascending array. An empty array gives 0. */
    static long percentile(long[] ascending, double percent) {
        if (ascending.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percent / 100.0 * ascending.length);
        return ascending[Math.min(Math.max(rank, 1), ascending.length) - 1];
    }

    static long[] sorted(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).sorted().toArray();
    }
}
