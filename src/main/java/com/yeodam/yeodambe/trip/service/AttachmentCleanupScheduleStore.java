package com.yeodam.yeodambe.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentCleanupScheduleStore {
    private static final String KEY = "trip:attachment:cleanup:scheduled";
    private static final String PROCESSING_KEY = "trip:attachment:cleanup:processing";
    private static final String OWNER_KEY = "trip:attachment:cleanup:owners";
    private static final RedisScript<String> CLAIM_SCRIPT = RedisScript.of(
            new ClassPathResource("redis/claim-attachment-cleanup.lua"),
            String.class
    );
    private static final RedisScript<Long> COMPLETE_SCRIPT = RedisScript.of(
            new ClassPathResource("redis/complete-attachment-cleanup.lua"),
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public Optional<Claim> claim(Duration leaseDuration) {
        String token = UUID.randomUUID().toString();

        String attachmentId = redisTemplate.execute(
                CLAIM_SCRIPT,
                List.of(KEY, PROCESSING_KEY, OWNER_KEY),
                token,
                Long.toString(leaseDuration.toMillis())
        );

        if (attachmentId == null) {
            return Optional.empty();
        }

        return Optional.of(new Claim(Long.valueOf(attachmentId), token));
    }

    public boolean complete(Claim claim) {
        Long result = redisTemplate.execute(
                COMPLETE_SCRIPT,
                List.of(PROCESSING_KEY, OWNER_KEY),
                claim.attachmentId().toString(),
                claim.token()
        );

        return Long.valueOf(1L).equals(result);
    }

    public record Claim(Long attachmentId, String token) {
    }

    public void scheduleAfterCommit(List<Long> attachmentIds, Instant scheduledAt) {
        List<Long> ids = List.copyOf(attachmentIds);

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            scheduleAll(ids, scheduledAt);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        scheduleAll(ids, scheduledAt);
                    }
                }
        );
    }

    private void scheduleAll(List<Long> attachmentIds, Instant scheduledAt) {
        for (Long attachmentId : attachmentIds) {
            try {
                schedule(attachmentId, scheduledAt);
            } catch (DataAccessException exception) {
                log.warn("사진 정리 예약 등록에 실패했습니다. attachmentId={}",
                        attachmentId, exception);
            }
        }
    }

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
