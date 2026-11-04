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
    private final StoryGenerationPreparationService preparationService;
    private final StoryContentStorageService contentStorageService;
    private final StoryGenerationFinalizationService finalizationService;
    private final StoryGenerationJobRepository jobRepository;
    private final StoryGenerationOutboxRepository outboxRepository;

    public void complete(
            Long generationId,
            StoryGenerationAiResponse response
    ) {
        var preparedStoryId = preparationService.prepare(generationId, response);

        if (preparedStoryId.isEmpty()) {
            return;
        }

        Long storyId = preparedStoryId.orElseThrow();

        var job = jobRepository.findById(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        var outbox = outboxRepository.findByGenerationId(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.OUTBOX_NOT_FOUND.message()
                ));

        try (var scope = WorkflowMdcScope.job(
                "story_generation",
                job.getTripId(),
                job.getExecutionId(),
                outbox.getRequestId()
        )) {
            logAiResultReceived(generationId);

            var preparedContent = preparationService.readPreparedContent(
                    generationId,
                    storyId,
                    java.time.ZoneOffset.UTC
            );

            if (preparedContent.isEmpty()) {
                return;
            }

            contentStorageService.insertIfAbsent(preparedContent.orElseThrow());
            finalizationService.finish(generationId, storyId);
        }
    }

    private void logAiResultReceived(Long generationId) {
        WorkflowLog.stage("ai_result_received")
                .field("generation_id", generationId)
                .committed("ai_result_received");
    }
}