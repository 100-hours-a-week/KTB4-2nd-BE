package com.yeodam.yeodambe.user.security.ratelimit;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Per-process GCRA quota. No requests, tokens or sessions are stored here. */
public final class UserReadRateLimiter {
    private static final long SECOND = 1_000_000_000L;
    private final long interval;
    private final long tolerance;
    private final int maxUsers;
    private final LongSupplier clock;
    private final Map<Long, Long> arrivals = new HashMap<>();
    private long nextSweep;

    public record Decision(boolean allowed, boolean capacityExceeded, long retryAfterSeconds) {}

    public UserReadRateLimiter(int rate, int burst, int maxUsers) {
        this(rate, burst, maxUsers, System::nanoTime);
    }

    UserReadRateLimiter(int rate, int burst, int maxUsers, LongSupplier clock) {
        if (rate < 1 || rate > 1000 || burst < 0 || burst > 10000 || maxUsers < 1 || maxUsers > 100000) {
            throw new IllegalArgumentException("Invalid user read rate limit configuration");
        }
        this.interval = (SECOND + rate - 1) / rate;
        this.tolerance = interval * burst;
        this.maxUsers = maxUsers;
        this.clock = clock;
        this.nextSweep = clock.getAsLong();
    }

    public synchronized Decision acquire(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("A validated positive user ID is required");
        }
        long now = clock.getAsLong();
        // Only fully replenished quotas can be removed; eviction cannot reset an active user's budget.
        if (now - nextSweep >= 0 || (!arrivals.containsKey(userId) && arrivals.size() >= maxUsers)) {
            arrivals.entrySet().removeIf(entry -> now - entry.getValue() >= 0);
            nextSweep = now + 60 * SECOND;
        }
        Long previous = arrivals.get(userId);
        if (previous == null && arrivals.size() >= maxUsers) {
            return new Decision(false, true, 1);
        }
        long arrival = previous == null || now - previous >= 0 ? now : previous;
        long wait = arrival - now - tolerance;
        if (wait > 0) {
            return new Decision(false, false, (wait + SECOND - 1) / SECOND);
        }
        arrivals.put(userId, arrival + interval);
        return new Decision(true, false, 0);
    }
}
