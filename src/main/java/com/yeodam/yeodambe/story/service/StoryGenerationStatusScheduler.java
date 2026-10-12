package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.story.status-polling-enabled",
        havingValue = "true"
)
public class StoryGenerationStatusScheduler {
    private final StoryGenerationJobRepository jobRepository;
    private final StoryGenerationOutboxRepository outboxRepository;
    private final StoryGenerationStatusSyncService syncService;

    @Scheduled(fixedDelay = 2_000)
    public void synchronizePendingJobs() {
        var jobs = jobRepository.findAllByStatusIn(
                List.of(
                        StoryGenerationJob.Status.QUEUED,
                        StoryGenerationJob.Status.PROCESSING
                )
        );

        for (var job : jobs) {
            String previousRequestId = MDC.get("request_id");

            try {
                var outbox = outboxRepository.findByGenerationId(job.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                StoryGenerationInternalErrorMessage.OUTBOX_NOT_FOUND.message()
                        ));

                MDC.put("request_id", outbox.getRequestId());
                syncService.synchronize(job.getId());
            } catch (RuntimeException failure) {
                log.atWarn()
                        .addKeyValue("event", "story_generation_status_sync_failed")
                        .addKeyValue("result", "failure")
                        .addKeyValue("trip_id", job.getTripId())
                        .addKeyValue("job_id", job.getId())
                        .addKeyValue("failure_stage", "ai_status_sync")
                        .addKeyValue("failure", failure.getClass().getSimpleName())
                        .log("스토리 생성 상태 반영에 실패했습니다.");
            } finally {
                if (previousRequestId == null) {
                    MDC.remove("request_id");
                } else {
                    MDC.put("request_id", previousRequestId);
                }
            }
        }
    }
}
