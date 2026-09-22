package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
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
    private final TripRepository trips;
    private final TripAttachmentRepository attachmentRepository;
    private final TripDetailPlaceRepository placeRepository;
    private final InitialUploadExecutionRegistry executions;

    @Transactional
    public void saveCompleted(
            Long tripId,
            Long userId,
            String executionId,
            List<TripAttachment> attachments,
            JsonNode result
    ) {
        if (!executions.isCurrent(tripId, executionId)) {
            throw new IllegalStateException("현재 실행과 AI 결과가 일치하지 않습니다.");
        }
        if (result == null || !result.path("places").isArray() || !result.path("unclassified").isArray()) {
            throw new IllegalStateException("AI 결과 형식이 올바르지 않습니다.");
        }

        Set<Long> expected = new HashSet<>(attachments.stream()
                .map(TripAttachment::getId)
                .toList());
        Set<Long> actual = new HashSet<>();

        for (JsonNode place : result.path("places")) {
            for (JsonNode photo : place.path("attachments")) {
                if (!actual.add(photo.path("trip_attachment_id").asLong(-1))) {
                    throw new IllegalStateException("AI 결과에 중복된 사진이 있습니다.");
                }
            }
        }

        for (JsonNode photo : result.path("unclassified")) {
            if (!actual.add(photo.path("trip_attachment_id").asLong(-1))) {
                throw new IllegalStateException("AI 결과에 중복된 사진이 있습니다.");
            }
        }

        if (!expected.equals(actual)) throw new IllegalStateException("AI 결과의 사진 목록이 다릅니다.");
        if (trips.finishInitialUpload(
                tripId,
                userId,
                ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED
        ) != 1) {
            throw new IllegalStateException("현재 실행과 AI 결과가 일치하지 않습니다.");
        }

        Map<Long, TripAttachment> byId = new HashMap<>();
        for (TripAttachment attachment : attachments) byId.put(attachment.getId(), attachment);

        Set<String> placeIds = new HashSet<>();
        int order = 0;

        for (JsonNode place : result.path("places")) {
            String placeId = place.path("place_id").asString();

            if (placeId.isBlank() || !placeIds.add(placeId) || !place.path("attachments").isArray()) {
                throw new IllegalStateException("AI 장소 결과가 올바르지 않습니다.");
            }

            long representativeId = place.path("representative_attachment_id").asLong(-1);
            TripAttachment representative = byId.get(representativeId);

            if (representative == null) throw new IllegalStateException("대표 사진이 없습니다.");

            boolean representativeInPlace = false;
            for (JsonNode photo : place.path("attachments")) {
                if (photo.path("trip_attachment_id").asLong(-1) == representativeId) representativeInPlace = true;
            }

            if (!representativeInPlace) throw new IllegalStateException("대표 사진이 장소에 없습니다.");

            TripDetailPlace savedPlace = placeRepository.save(TripDetailPlace.fromAnalysis(
                    tripId, ++order, coordinate(place, "latitude"), coordinate(place, "longitude"),
                    time(place.path("first_taken_at")), time(place.path("last_taken_at")),
                    representative.getPreviewStorageKey()));

            for (JsonNode photo : place.path("attachments")) {
                byId.get(
                        photo.path("trip_attachment_id").asLong(-1)).classify(savedPlace.getId(),
                        origin(photo), time(photo.path("taken_at")),
                        coordinate(photo, "latitude"), coordinate(photo, "longitude"),
                        evaluation(photo)
                );
            }
        }


        for (JsonNode photo : result.path("unclassified")) {
            AttachmentIssue issue;
            try {
                issue = AttachmentIssue.valueOf(photo.path("issue").asString());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("AI 사진 이슈가 올바르지 않습니다.", e);
            }

            if (issue == AttachmentIssue.NONE) throw new IllegalStateException("미분류 사진의 이슈가 없습니다.");

            byId.get(
                    photo.path("trip_attachment_id").asLong(-1)).unclassify(issue,
                    origin(photo), time(photo.path("taken_at")),
                    optionalCoordinate(photo, "latitude"), optionalCoordinate(photo, "longitude"),
                    evaluation(photo)
            );
        }

        attachmentRepository.saveAll(attachments);
    }

    private RegionOrigin origin(JsonNode photo) {
        try {
            return RegionOrigin.valueOf(photo.path("region_origin").asString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AI 지역 출처가 올바르지 않습니다.", e);
        }
    }

    private LocalDateTime time(JsonNode value) {
        if (value.isNull() || value.isMissingNode()) return null;
        try {
            return OffsetDateTime.parse(value.asString()).toLocalDateTime();
        } catch (RuntimeException e) {
            throw new IllegalStateException("AI 촬영 시각이 올바르지 않습니다.", e);
        }
    }

    private BigDecimal coordinate(JsonNode value, String field) {
        BigDecimal coordinate = optionalCoordinate(value, field);
        if (coordinate == null) throw new IllegalStateException("AI 장소 좌표가 없습니다.");
        return coordinate;
    }

    private BigDecimal optionalCoordinate(JsonNode value, String field) {
        JsonNode number = value.path(field);
        if (number.isNull() || number.isMissingNode()) return null;
        try {
            return new BigDecimal(number.asString());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("AI 좌표가 올바르지 않습니다.", e);
        }
    }

    private Integer evaluation(JsonNode photo) {
        JsonNode value = photo.path("evaluation");
        if (value.isNull() || value.isMissingNode()) return null;

        int score = value.asInt(-1);
        if (score < 0 || score > 100) throw new IllegalStateException("AI 평가값이 올바르지 않습니다.");

        return score;
    }
}
