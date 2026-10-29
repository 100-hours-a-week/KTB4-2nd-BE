package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;

import com.yeodam.yeodambe.story.repository.StoryBlockRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.repository.StorySelectedPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import java.time.LocalDateTime;
import java.util.HashMap;
import com.yeodam.yeodambe.common.exception.StoryAlreadyExistsException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.client.request.StoryGenerationMessage;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.entity.StorySelectedPlace;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.story.event.StoryGenerationStatusChangedEvent;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusResponse;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class StoryGenerationCompletionService {
    private final StoryGenerationJobRepository jobRepository;
    private final StoryGenerationOutboxRepository outboxRepository;
    private final TripRepository tripRepository;
    private final StoryRepository storyRepository;
    private final StoryBlockRepository blockRepository;
    private final StorySelectedPlaceRepository selectedPlaceRepository;
    private final StoryGenerationResultValidator resultValidator;
    private final ObjectMapper objectMapper;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void complete(
            Long generationId,
            StoryGenerationAiResponse response
    ) {
        if (response == null
                || response.status() != StoryGenerationAiResponse.Status.COMPLETED) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID
            );
        }

        var job = jobRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (!job.getTripId().equals(response.tripId())
                || !job.getExecutionId().equals(response.executionId())) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH
            );
        }

        if (job.isFinished()) {
            return;
        }
        var outbox = outboxRepository.findByGenerationId(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.OUTBOX_NOT_FOUND.message()
                ));
        var now = outbox.isCdcManaged()
                ? jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class)
                : LocalDateTime.now();
        if (job.failIfTimedOut(outbox.getPublishedAt(), now)) {
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
            logWorkflowFailed(job);
            return;
        }

        try (var scope = WorkflowMdcScope.job("story_generation", job.getTripId(), job.getExecutionId(), outbox.getRequestId());
             var timing = startResultApplicationLogging(generationId)) {
            logAiResultReceived(generationId);
            var trip = tripRepository.findOwnedActiveForUpdate(
                    job.getTripId(), job.getUserId()
            ).orElseThrow(TripNotFoundException::new);

            if (storyRepository.findCurrentCompletedByTripId(trip.getId()).isPresent()) {
                throw new StoryAlreadyExistsException();
            }

            var request = objectMapper.readValue(
                    outbox.getPayload(), StoryGenerationMessage.class
            );

            if (!job.getTripId().equals(request.tripId())
                    || !job.getExecutionId().equals(request.executionId())) {
                throw new IllegalArgumentException(
                        StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message()
                );
            }

            resultValidator.validate(response.result(), request);

            var story = new Story(
                    trip.getId(),
                    job.getMood(),
                    response.result().storySummary()
            );
            story.complete();
            storyRepository.saveAndFlush(story);

            var selectedPlaces = request.places().stream()
                    .map(place -> new StorySelectedPlace(
                            story.getId(), place.tripPlaceId()
                    ))
                    .toList();
            selectedPlaceRepository.saveAll(selectedPlaces);

            var sourcePlaces = new HashMap<Long, TripDetailPlace>();
            tripDetailPlaceRepository.findAllById(
                    request.places().stream()
                            .map(StoryGenerationMessage.Place::tripPlaceId)
                            .toList()
            ).forEach(place -> sourcePlaces.put(place.getId(), place));

            var blocks = response.result().blocks().stream()
                    .map(block -> {
                        var sourcePlace = sourcePlaces.get(block.tripPlaceId());
                        if (sourcePlace == null
                                || !trip.getId().equals(sourcePlace.getTripId())
                                || sourcePlace.getStartedAt() == null) {
                            throw new StoryDataIntegrityException(
                                    StoryGenerationInternalErrorMessage.RESULT_SOURCE_INVALID
                            );
                        }
                        return new StoryBlock(
                                story.getId(), block.tripAttachmentId(),
                                sourcePlace.getId(), sourcePlace.getStartedAt().toLocalDate(),
                                block.orderNumber(), block.dayLabel(),
                                block.detailSummary(), block.memo()
                        );
                    })
                    .toList();
            blockRepository.saveAll(blocks);

            trip.changeCurrentStory(story.getId());
            job.complete(story.getId());
            logResultApplied(timing);
            logWorkflowCompleted(job);
        }
    }

    private WorkflowLog.Stage startResultApplicationLogging(Long generationId) {
        return WorkflowLog.stage("result_application").field("generation_id", generationId);
    }

    private void logAiResultReceived(Long generationId) {
        WorkflowLog.stage("ai_result_received")
                .field("generation_id", generationId)
                .committed("ai_result_received");
    }

    private void logWorkflowFailed(StoryGenerationJob job) {
        WorkflowLog.finished("story_generation", job.getTripId(), job.getExecutionId(),
                job.getCreatedAt(), "failure", job.getErrorCode());
    }

    private void logWorkflowCompleted(StoryGenerationJob job) {
        WorkflowLog.finished("story_generation", job.getTripId(), job.getExecutionId(),
                job.getCreatedAt(), "success", null);
    }

    private void logResultApplied(WorkflowLog.Stage timing) {
        timing.committed("result_application");
    }
}