package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;

import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.user.service.UserStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

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

    @Transactional
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

    @Transactional
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
            Long tripId, Long userId, List<TripAttachment> attachments,
            JsonNode result, Map<String, String> placeNames
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

        if (!expected.equals(actual)) throw new IllegalStateException(TripInternalErrorMessage.AI_RESULT_ATTACHMENT_LIST_MISMATCH.message());

        validatePlaceNames(placeIds, placeNames);

        if (trips.finishInitialUpload(
                tripId,
                userId,
                ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED
        ) != 1) {
            throw new IllegalStateException(TripInternalErrorMessage.CURRENT_EXECUTION_AI_RESULT_MISMATCH.message());
        }

        Map<Long, TripAttachment> byId = new HashMap<>();
        for (TripAttachment attachment : attachments) byId.put(attachment.getId(), attachment);

        int order = 0;

        for (JsonNode place : result.path("places")) {
            String placeId = place.path("place_id").asString();

            long representativeId = place.path("representative_attachment_id").asLong(-1);
            TripAttachment representative = byId.get(representativeId);

            if (representative == null) throw new IllegalStateException(TripInternalErrorMessage.REPRESENTATIVE_ATTACHMENT_MISSING.message());

            boolean representativeInPlace = false;
            for (JsonNode photo : place.path("attachments")) {
                if (photo.path("trip_attachment_id").asLong(-1) == representativeId) representativeInPlace = true;
            }

            if (!representativeInPlace) throw new IllegalStateException(TripInternalErrorMessage.REPRESENTATIVE_ATTACHMENT_NOT_IN_PLACE.message());

            TripDetailPlace savedPlace = placeRepository.save(TripDetailPlace.fromAnalysis(
                    tripId, ++order, placeNames.get(placeId),
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
        }


        for (JsonNode photo : result.path("unclassified")) {
            AttachmentIssue issue;
            try {
                issue = AttachmentIssue.valueOf(photo.path("issue").asString());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(TripInternalErrorMessage.AI_ATTACHMENT_ISSUE_INVALID.message(), e);
            }

            if (issue == AttachmentIssue.NONE) throw new IllegalStateException(TripInternalErrorMessage.UNCLASSIFIED_ATTACHMENT_ISSUE_MISSING.message());

            byId.get(
                    photo.path("trip_attachment_id").asLong(-1)).unclassify(issue,
                    origin(photo), time(photo.path("taken_at")),
                    optionalCoordinate(photo, "latitude"), optionalCoordinate(photo, "longitude"),
                    evaluation(photo)
            );
        }

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

        attachmentRepository.saveAll(attachments);
        userStats.refreshFromActiveTrips(userId);
    }

    private void validatePlaceNames(Set<String> placeIds, Map<String, String> placeNames) {
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
        if (value.isNull() || value.isMissingNode()) return null;
        try {
            return OffsetDateTime.parse(value.asString()).toLocalDateTime();
        } catch (RuntimeException e) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_CAPTURED_AT_INVALID.message(), e);
        }
    }

    private BigDecimal coordinate(JsonNode value, String field) {
        BigDecimal coordinate = optionalCoordinate(value, field);
        if (coordinate == null) throw new IllegalStateException(TripInternalErrorMessage.AI_PLACE_COORDINATE_MISSING.message());
        return coordinate;
    }

    private BigDecimal optionalCoordinate(JsonNode value, String field) {
        JsonNode number = value.path(field);
        if (number.isNull() || number.isMissingNode()) return null;
        try {
            return new BigDecimal(number.asString());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(TripInternalErrorMessage.AI_COORDINATE_INVALID.message(), e);
        }
    }

    private Integer evaluation(JsonNode photo) {
        JsonNode value = photo.path("evaluation");
        if (value.isNull() || value.isMissingNode()) return null;

        int score = value.asInt(-1);
        if (score < 0 || score > 100) throw new IllegalStateException(TripInternalErrorMessage.AI_SCORE_INVALID.message());

        return score;
    }
}
