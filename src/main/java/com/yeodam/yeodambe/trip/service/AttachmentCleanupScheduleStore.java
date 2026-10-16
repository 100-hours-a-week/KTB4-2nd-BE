package com.yeodam.yeodambe.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentCleanupScheduleStore {
    private static final String KEY = "trip:attachment:cleanup:scheduled";

    private final StringRedisTemplate redisTemplate;

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
