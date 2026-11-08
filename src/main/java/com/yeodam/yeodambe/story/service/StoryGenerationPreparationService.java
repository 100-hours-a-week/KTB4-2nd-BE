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
import tools.jackson.databind.SerializationFeature;
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
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.service.response.StoryPreparationSnapshot;
import com.yeodam.yeodambe.trip.entity.Trip;
import org.springframework.beans.factory.annotation.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import tools.jackson.core.JacksonException;

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

    @Value("${app.story.mongo-native-generation-enabled:true}")
    private boolean nativeGenerationEnabled;

    @Transactional
    public Optional<StoryContentDocument> prepareContent(
            Long generationId,
            StoryGenerationAiResponse response
    ) {
        Optional<StoryGenerationJob> active = findActiveJob(generationId, response);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        StoryGenerationJob job = active.orElseThrow();
        if (job.getPreparedContentMetadata() == null
                && (job.getStoryId() != null || !nativeGenerationEnabled)) {
            Long storyId = prepareLegacy(job, response);
            return readPreparedContent(generationId, storyId, ZoneOffset.UTC);
        }
        return Optional.of(prepareNative(job, response));
    }

    @Transactional
    public Optional<Long> prepare(
            Long generationId,
            StoryGenerationAiResponse response
    ) {
        return findActiveJob(generationId, response).map(job -> prepareLegacy(job, response));
    }

    private Optional<StoryGenerationJob> findActiveJob(
            Long generationId,
            StoryGenerationAiResponse response
    ) {
        StoryGenerationJob job = jobRepository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));
        if (response == null || response.status() != StoryGenerationAiResponse.Status.COMPLETED) {
            throw new InvalidStoryResultException(StoryGenerationInternalErrorMessage.AI_RESPONSE_INVALID);
        }
        if (!job.getTripId().equals(response.tripId())
                || !job.getExecutionId().equals(response.executionId())) {
            throw new InvalidStoryResultException(StoryGenerationInternalErrorMessage.AI_EXECUTION_MISMATCH);
        }
        if (job.isFinished() || timeoutService.expire(generationId)) {
            return Optional.empty();
        }
        return Optional.of(job);
    }

    private Long prepareLegacy(StoryGenerationJob job, StoryGenerationAiResponse response) {
        rejectNativeLegacyAccess(job);
        Trip trip = tripRepository.findOwnedActiveForUpdate(
                job.getTripId(),
                job.getUserId()
        ).orElseThrow(TripNotFoundException::new);

        if (storyRepository.findCurrentCompletedByTripId(trip.getId()).isPresent()) {
            throw new StoryAlreadyExistsException();
        }

        StoryGenerationMessage request = readAndValidateRequest(job, response);
        boolean firstPreparation = job.getStoryId() == null;

        Story story = findOrCreateStory(
                job,
                response.result().storySummary()
        );

        if (firstPreparation) {
            saveInitialContent(story, request, response.result());
        }

        return story.getId();
    }

    @Transactional(readOnly = true)
    public Optional<StoryContentDocument> readPreparedContent(
            Long generationId,
            Long storyId,
            ZoneId sourceZone
    ) {
        StoryGenerationJob job = jobRepository.findById(generationId)
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.JOB_NOT_FOUND.message()
                ));

        if (job.isFinished()) {
            return Optional.empty();
        }

        rejectNativeLegacyAccess(job);
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

        List<StorySelectedPlace> selectedPlaces =
                selectedPlaceRepository.findAllByStoryIdOrderByIdAsc(storyId);

        List<StoryBlock> blocks =
                blockRepository.findAllByStoryIdOrderByOrderNumberAscIdAsc(storyId);

        StoryContentDocument document = documentFactory.create(
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
        StoryGenerationOutbox outbox = outboxRepository.findByGenerationId(job.getId())
                .orElseThrow(() -> new IllegalStateException(
                        StoryGenerationInternalErrorMessage.OUTBOX_NOT_FOUND.message()
                ));

        StoryGenerationMessage request = objectMapper.readValue(
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

        for (StoryGenerationMessage.Place place : request.places()) {
            selectedPlaces.add(new StorySelectedPlace(
                    story.getId(),
                    place.tripPlaceId()
            ));
            placeIds.add(place.tripPlaceId());
        }

        selectedPlaceRepository.saveAll(selectedPlaces);

        Map<Long, TripDetailPlace> sourcePlaces = new HashMap<>();

        for (TripDetailPlace place : tripDetailPlaceRepository.findAllById(placeIds)) {
            sourcePlaces.put(place.getId(), place);
        }

        List<StoryBlock> blocks = new ArrayList<>();

        for (StoryGenerationAiResponse.Block block : result.blocks()) {
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

    private StoryContentDocument prepareNative(
            StoryGenerationJob job,
            StoryGenerationAiResponse response
    ) {
        if (job.getPreparedContentMetadata() != null && job.getStoryId() == null) {
            throw invalidMetadata();
        }
        Trip trip = tripRepository.findOwnedActiveForUpdate(job.getTripId(), job.getUserId())
                .orElseThrow(TripNotFoundException::new);
        if (storyRepository.findCurrentCompletedByTripId(trip.getId()).isPresent()) {
            throw new StoryAlreadyExistsException();
        }
        StoryGenerationMessage request = readAndValidateRequest(job, response);
        boolean firstPreparation = job.getStoryId() == null;
        Story story = findOrCreateStory(job, response.result().storySummary());
        List<Long> selectedPlaceIds = request.places().stream()
                .map(StoryGenerationMessage.Place::tripPlaceId)
                .toList();
        Map<Long, LocalDate> currentDates = loadSourceDates(job.getTripId(), response.result());
        Map<Long, String> previewKeys = loadResultPreviewKeys(job.getTripId(), response.result());
        Map<Long, LocalDate> sourceDates;
        if (firstPreparation) {
            sourceDates = currentDates;
        } else {
            StoryPreparationSnapshot snapshot = readSnapshot(job.getPreparedContentMetadata());
            sourceDates = snapshot.sourcePlaces().stream().collect(Collectors.toMap(
                    StoryPreparationSnapshot.SourcePlace::tripPlaceId,
                    StoryPreparationSnapshot.SourcePlace::sourceDate
            ));
            if (!sourceDates.keySet().equals(currentDates.keySet())) {
                throw new InvalidStoryResultException(StoryGenerationInternalErrorMessage.PREPARED_CONTENT_MISMATCH);
            }
        }
        String hash = initialContentHash(job, request, response.result(), sourceDates);
        if (firstPreparation) {
            List<StorySelectedPlace> selectedPlaces = selectedPlaceIds.stream()
                    .map(placeId -> new StorySelectedPlace(story.getId(), placeId))
                    .toList();
            selectedPlaceRepository.saveAll(selectedPlaces);
            List<StoryPreparationSnapshot.SourcePlace> sources = sourceDates.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new StoryPreparationSnapshot.SourcePlace(entry.getKey(), entry.getValue()))
                    .toList();
            job.prepareContent(objectMapper.writeValueAsString(new StoryPreparationSnapshot(hash, sources)));
        } else if (!hash.equals(readSnapshot(job.getPreparedContentMetadata()).initialContentHash())) {
            throw new InvalidStoryResultException(StoryGenerationInternalErrorMessage.PREPARED_CONTENT_MISMATCH);
        }
        return documentFactory.createFromResult(
                story, response.result(), selectedPlaceIds, sourceDates, previewKeys, hash
        );
    }

    private Map<Long, LocalDate> loadSourceDates(Long tripId, StoryGenerationAiResponse.Result result) {
        List<Long> placeIds = result.blocks().stream()
                .map(StoryGenerationAiResponse.Block::tripPlaceId)
                .distinct()
                .toList();
        List<TripDetailPlace> places = tripDetailPlaceRepository.findAllById(placeIds);
        if (places.size() != placeIds.size() || places.stream().anyMatch(place ->
                !tripId.equals(place.getTripId()) || place.getStartedAt() == null)) {
            throw new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.RESULT_SOURCE_INVALID);
        }
        return places.stream().collect(Collectors.toMap(
                TripDetailPlace::getId, place -> place.getStartedAt().toLocalDate()
        ));
    }

    private Map<Long, String> loadResultPreviewKeys(Long tripId, StoryGenerationAiResponse.Result result) {
        List<Long> attachmentIds = result.blocks().stream()
                .map(StoryGenerationAiResponse.Block::tripAttachmentId)
                .toList();
        List<TripAttachment> attachments = tripAttachmentRepository.findAllWithFileByIds(attachmentIds);
        if (attachments.size() != attachmentIds.size() || attachments.stream().anyMatch(attachment ->
                !tripId.equals(attachment.getTripId()) || attachment.getFile() == null)) {
            throw new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.RESULT_SOURCE_INVALID);
        }
        return attachments.stream()
                .filter(attachment -> attachment.getPreviewStorageKey() != null)
                .collect(Collectors.toMap(TripAttachment::getId, TripAttachment::getPreviewStorageKey));
    }

    private StoryPreparationSnapshot readSnapshot(String metadata) {
        if (metadata == null) {
            throw invalidMetadata();
        }
        StoryPreparationSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(metadata, StoryPreparationSnapshot.class);
        } catch (JacksonException failure) {
            throw invalidMetadata();
        }
        if (snapshot == null || snapshot.initialContentHash() == null
                || !snapshot.initialContentHash().matches("[0-9a-f]{64}")
                || snapshot.sourcePlaces() == null || snapshot.sourcePlaces().isEmpty()
                || snapshot.sourcePlaces().size() > 30
                || snapshot.sourcePlaces().stream().anyMatch(place -> place == null
                || place.tripPlaceId() == null || place.tripPlaceId() < 1 || place.sourceDate() == null)
                || snapshot.sourcePlaces().stream().map(StoryPreparationSnapshot.SourcePlace::tripPlaceId)
                .distinct().count() != snapshot.sourcePlaces().size()) {
            throw invalidMetadata();
        }
        return snapshot;
    }

    private void rejectNativeLegacyAccess(StoryGenerationJob job) {
        if (job.getPreparedContentMetadata() != null) {
            throw invalidMetadata();
        }
    }

    private StoryDataIntegrityException invalidMetadata() {
        return new StoryDataIntegrityException(StoryGenerationInternalErrorMessage.PREPARATION_METADATA_INVALID);
    }

    private String initialContentHash(
            StoryGenerationJob job,
            StoryGenerationMessage request,
            StoryGenerationAiResponse.Result result,
            Map<Long, LocalDate> sourceDates
    ) {
        List<List<Object>> sources = sourceDates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> List.<Object>of(entry.getKey(), entry.getValue().toString()))
                .toList();
        List<List<Object>> blocks = result.blocks().stream()
                .sorted(Comparator.comparing(StoryGenerationAiResponse.Block::orderNumber))
                .map(block -> Arrays.<Object>asList(
                        block.orderNumber(), block.tripPlaceId(), block.tripAttachmentId(),
                        block.dayLabel(), block.detailSummary(), block.memo()
                )).toList();
        List<Object> canonical = List.of(
                "story-init-v1", job.getId(), job.getExecutionId(), job.getTripId(), job.getStoryId(),
                job.getMood().name(), result.storySummary(),
                request.places().stream().map(StoryGenerationMessage.Place::tripPlaceId).toList(), sources, blocks
        );
        byte[] bytes = objectMapper.writer()
                .without(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(canonical)
                .getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(StoryGenerationInternalErrorMessage.CONTENT_HASH_UNAVAILABLE.message(), failure);
        }
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
