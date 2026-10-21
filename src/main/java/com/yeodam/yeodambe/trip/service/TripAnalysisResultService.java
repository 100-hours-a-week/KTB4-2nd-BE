package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.event.TripCreateResultEvent;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import com.yeodam.yeodambe.user.service.UserStatsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class TripAnalysisResultService {
    private static final Comparator<TripAttachment> THUMBNAIL_ORDER = Comparator.comparing(
                    TripAttachment::getEvaluation,
                    Comparator.nullsLast(Comparator.reverseOrder())
            )
            .thenComparing(TripAttachment::getId);

    private final TripRepository trips;
    private final TripAttachmentRepository attachmentRepository;
    private final TripDetailPlaceRepository placeRepository;
    private final InitialUploadExecutionRegistry executions;
    private final UserStatsService userStats;
    private final InitialAttachmentUploadBatchRepository uploadBatches;
    private final TripCreationExecutionRepository creationExecutions;
    private final InitialAttachmentUploadItemRepository uploadItems;
    private final InitialAttachmentUploadTransactionService uploadTransactions;
    private final ObjectMapper json;
    private final EntityManager entityManager;
    private final TripMapCacheStore tripMapCacheStore;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveCompleted(
            Long tripId,
            Long userId,
            String executionId,
            List<TripAttachment> attachments,
            JsonNode result,
            Map<String, String> placeNames
    ) {
        if (!executions.isCurrent(tripId, executionId)) {
            throw new IllegalStateException(TripInternalErrorMessage.CURRENT_EXECUTION_AI_RESULT_MISMATCH.message());
        }
        if (uploadBatches.existsByTripId(tripId)) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        persistCompleted(tripId, userId, attachments, result, placeNames);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveDirectUploadCompleted(
            Long tripId, Long userId, String uploadId,
            List<TripAttachment> attachments, JsonNode result, Map<String, String> placeNames
    ) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        InitialAttachmentUploadBatch batch = uploadBatches.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(TripInitialAttachmentUploadNotAllowedException::new);
        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING
                || batch.getStatus() != InitialAttachmentUploadStatus.ANALYZING || !batch.getLastBatch()) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        persistCompleted(tripId, userId, attachments, result, placeNames);
        batch.completeAnalysis();
        uploadBatches.save(batch);
    }

    private void persistCompleted(
            Long tripId,
            Long userId,
            List<TripAttachment> attachments,
            JsonNode result,
            Map<String, String> placeNames
    ) {
        validateCompletedResult(attachments, result, placeNames);

        if (trips.finishInitialUpload(
                tripId,
                userId,
                ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED
        ) != 1) {
            throw new IllegalStateException(TripInternalErrorMessage.CURRENT_EXECUTION_AI_RESULT_MISMATCH.message());
        }

        Map<Long, TripAttachment> byId = new HashMap<>();
        for (TripAttachment attachment : attachments) {
            byId.put(attachment.getId(), attachment);
        }

        Map<String, Long> savedPlaceIds = savePlaces(tripId, result.path("places"), placeNames, byId);
        saveUnclassifiedAttachments(result.path("unclassified"), savedPlaceIds, byId);
        updateTripThumbnail(tripId, userId, attachments);
        attachmentRepository.saveAll(attachments);
        userStats.refreshFromActiveTrips(userId);
        tripMapCacheStore.evictAfterCommit(userId);
    }

    private void validateCompletedResult(
            List<TripAttachment> attachments,
            JsonNode result,
            Map<String, String> placeNames
    ) {
        if (result == null || !result.path("places").isArray() || !result.path("unclassified").isArray()) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_RESULT_FORMAT_INVALID.message());
        }

        Set<Long> expected = new HashSet<>(attachments.stream()
                .map(TripAttachment::getId)
                .toList());
        Set<Long> actual = new HashSet<>();
        Set<String> placeIds = new HashSet<>();

        for (JsonNode place : result.path("places")) {
            String placeId = place.path("place_id").asString();

            if (placeId.isBlank() || !placeIds.add(placeId) || !place.path("attachments").isArray()) {
                throw new IllegalStateException(TripInternalErrorMessage.AI_PLACE_RESULT_INVALID.message());
            }

            for (JsonNode photo : place.path("attachments")) {
                if (!actual.add(photo.path("trip_attachment_id").asLong(-1))) {
                    throw new IllegalStateException(TripInternalErrorMessage.AI_RESULT_DUPLICATE_ATTACHMENT.message());
                }
            }
        }

        for (JsonNode photo : result.path("unclassified")) {
            if (!actual.add(photo.path("trip_attachment_id").asLong(-1))) {
                throw new IllegalStateException(TripInternalErrorMessage.AI_RESULT_DUPLICATE_ATTACHMENT.message());
            }
        }

        if (!expected.equals(actual)) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_RESULT_ATTACHMENT_LIST_MISMATCH.message());
        }

        validatePlaceNames(placeIds, placeNames);
        validateUnclassifiedPlaceReferences(result.path("unclassified"), placeIds);
    }

    @Transactional
    public void replaceCompleted(Long tripId, Long userId, JsonNode result, Map<String, String> placeNames) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException();
        }
        List<TripAttachment> attachments = attachmentRepository.findAllForReanalysis(tripId);
        validateCompletedResult(attachments, result, placeNames);
        placeRepository.softDeleteByTripId(tripId, LocalDateTime.now());
        applyClassification(tripId, userId, attachments, result, placeNames);
    }

    private void applyClassification(Long tripId, Long userId, List<TripAttachment> attachments,
                                     JsonNode result, Map<String, String> placeNames) {
        Map<Long, TripAttachment> byId = new HashMap<>();
        for (TripAttachment attachment : attachments) {
            byId.put(attachment.getId(), attachment);
        }
        Map<String, Long> savedPlaceIds = savePlaces(tripId, result.path("places"), placeNames, byId);
        saveUnclassifiedAttachments(result.path("unclassified"), savedPlaceIds, byId);
        updateTripThumbnail(tripId, userId, attachments);
        attachmentRepository.saveAll(attachments);
        userStats.refreshFromActiveTrips(userId);
        tripMapCacheStore.evictAfterCommit(userId);
    }

    private Map<String, Long> savePlaces(
            Long tripId,
            JsonNode places,
            Map<String, String> placeNames,
            Map<Long, TripAttachment> byId
    ) {
        Map<String, Long> savedPlaceIds = new HashMap<>();
        int order = 0;

        for (JsonNode place : places) {
            TripDetailPlace savedPlace = savePlace(tripId, ++order, place, placeNames, byId);
            savedPlaceIds.put(place.path("place_id").asString(), savedPlace.getId());
        }

        return savedPlaceIds;
    }

    private TripDetailPlace savePlace(
            Long tripId,
            int order,
            JsonNode place,
            Map<String, String> placeNames,
            Map<Long, TripAttachment> byId
    ) {
        String placeId = place.path("place_id").asString();

        TripAttachment representative = requireRepresentativeAttachment(place, byId);

        TripDetailPlace savedPlace = placeRepository.save(TripDetailPlace.fromAnalysis(
                tripId, order, placeNames.get(placeId),
                coordinate(place, "latitude"), coordinate(place, "longitude"),
                time(place.path("first_taken_at")), time(place.path("last_taken_at")),
                representative.getPreviewStorageKey()));

        List<TripAttachment> placeAttachments = new ArrayList<>();
        for (JsonNode photo : place.path("attachments")) {
            TripAttachment attachment = byId.get(photo.path("trip_attachment_id").asLong(-1));
            attachment.classify(savedPlace.getId(),
                    origin(photo), time(photo.path("taken_at")),
                    coordinate(photo, "latitude"), coordinate(photo, "longitude"),
                    evaluation(photo)
            );
            placeAttachments.add(attachment);
        }

        TripAttachment thumbnail = placeAttachments.stream()
                .min(THUMBNAIL_ORDER)
                .orElseThrow(() -> new IllegalStateException(TripInternalErrorMessage.REPRESENTATIVE_ATTACHMENT_MISSING.message()));
        savedPlace.changeThumbnailKey(thumbnail.getPreviewStorageKey());
        return savedPlace;
    }

    private TripAttachment requireRepresentativeAttachment(
            JsonNode place,
            Map<Long, TripAttachment> byId
    ) {
        long representativeId = place.path("representative_attachment_id").asLong(-1);
        TripAttachment representative = byId.get(representativeId);

        if (representative == null) {
            throw new IllegalStateException(TripInternalErrorMessage.REPRESENTATIVE_ATTACHMENT_MISSING.message());
        }

        boolean representativeInPlace = false;
        for (JsonNode photo : place.path("attachments")) {
            if (photo.path("trip_attachment_id").asLong(-1) == representativeId) {
                representativeInPlace = true;
            }
        }

        if (!representativeInPlace) {
            throw new IllegalStateException(TripInternalErrorMessage.REPRESENTATIVE_ATTACHMENT_NOT_IN_PLACE.message());
        }

        return representative;
    }

    private void saveUnclassifiedAttachments(
            JsonNode unclassified,
            Map<String, Long> savedPlaceIds,
            Map<Long, TripAttachment> byId
    ) {
        for (JsonNode photo : unclassified) {
            AttachmentIssue issue;
            try {
                issue = AttachmentIssue.valueOf(photo.path("issue").asString());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(TripInternalErrorMessage.AI_ATTACHMENT_ISSUE_INVALID.message(), e);
            }

            if (issue == AttachmentIssue.NONE) {
                throw new IllegalStateException(TripInternalErrorMessage.UNCLASSIFIED_ATTACHMENT_ISSUE_MISSING.message());
            }

            byId.get(
                    photo.path("trip_attachment_id").asLong(-1)).unclassify(
                    resolveUnclassifiedPlaceId(photo, savedPlaceIds), issue,
                    origin(photo), time(photo.path("taken_at")),
                    optionalCoordinate(photo, "latitude"), optionalCoordinate(photo, "longitude"),
                    evaluation(photo)
            );
        }
    }

    private void updateTripThumbnail(
            Long tripId,
            Long userId,
            List<TripAttachment> attachments
    ) {
        String thumbnailKey = attachments.stream()
                .filter(attachment -> attachment.getClassificationStatus() == ClassificationStatus.ACTIVE)
                .min(THUMBNAIL_ORDER)
                .map(TripAttachment::getPreviewStorageKey)
                .orElse(null);
        if (trips.updateThumbnailKey(
                tripId,
                userId,
                ProcessingStatus.COMPLETED,
                thumbnailKey
        ) != 1) {
            throw new IllegalStateException(TripInternalErrorMessage.CURRENT_EXECUTION_AI_RESULT_MISMATCH.message());
        }
    }

    private void validateUnclassifiedPlaceReferences(
            JsonNode unclassified,
            Set<String> placeIds
    ) {
        boolean invalidReference = StreamSupport.stream(unclassified.spliterator(), false)
                .map(photo -> photo.path("place_id"))
                .filter(placeId -> !placeId.isMissingNode() && !placeId.isNull())
                .anyMatch(placeId -> !placeId.isString()
                        || placeId.asString().isBlank()
                        || !placeIds.contains(placeId.asString()));
        if (invalidReference) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_UNCLASSIFIED_PLACE_REFERENCE_INVALID.message());
        }
    }

    private Long resolveUnclassifiedPlaceId(
            JsonNode photo,
            Map<String, Long> savedPlaceIds
    ) {
        JsonNode placeId = photo.path("place_id");
        if (placeId.isMissingNode() || placeId.isNull()) {
            return null;
        }
        return savedPlaceIds.get(placeId.asString());
    }

    private void validatePlaceNames(
            Set<String> placeIds,
            Map<String, String> placeNames
    ) {
        if (placeNames == null || !placeIds.equals(placeNames.keySet())) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_PLACE_NAME_RESULT_INVALID.message());
        }
        for (String name : placeNames.values()) {
            if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 50) {
                throw new IllegalStateException(TripInternalErrorMessage.AI_PLACE_NAME_RESULT_INVALID.message());
            }
        }
    }

    private RegionOrigin origin(JsonNode photo) {
        try {
            return RegionOrigin.valueOf(photo.path("region_origin").asString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_REGION_SOURCE_INVALID.message(), e);
        }
    }

    private LocalDateTime time(JsonNode value) {
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.asString())
                    .atZoneSameInstant(ZoneId.of("Asia/Seoul"))
                    .toLocalDateTime();
        } catch (RuntimeException e) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_CAPTURED_AT_INVALID.message(), e);
        }
    }

    private BigDecimal coordinate(
            JsonNode value,
            String field
    ) {
        BigDecimal coordinate = optionalCoordinate(value, field);
        if (coordinate == null) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_PLACE_COORDINATE_MISSING.message());
        }
        return coordinate;
    }

    private BigDecimal optionalCoordinate(
            JsonNode value,
            String field
    ) {
        JsonNode number = value.path(field);
        if (number.isNull() || number.isMissingNode()) {
            return null;
        }
        try {
            return new BigDecimal(number.asString());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_COORDINATE_INVALID.message(), e);
        }
    }

    private Integer evaluation(JsonNode photo) {
        JsonNode value = photo.path("evaluation");
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }

        int score = value.asInt(-1);
        if (score < 0 || score > 100) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_SCORE_INVALID.message());
        }

        return score;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void receiveKafkaResult(
            TripCreateResultEvent event,
            String rawPayload
    ) {
        var candidate = creationExecutions.findById(event.executionId()).orElse(null);
        if (candidate == null || !candidate.getTripId().equals(event.tripId())) {
            return;
        }
        Trip trip = trips.findOwnedActiveForUpdate(candidate.getTripId(), candidate.getUserId()).orElse(null);
        var execution = lockExecution(event.executionId());
        if (trip == null || execution.isTerminal() || execution.getProcessPayload() == null) {
            return;
        }
        if (execution.getResultRawPayload() != null) {
            if (!json.readTree(execution.getResultRawPayload()).equals(json.readTree(rawPayload))) {
                execution.recordConflictingResult(rawPayload);
            }
            return;
        }
        execution.receiveResult(rawPayload, event.status().name(), uploadTransactions.databaseTime());
        logAiResultReceived(event.tripId(), event.executionId());
        switch (event.status()) {
            case COMPLETED -> {
                // 원문을 먼저 커밋하고 검증·외부 조회·최종 저장은 별도 작업에서 수행한다.
            }
            case FAILED -> uploadTransactions.failExecution(event.executionId(), ErrorMessage.AI_PROCESSING_FAILED.name());
            case CANCELED -> {
                uploadTransactions.cancelExecution(trip.getId(), trip.getUserId());
                execution.cancellationDelivered();
                creationExecutions.saveAndFlush(execution);
                trips.finishInitialUpload(trip.getId(), trip.getUserId(), ProcessingStatus.PROCESSING, ProcessingStatus.CANCELED);
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<TripAnalysisResultClaim> claimResult() {
        var now = uploadTransactions.databaseTime();
        for (var candidate : creationExecutions.findResultsToApply(now, PageRequest.of(0, 20))) {
            Trip trip = trips.findOwnedActiveForUpdate(candidate.getTripId(), candidate.getUserId()).orElse(null);
            var execution = lockExecution(candidate.getExecutionId());
            if (trip == null || execution.isTerminal()
                    || execution.getStatus() != TripCreationExecution.Status.RESULT_APPLYING
                    || (execution.getResultLeaseUntil() != null && execution.getResultLeaseUntil().isAfter(now))
                    || (execution.getResultNextAttemptAt() != null && execution.getResultNextAttemptAt().isAfter(now))) {
                continue;
            }
            if (execution.getResultAttempts() >= 3) {
                uploadTransactions.failExecution(execution.getExecutionId(), ErrorMessage.RESULT_APPLY_RETRY_EXHAUSTED.name());
                continue;
            }
            execution.claimResult(UUID.randomUUID().toString(), now.plusMinutes(5));
            return Optional.of(new TripAnalysisResultClaim(
                    execution.getExecutionId(), trip.getId(), trip.getUserId(), execution.getResultClaimToken(),
                    execution.getResultRawPayload(), execution.getResultAttempts() + 1));
        }
        return Optional.empty();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean startResultAttempt(TripAnalysisResultClaim claim) {
        if (!renewResultLease(claim)) {
            return false;
        }
        var execution = creationExecutions.findById(claim.executionId()).orElseThrow();
        if (execution.getResultAttempts() + 1 != claim.attempt() || execution.getResultAttempts() >= 3) {
            return false;
        }
        execution.startResultAttempt(uploadTransactions.databaseTime());
        return true;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public JsonNode validateForApplication(TripAnalysisResultClaim claim) {
        JsonNode root = json.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(claim.rawPayload());
        JsonNode result = root.path("result");
        List<TripAttachment> photos = executionAttachments(claim.executionId());
        validateResult(photos, result);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean renewResultLease(TripAnalysisResultClaim claim) {
        Trip trip = trips.findOwnedActiveForUpdate(claim.tripId(), claim.userId()).orElse(null);
        var execution = lockExecution(claim.executionId());
        if (trip == null || execution.isTerminal()
                || execution.getResultLeaseUntil() == null
                || !Objects.equals(execution.getResultClaimToken(), claim.claimToken())
                || execution.getResultLeaseUntil().isBefore(uploadTransactions.databaseTime())) {
            return false;
        }
        execution.renewResultLease(uploadTransactions.databaseTime().plusMinutes(5));
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveKafkaCompleted(
            TripAnalysisResultClaim claim,
            JsonNode result,
            Map<String, String> placeNames
    ) {
        if (!renewResultLease(claim)) {
            throw new CancellationException(TripInternalErrorMessage.RESULT_APPLICATION_CANCELED.message());
        }
        var execution = creationExecutions.findById(claim.executionId()).orElseThrow();
        if (!Objects.equals(execution.getResultRawPayload(), claim.rawPayload())) {
            throw new IllegalStateException(TripInternalErrorMessage.ADOPTED_RESULT_PAYLOAD_MISMATCH.message());
        }
        long originalBytes = uploadItems.findExecutionItems(claim.executionId()).stream()
                .mapToLong(InitialAttachmentUploadItem::getSizeBytes)
                .reduce(0L, Math::addExact);
        uploadTransactions.releaseReservation(execution, originalBytes);
        execution.finish(TripCreationExecution.Status.COMPLETED,
                null, uploadTransactions.databaseTime());
        creationExecutions.saveAndFlush(execution);
        persistCompleted(claim.tripId(), claim.userId(), executionAttachments(claim.executionId()), result, placeNames);
        logWorkflowCompleted(claim);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failResult(
            TripAnalysisResultClaim claim,
            boolean permanent,
            String failureReason
    ) {
        if (!renewResultLease(claim)) {
            return;
        }
        var execution = creationExecutions.findById(claim.executionId()).orElseThrow();
        execution.recordResultFailure(failureReason, uploadTransactions.databaseTime());
        if (permanent || execution.getResultAttempts() >= 3) {
            uploadTransactions.failExecution(
                    claim.executionId(),
                    permanent ? ErrorMessage.AI_RESULT_INTEGRITY_FAILED.name()
                            : ErrorMessage.RESULT_APPLY_RETRY_EXHAUSTED.name()
            );
        } else {
            execution.retryResult(uploadTransactions.databaseTime().plusSeconds(10));
        }
    }

    private TripCreationExecution lockExecution(String id) {
        var execution = creationExecutions.findForUpdate(id).orElseThrow();
        entityManager.flush();
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        return execution;
    }

    private List<TripAttachment> executionAttachments(String executionId) {
        List<Long> ids = uploadItems.findExecutionItems(executionId).stream()
                .map(InitialAttachmentUploadItem::getTripAttachmentId).toList();
        if (ids.isEmpty() || ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException(TripInternalErrorMessage.EXECUTION_ATTACHMENT_LIST_MISSING.message());
        }
        List<TripAttachment> photos = attachmentRepository.findAllById(ids);
        if (photos.size() != ids.size() || photos.stream().anyMatch(photo -> photo.getDeletedAt() != null)) {
            throw new IllegalStateException(TripInternalErrorMessage.EXECUTION_ATTACHMENT_LIST_MISMATCH.message());
        }
        return photos;
    }

    public Set<String> validateResult(
            List<TripAttachment> attachments,
            JsonNode result
    ) {
        if (result == null || !result.path("places").isArray() || !result.path("unclassified").isArray()
                || !result.path("failed").isArray() || !result.path("failed").isEmpty()) {
            throw new IllegalStateException(TripInternalErrorMessage.TRIP_RESULT_FORMAT_INVALID.message());
        }
        Set<Long> expected = new HashSet<>(attachments.stream().map(TripAttachment::getId).toList());
        Set<Long> actual = new HashSet<>();
        Set<String> placeIds = new HashSet<>();
        for (JsonNode place : result.path("places")) {
            String placeId = place.path("place_id").asString();
            if (!place.path("place_id").isString() || placeId.isBlank() || !placeIds.add(placeId)
                    || !place.path("attachments").isArray() || place.path("attachments").isEmpty()) {
                throw new IllegalStateException(TripInternalErrorMessage.TRIP_RESULT_PLACE_LIST_INVALID.message());
            }
            validateResultPlace(place, actual);
        }
        for (JsonNode photo : result.path("unclassified")) {
            validateResultPhoto(photo, actual);
            if (AttachmentIssue.valueOf(photo.path("issue").asString()) == AttachmentIssue.NONE) {
                throw new IllegalStateException(TripInternalErrorMessage.UNCLASSIFIED_ATTACHMENT_REASON_MISSING.message());
            }
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException(TripInternalErrorMessage.RESULT_ATTACHMENT_SET_MISMATCH.message());
        }
        validateUnclassifiedPlaceReferences(result.path("unclassified"), placeIds);
        return placeIds;
    }

    private void validateResultPlace(
            JsonNode place,
            Set<Long> actual
    ) {
        validateCoordinate(place, "latitude", false);
        validateCoordinate(place, "longitude", false);
        LocalDateTime first = time(place.path("first_taken_at"));
        LocalDateTime last = time(place.path("last_taken_at"));
        if (!isNullableTime(place.path("first_taken_at")) || !isNullableTime(place.path("last_taken_at"))
                || (first == null) != (last == null) || first != null && last.isBefore(first)
                || !place.path("representative_attachment_id").isIntegralNumber()) {
            throw new IllegalStateException(TripInternalErrorMessage.RESULT_PLACE_PERIOD_OR_REPRESENTATIVE_INVALID.message());
        }
        long representative = place.path("representative_attachment_id").asLong(-1);
        boolean present = false;
        for (JsonNode photo : place.path("attachments")) {
            validateResultPhoto(photo, actual);
            validateCoordinate(photo, "latitude", false);
            validateCoordinate(photo, "longitude", false);
            if (origin(photo) == RegionOrigin.UNKNOWN || !photo.path("evaluation").isIntegralNumber()) {
                throw new IllegalStateException(TripInternalErrorMessage.RESULT_PLACE_ATTACHMENT_ORIGIN_OR_SCORE_INVALID.message());
            }
            present |= photo.path("trip_attachment_id").asLong() == representative;
        }
        if (!present) {
            throw new IllegalStateException(TripInternalErrorMessage.REPRESENTATIVE_ATTACHMENT_NOT_IN_PLACE.message());
        }
    }

    private void validateResultPhoto(
            JsonNode photo,
            Set<Long> ids
    ) {
        if (!photo.path("trip_attachment_id").isIntegralNumber() || !ids.add(photo.path("trip_attachment_id").asLong())) {
            throw new IllegalStateException(TripInternalErrorMessage.RESULT_ATTACHMENT_ID_INVALID_OR_DUPLICATE.message());
        }
        if (!photo.path("region_origin").isString()) {
            throw new IllegalStateException(TripInternalErrorMessage.RESULT_ATTACHMENT_REGION_ORIGIN_INVALID.message());
        }
        origin(photo);
        JsonNode score = photo.path("evaluation");
        if (!score.isMissingNode() && !score.isNull() && (!score.isIntegralNumber() || !score.canConvertToInt())) {
            throw new IllegalStateException(TripInternalErrorMessage.RESULT_ATTACHMENT_SCORE_NOT_INTEGER.message());
        }
        evaluation(photo);
        JsonNode takenAt = photo.path("taken_at");
        if (!takenAt.isMissingNode() && !takenAt.isNull() && !takenAt.isString()) {
            throw new IllegalStateException(TripInternalErrorMessage.RESULT_ATTACHMENT_CAPTURED_AT_INVALID.message());
        }
        time(takenAt);
        validateCoordinate(photo, "latitude", true);
        validateCoordinate(photo, "longitude", true);
    }
    private boolean isNullableTime(JsonNode value) {
        return value.isNull() || value.isString();
    }

    private void validateCoordinate(
            JsonNode object,
            String field,
            boolean nullable
    ) {
        JsonNode value = object.path(field);
        if (nullable && (value.isNull() || value.isMissingNode())) {
            return;
        }
        BigDecimal maximum = BigDecimal.valueOf("latitude".equals(field) ? 90 : 180);
        if (!value.isNumber() || value.asDecimal().abs().compareTo(maximum) > 0) {
            throw new IllegalStateException(TripInternalErrorMessage.RESULT_COORDINATE_OUT_OF_RANGE.message());
        }
    }

    private void logAiResultReceived(Long tripId, String executionId) {
        WorkflowLog.event("trip_creation", tripId, executionId, "ai_result_received");
    }

    private void logWorkflowCompleted(TripAnalysisResultClaim claim) {
        var completedTrip = trips.findById(claim.tripId()).orElseThrow();
        WorkflowLog.finished("trip_creation", claim.tripId(), claim.executionId(),
                completedTrip.getCreatedAt(), "success", null);
    }
}
