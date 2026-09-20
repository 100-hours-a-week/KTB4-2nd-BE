package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TripProcessingStatusService {
    private final TripRepository trips;
    private final TripDetailPlaceRepository places;
    private final TripAttachmentRepository attachments;
    private final TripPhotoAnalysisService analysis;
    private final InitialUploadExecutionRegistry executions;

    public TripProcessingStatusResponse findStatus(Long tripId, Long userId) {
        Trip trip = findTrip(tripId, userId);

        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            return fromDatabase(trip);
        }
        if (!executions.isAnalysisStarted(tripId)) {
            return processing(tripId);
        }

        TripPhotoAnalysisStatusResponse aiStatus = analysis.findStatus(tripId);
        if (aiStatus.status() == TripPhotoAnalysisStatusResponse.Status.PROCESSING) {
            return new TripProcessingStatusResponse(
                    tripId,
                    ProcessingStatus.PROCESSING,
                    new TripProcessingStatusResponse.Progress(
                            aiStatus.progress().done(), aiStatus.progress().total()),
                    aiStatus.currentStep(),
                    null,
                    null
            );
        }

        return fromDatabase(findTrip(tripId, userId));
    }

    private Trip findTrip(Long tripId, Long userId) {
        return trips.findByIdAndUserIdAndDeletedAtIsNull(tripId, userId)
                .orElseThrow(TripNotFoundException::new);
    }

    private TripProcessingStatusResponse fromDatabase(Trip trip) {
        return switch (trip.getProcessingStatus()) {
            case PROCESSING -> processing(trip.getId());
            case COMPLETED -> completed(trip.getId());
            case FAILED -> new TripProcessingStatusResponse(
                    trip.getId(),
                    ProcessingStatus.FAILED,
                    null,
                    null,
                    null,
                    new TripProcessingStatusResponse.Error("AI_PROCESSING_FAILED", "첨부 처리에 실패했습니다.")
            );
            case CANCELED -> new TripProcessingStatusResponse(
                    trip.getId(),
                    ProcessingStatus.CANCELED,
                    null,
                    null,
                    null,
                    null
            );
        };
    }

    private TripProcessingStatusResponse processing(Long tripId) {
        return new TripProcessingStatusResponse(
                tripId,
                ProcessingStatus.PROCESSING,
                null,
                null,
                null,
                null
        );
    }

    private TripProcessingStatusResponse completed(Long tripId) {
        int placeCount = Math.toIntExact(places.countByTripIdAndDeletedAtIsNull(tripId));
        int classifiedCount = Math.toIntExact(
                attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                        tripId, ClassificationStatus.ACTIVE)
        );
        int unclassifiedCount = Math.toIntExact(
                attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                        tripId, ClassificationStatus.UNCLASSIFIED)
        );
        int total = Math.addExact(classifiedCount, unclassifiedCount);

        return new TripProcessingStatusResponse(
                tripId,
                ProcessingStatus.COMPLETED,
                new TripProcessingStatusResponse.Progress(total, total),
                null,
                new TripProcessingStatusResponse.Result(tripId, placeCount, classifiedCount, unclassifiedCount),
                null
        );
    }
}
