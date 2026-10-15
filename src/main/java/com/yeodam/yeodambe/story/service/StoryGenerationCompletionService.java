package com.yeodam.yeodambe.story.service;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

    @Transactional
    public void complete(
            Long generationId,
            StoryGenerationAiResponse response
    ) {
        if (response == null
                || response.status() != StoryGenerationAiResponse.Status.COMPLETED) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID.message()
            );
        }

        var job = jobRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (!job.getTripId().equals(response.tripId())
                || !job.getExecutionId().equals(response.executionId())) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message()
            );
        }

        if (job.isFinished()) {
            return;
        }
        var outbox = outboxRepository.findByGenerationId(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.OUTBOX_NOT_FOUND.message()
                ));
        var now = LocalDateTime.now();
        if (job.failIfTimedOut(outbox.getPublishedAt(), now)) {
            return;
        }

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
                                "스토리 생성 장소 또는 날짜가 올바르지 않습니다."
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
    }
}