package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.StoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.yeodam.yeodambe.story.client.request.StoryGenerationMessage;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import tools.jackson.databind.ObjectMapper;
import com.yeodam.yeodambe.common.exception.StoryAlreadyExistsException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.entity.StoryBlock;
import com.yeodam.yeodambe.story.entity.StorySelectedPlace;
import com.yeodam.yeodambe.story.repository.StoryBlockRepository;
import com.yeodam.yeodambe.story.repository.StorySelectedPlaceRepository;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.entity.TripAttachment;

@Service
@RequiredArgsConstructor
public class StoryGenerationPreparationService {

    private final StoryRepository storyRepository;
    private final StoryGenerationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final StoryGenerationResultValidator resultValidator;
    private final StoryGenerationJobRepository jobRepository;
    private final TripRepository tripRepository;
    private final StoryGenerationTimeoutService timeoutService;
    private final StoryBlockRepository blockRepository;
    private final StorySelectedPlaceRepository selectedPlaceRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final StoryContentDocumentFactory documentFactory;
    private final TripAttachmentRepository tripAttachmentRepository;

    @Transactional
    public Optional<Long> prepare(
            Long generationId,
            StoryGenerationAiResponse response
    ) {
        var job = jobRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (response == null
                || response.status() != StoryGenerationAiResponse.Status.COMPLETED) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID
            );
        }

        if (!job.getTripId().equals(response.tripId())
                || !job.getExecutionId().equals(response.executionId())) {
            throw new InvalidStoryResultException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH
            );
        }

        if (job.isFinished() || timeoutService.expire(generationId)) {
            return Optional.empty();
        }

        var trip = tripRepository.findOwnedActiveForUpdate(
                job.getTripId(),
                job.getUserId()
        ).orElseThrow(TripNotFoundException::new);

        if (storyRepository.findCurrentCompletedByTripId(trip.getId()).isPresent()) {
            throw new StoryAlreadyExistsException();
        }

        var request = readAndValidateRequest(job, response);
        boolean firstPreparation = job.getStoryId() == null;

        Story story = findOrCreateStory(
                job,
                response.result().storySummary()
        );

        if (firstPreparation) {
            saveInitialContent(story, request, response.result());
        }

        return Optional.of(story.getId());
    }

    @Transactional(readOnly = true)
    public Optional<StoryContentDocument> readPreparedContent(
            Long generationId,
            Long storyId,
            ZoneId sourceZone
    ) {
        var job = jobRepository.findById(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (job.isFinished()) {
            return Optional.empty();
        }

        if (!storyId.equals(job.getStoryId())) {
            throw new IllegalStateException(
                    StoryGenerationInternalErrorMessage
                            .PREPARED_STORY_ID_MISMATCH.message()
            );
        }

        Story story = storyRepository.findById(storyId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage
                                .PREPARED_STORY_INVALID.message()
                ));

        if (!story.getTripId().equals(job.getTripId())
                || story.getDeletedAt() != null
                || story.getProcessingStatus() != Story.Status.PROCESSING) {
            throw new IllegalStateException(
                    StoryGenerationInternalErrorMessage
                            .PREPARED_STORY_INVALID.message()
            );
        }

        var selectedPlaces =
                selectedPlaceRepository.findAllByStoryIdOrderByIdAsc(storyId);

        var blocks =
                blockRepository.findAllByStoryIdOrderByOrderNumberAscIdAsc(storyId);

        var document = documentFactory.create(
                story,
                selectedPlaces,
                blocks,
                sourceZone,
                loadPreviewKeys(blocks)
        );

        return Optional.of(document);
    }

    private StoryGenerationMessage readAndValidateRequest(
            StoryGenerationJob job,
            StoryGenerationAiResponse response
    ) {
        var outbox = outboxRepository.findByGenerationId(job.getId())
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.OUTBOX_NOT_FOUND.message()
                ));

        var request = objectMapper.readValue(
                outbox.getPayload(),
                StoryGenerationMessage.class
        );

        if (!job.getTripId().equals(request.tripId())
                || !job.getExecutionId().equals(request.executionId())) {
            throw new IllegalArgumentException(
                    StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH.message()
            );
        }

        resultValidator.validate(response.result(), request);

        return request;
    }

    private Story findOrCreateStory(
            StoryGenerationJob job,
            String storySummary
    ) {
        if (job.getStoryId() != null) {
            Story story = storyRepository.findById(job.getStoryId())
                    .orElseThrow(() -> new IllegalStateException(
                            StoryGenerationInternalErrorMessage
                                    .PREPARED_STORY_INVALID.message()
                    ));

            if (!story.getTripId().equals(job.getTripId())
                    || story.getDeletedAt() != null
                    || story.getProcessingStatus() != Story.Status.PROCESSING) {
                throw new IllegalStateException(
                        StoryGenerationInternalErrorMessage
                                .PREPARED_STORY_INVALID.message()
                );
            }

            return story;
        }

        Story story = new Story(
                job.getTripId(),
                job.getMood(),
                storySummary
        );

        storyRepository.saveAndFlush(story);
        job.prepareStory(story.getId());

        return story;
    }

    private void saveInitialContent(
            Story story,
            StoryGenerationMessage request,
            StoryGenerationAiResponse.Result result
    ) {
        List<StorySelectedPlace> selectedPlaces = new ArrayList<>();
        List<Long> placeIds = new ArrayList<>();

        for (var place : request.places()) {
            selectedPlaces.add(new StorySelectedPlace(
                    story.getId(),
                    place.tripPlaceId()
            ));
            placeIds.add(place.tripPlaceId());
        }

        selectedPlaceRepository.saveAll(selectedPlaces);

        var sourcePlaces = new HashMap<Long, TripDetailPlace>();

        for (TripDetailPlace place : tripDetailPlaceRepository.findAllById(placeIds)) {
            sourcePlaces.put(place.getId(), place);
        }

        List<StoryBlock> blocks = new ArrayList<>();

        for (var block : result.blocks()) {
            TripDetailPlace sourcePlace = sourcePlaces.get(block.tripPlaceId());

            if (sourcePlace == null
                    || !story.getTripId().equals(sourcePlace.getTripId())
                    || sourcePlace.getStartedAt() == null) {
                throw new StoryDataIntegrityException(
                        StoryGenerationInternalErrorMessage.RESULT_SOURCE_INVALID
                );
            }

            blocks.add(new StoryBlock(
                    story.getId(),
                    block.tripAttachmentId(),
                    sourcePlace.getId(),
                    sourcePlace.getStartedAt().toLocalDate(),
                    block.orderNumber(),
                    block.dayLabel(),
                    block.detailSummary(),
                    block.memo()
            ));
        }

        blockRepository.saveAllAndFlush(blocks);
    }
    private Map<Long, String> loadPreviewKeys(List<StoryBlock> blocks) {
        if (blocks.isEmpty()) {
            return Map.of();
        }
        return tripAttachmentRepository.findAllWithFileByIds(blocks.stream()
                        .map(StoryBlock::getTripAttachmentId)
                        .distinct()
                        .toList()).stream()
                .filter(attachment -> attachment.getPreviewStorageKey() != null)
                .collect(Collectors.toMap(
                        TripAttachment::getId,
                        TripAttachment::getPreviewStorageKey
                ));
    }

}
