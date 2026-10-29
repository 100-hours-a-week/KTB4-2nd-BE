package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.websocket.service.request.AiTaskCompletionType;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionName;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripCompletionSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripListItemResponse;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripCompletionSnapshotFactory {
    private final TripRepository trips;
    private final TripRegionRepository regions;
    private final TripAttachmentRepository attachments;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public TripCompletionSnapshot capture(Long tripId, Long userId,
                                          AiTaskCompletionType eventType, String rawPayload) {
        entityManager.flush();
        var trip = trips.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
        String placeSummary = regions.findNamesByTripIds(List.of(tripId)).stream()
                .map(TripRegionName::regionName).limit(3).collect(Collectors.joining(", "));
        long count = attachments.countNotDeletedByTripIds(List.of(tripId)).stream()
                .mapToLong(value -> value.attachmentCount()).sum();
        var listItem = new TripListItemResponse(
                trip.getId(), TripListItemResponse.Status.COMPLETED,
                trip.getTripName(), trip.getStartDate(), trip.getEndDate(), placeSummary,
                count, trip.getFavorite(), null, trip.getSequence());
        return new TripCompletionSnapshot(eventType, resultFromRaw(tripId, rawPayload), listItem, trip.getThumbnailKey());
    }

    public TripProcessingStatusResponse.Result resultFromRaw(Long tripId, String rawPayload) {
        if (rawPayload == null) return null;
        return TripProcessingStatusResponse.Result.fromAiResult(
                tripId, objectMapper.readTree(rawPayload).path("result"));
    }
}
