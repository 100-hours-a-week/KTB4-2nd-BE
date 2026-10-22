package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
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
    private final StoryGenerationTimeoutService timeoutService;

    @Scheduled(fixedDelay = 2_000)
    public void synchronizePendingJobs() {
        var jobs = jobRepository.findAllByStatusIn(
                List.of(
                        StoryGenerationJob.Status.QUEUED,
                        StoryGenerationJob.Status.PROCESSING
                )
        );

        for (var job : jobs) {
            try (var scope = WorkflowMdcScope.job("story_generation", job.getTripId(), job.getExecutionId(), null)) {
                populateGenerationLoggingContext(job);
                try {
                    var outbox = outboxRepository.findByGenerationId(job.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    StoryGenerationInternalErrorMessage.OUTBOX_NOT_FOUND.message()
                            ));
                    populateRequestLoggingContext(outbox.getRequestId(), job.getExecutionId());
                    if (!timeoutService.expire(job.getId())) {
                        syncService.synchronize(job.getId());
                    }
                } catch (RuntimeException failure) {
                    logStatusSyncFailure(failure);
                }
            }
        }
    }

    private void logStatusSyncFailure(RuntimeException failure) {
        log.atWarn()
                .addKeyValue("event", "story_generation_status_sync_failed")
                .addKeyValue("result", "failure")
                .addKeyValue("failure_stage", "ai_status_sync")
                .addKeyValue("failure", failure.getClass().getSimpleName())
                .log(StoryGenerationInternalErrorMessage.STATUS_APPLICATION_FAILED.message());
    }

    private void populateGenerationLoggingContext(StoryGenerationJob job) {
        MDC.put("generation_id", job.getId().toString());
    }

    private void populateRequestLoggingContext(String requestId, String executionId) {
        if (requestId != null && !requestId.equals(executionId)) {
            MDC.put("request_id", requestId);
        }
    }
}
