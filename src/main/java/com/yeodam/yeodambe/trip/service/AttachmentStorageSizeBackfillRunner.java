package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AttachmentStorageSizeBackfillRunner
        implements ApplicationRunner {

    private final TripAttachmentRepository attachments;
    private final AttachmentStorageSizeBackfillService backfill;
    private final UserStatsRepository userStats;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("attachment-size-backfill").factory());

    @Override
    public void run(ApplicationArguments args) {
        executor.submit(() -> {
            try {
                backfill();
            } catch (RuntimeException failure) {
                log.error("첨부 용량 백필 실행 실패. 다음 서버 시작 시 재실행합니다.", failure);
            }
        });
    }

    void backfill() {
        long afterId = 0L;
        int attachmentFailures = 0;

        while (!Thread.currentThread().isInterrupted()) {
            var batch = attachments.findMissingStorageSizes(
                    afterId, PageRequest.of(0, 100));

            if (batch.isEmpty()) {
                break;
            }

            for (var attachment : batch) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    backfill.backfillOne(attachment.getId());
                } catch (RuntimeException failure) {
                    attachmentFailures++;
                    log.error("첨부 용량 백필 실패 attachment_id={}", attachment.getId(), failure);
                }
                afterId = attachment.getId();
            }
        }

        long afterUserId = 0L;
        int statsFailures = 0;
        while (!Thread.currentThread().isInterrupted()) {
            var batch = userStats.findActiveUserIdsAfter(afterUserId, PageRequest.of(0, 100));
            if (batch.isEmpty()) {
                break;
            }
            for (Long userId : batch) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    backfill.refreshUserStats(userId);
                } catch (RuntimeException failure) {
                    statsFailures++;
                    log.error("첨부 용량 백필 후 통계 보정 실패 user_id={}", userId, failure);
                }
                afterUserId = userId;
            }
        }
        log.info("첨부 용량 백필 실행 종료 attachment_failures={}, stats_failures={}",
                attachmentFailures, statsFailures);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
