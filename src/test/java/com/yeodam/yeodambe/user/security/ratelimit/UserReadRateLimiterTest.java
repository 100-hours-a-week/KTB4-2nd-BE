package com.yeodam.yeodambe.user.security.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserReadRateLimiterTest {
    @Test
    void burstIsExtraAllowanceAndReplenishesWithoutSleeping() {
        AtomicLong time = new AtomicLong();
        var limiter = new UserReadRateLimiter(5, 30, 10, time::get);
        for (int i = 0; i < 31; i++) {
            assertThat(limiter.acquire(1).allowed()).isTrue();
        }
        assertThat(limiter.acquire(1)).isEqualTo(new UserReadRateLimiter.Decision(false, false, 1));
        time.set(199_999_999L);
        assertThat(limiter.acquire(1).allowed()).isFalse();
        time.incrementAndGet();
        assertThat(limiter.acquire(1).allowed()).isTrue();
        assertThat(limiter.acquire(1).allowed()).isFalse();
    }

    @Test
    void anotherUserHasIndependentQuotaAndRejectionsDoNotPostponeRecovery() {
        AtomicLong time = new AtomicLong();
        var limiter = new UserReadRateLimiter(5, 0, 10, time::get);
        assertThat(limiter.acquire(1).allowed()).isTrue();
        assertThat(limiter.acquire(2).allowed()).isTrue();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.acquire(1).allowed()).isFalse();
        }
        time.set(200_000_000L);
        assertThat(limiter.acquire(1).allowed()).isTrue();
    }

    @Test
    void fullStoreNeverEvictsActiveQuotaAndFullyReplenishedEntriesCanBeReclaimed() {
        AtomicLong time = new AtomicLong();
        var limiter = new UserReadRateLimiter(5, 0, 1, time::get);
        assertThat(limiter.acquire(1).allowed()).isTrue();
        assertThat(limiter.acquire(2).capacityExceeded()).isTrue();
        assertThat(limiter.acquire(1).allowed()).isFalse();
        time.set(200_000_000L);
        assertThat(limiter.acquire(2).allowed()).isTrue();
    }

    @Test
    void negativeMonotonicClockIsSupported() {
        AtomicLong time = new AtomicLong(-1_000_000_000L);
        var limiter = new UserReadRateLimiter(5, 0, 1, time::get);
        assertThat(limiter.acquire(1).allowed()).isTrue();
        assertThat(limiter.acquire(1).allowed()).isFalse();
        time.addAndGet(200_000_000L);
        assertThat(limiter.acquire(1).allowed()).isTrue();
    }

    @Test
    void concurrentRequestsCannotOverspendOneUsersQuota() throws Exception {
        var limiter = new UserReadRateLimiter(5, 30, 10, () -> 0L);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 200; i++) {
                calls.add(() -> limiter.acquire(1).allowed());
            }
            int allowed = 0;
            for (var future : executor.invokeAll(calls)) {
                if (future.get()) allowed++;
            }
            assertThat(allowed).isEqualTo(31);
        }
    }

    @Test
    void invalidLimitsAndUserIdsAreRejected() {
        for (int[] config : new int[][]{{0, 30, 10}, {1001, 30, 10}, {5, -1, 10},
                {5, 10001, 10}, {5, 30, 0}, {5, 30, 100001}}) {
            assertThatThrownBy(() -> new UserReadRateLimiter(config[0], config[1], config[2]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new UserReadRateLimiter(5, 30, 10).acquire(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
