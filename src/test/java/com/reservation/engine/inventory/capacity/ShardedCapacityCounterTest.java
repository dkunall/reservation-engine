package com.reservation.engine.inventory.capacity;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ShardedCapacityCounterTest {

    @Test
    void sharesAddUpToTotalAndDifferByAtMostOne() {
        for (int total : new int[]{0, 1, 7, 10, 1000, 1_000_003}) {
            for (int buckets : new int[]{1, 3, 4, 16, 64}) {
                int[] shares = IntStream.range(0, buckets).map(b -> ShardedCapacityCounter.share(total, buckets, b)).toArray();
                assertThat(IntStream.of(shares).sum()).isEqualTo(total);
                assertThat(IntStream.of(shares).max().orElse(0) - IntStream.of(shares).min().orElse(0)).isLessThanOrEqualTo(1);
            }
        }
        assertThat(IntStream.range(0, 4).map(b -> ShardedCapacityCounter.share(10, 4, b)).toArray()).containsExactly(3, 3, 2, 2);
    }
}
