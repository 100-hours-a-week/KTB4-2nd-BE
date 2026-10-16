package com.yeodam.yeodambe.trip.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AttachmentCleanupScheduleStore {
    private static final String KEY = "trip:attachment:cleanup:scheduled";

    private final StringRedisTemplate redisTemplate;

    public void schedule(Long attachmentId, Instant scheduledAt) {
        redisTemplate.opsForZSet().add(
                KEY,
                attachmentId.toString(),
                scheduledAt.toEpochMilli()
        );
    }

    public List<Long> findDue(Instant now, int limit) {
        var ids = redisTemplate.opsForZSet().rangeByScore(
                KEY,
                Double.NEGATIVE_INFINITY,
                now.toEpochMilli(),
                0,
                limit
        );

        return ids.stream()
                .map(Long::valueOf)
                .toList();
    }
}
