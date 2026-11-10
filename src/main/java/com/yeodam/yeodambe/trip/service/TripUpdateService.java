package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository.PhotoReadState;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import com.yeodam.yeodambe.trip.service.response.TripFavoriteResponse;
import com.yeodam.yeodambe.trip.service.response.TripUpdateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TripUpdateService {
    private final TripMutationCoordinator mutations;
    private final TripService transactions;
    private final TripRepository trips;
    private final TripEditNotificationOutboxService notifications;

    public TripUpdateResponse updateTrip(Long tripId, Long actorId, TripUpdateRequest request) {
        return mutations.execute(tripId, () -> {
            long previousSequence = trips.findPhotoReadStateById(tripId)
                    .orElseThrow(TripNotFoundException::new).getSequence();
            TripUpdateResponse updated = transactions.updateTrip(tripId, actorId, request);
            PhotoReadState current = trips.findPhotoReadStateById(tripId)
                    .orElseThrow(TripNotFoundException::new);
            if (current.getSequence() != previousSequence) {
                notifications.enqueueTripUpdated(
                        tripId, current.getSequence(), UUID.randomUUID().toString(), request, updated
                );
            }
            return updated;
        });
    }

    public TripFavoriteResponse registerFavorite(Long tripId, Long userId) {
        return mutations.execute(tripId, () -> transactions.registerFavorite(tripId, userId));
    }

    public void removeFavorite(Long tripId, Long userId) {
        mutations.run(tripId, () -> transactions.removeFavorite(tripId, userId));
    }
}
