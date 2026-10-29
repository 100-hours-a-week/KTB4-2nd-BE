package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;

import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.story.event.StoryGenerationStatusChangedEvent;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryGenerationTimeoutService {
    private final StoryGenerationJobRepository jobRepository;
    private final StoryGenerationOutboxRepository outboxRepository;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public boolean expire(Long generationId) {
        return expire(generationId, LocalDateTime.now());
    }

    @Transactional
    public boolean expire(Long generationId, LocalDateTime now) {
        var job = jobRepository.findByIdForUpdate(generationId).orElse(null);
        if (job == null || job.isFinished()) {
            return true;
        }
        var outbox = outboxRepository.findByGenerationId(generationId).orElseThrow();
        var comparisonTime = outbox.isCdcManaged()
                ? jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class)
                : now;
        if (!job.failIfTimedOut(outbox.getPublishedAt(), comparisonTime)) {
            return false;
        }
        var error = new StoryGenerationStatusResponse.Failure(
                ErrorMessage.STORY_GENERATION_FAILED.name(),
                StoryGenerationInternalErrorMessage.GENERATION_FAILED.message()
        );
        var snapshot = new StoryGenerationStatusResponse(
                job.getId(), job.getStatus(), job.getStatusVersion(), job.getStoryId(), error
        );
        eventPublisher.publishEvent(new StoryGenerationStatusChangedEvent(
                job.getUserId(), job.getTripId(), snapshot
        ));
        logGenerationTimedOut(job);
        return true;
    }

    private void logGenerationTimedOut(StoryGenerationJob job) {
        WorkflowLog.finished("story_generation", job.getTripId(), job.getExecutionId(), job.getCreatedAt(), "failure", job.getErrorCode());
        log.atWarn()
                .addKeyValue("event", "story_generation_timed_out")
                .addKeyValue("result", "failure")
                .addKeyValue("trip_id", job.getTripId())
                .addKeyValue("job_id", job.getExecutionId())
                .addKeyValue("generation_id", job.getId())
                .addKeyValue("error_code", job.getErrorCode())
                .log(StoryGenerationInternalErrorMessage.GENERATION_TIMED_OUT.message());
    }
}
