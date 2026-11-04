package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryAlreadyExistsException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.event.StoryGenerationStatusChangedEvent;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.service.response.StoryCompletionListItem;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.common.logging.WorkflowLog;


@Service
@RequiredArgsConstructor
public class StoryGenerationFinalizationService {

    private final StoryGenerationJobRepository jobRepository;
    private final StoryRepository storyRepository;
    private final TripRepository tripRepository;
    private final StoryGenerationTimeoutService timeoutService;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void finish(Long generationId, Long storyId) {
        var job = jobRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (job.isFinished() || timeoutService.expire(generationId)) {
            return;
        }

        if (!storyId.equals(job.getStoryId())) {
            throw new IllegalStateException(
                    StoryGenerationInternalErrorMessage
                            .PREPARED_STORY_ID_MISMATCH.message()
            );
        }

        var trip = tripRepository.findOwnedActiveForUpdate(
                job.getTripId(), job.getUserId()
        ).orElseThrow(TripNotFoundException::new);

        if (storyRepository.findCurrentCompletedByTripId(trip.getId()).isPresent()) {
            throw new StoryAlreadyExistsException();
        }

        var story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage
                                .PREPARED_STORY_INVALID.message()
                ));

        if (!trip.getId().equals(story.getTripId())
                || story.getDeletedAt() != null
                || story.getProcessingStatus() != Story.Status.PROCESSING) {
            throw new IllegalStateException(
                    StoryGenerationInternalErrorMessage
                            .PREPARED_STORY_INVALID.message()
            );
        }

        try (var timing = WorkflowLog.stage("story_finalization")
                .field("generation_id", generationId)) {
        story.complete();
        trip.changeCurrentStory(storyId);
        job.complete(storyId);

        tripRepository.advanceSequence(trip.getId());

        var sequence = jdbc.queryForObject(
                "SELECT change_sequence FROM trips WHERE trip_id = ?",
                Long.class,
                trip.getId()
        );

        var listItem = new StoryCompletionListItem(
                storyId,
                trip.getId(),
                sequence,
                story.getMood(),
                story.getStorySummary()
        );

        var snapshot = new StoryGenerationStatusResponse(
                job.getId(),
                job.getStatus(),
                job.getStatusVersion(),
                job.getStoryId(),
                null
        );

        eventPublisher.publishEvent(new StoryGenerationStatusChangedEvent(
                job.getUserId(),
                job.getTripId(),
                snapshot,
                listItem
        ));
            timing.committed("story_finalization");

            WorkflowLog.finished(
                    "story_generation",
                    job.getTripId(),
                    job.getExecutionId(),
                    job.getCreatedAt(),
                    "success",
                    null
            );
        }
    }
}