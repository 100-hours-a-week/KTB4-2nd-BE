package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TripPhotoDeletionService {
    private final TripAttachmentRepository attachments;
    private final TripPhotoDeletionReservationService reservations;
    private final TripEditLock editLock;

    public void deleteOne(Long userId, Long photoId) {
        List<Long> tripIds = attachments.findDeletionTripIds(List.of(photoId));
        if (tripIds.isEmpty()) {
            throw new AttachmentNotFoundException();
        }
        editLock.executeAll(tripIds, () -> {
            reservations.deleteOne(userId, UUID.randomUUID().toString(), photoId);
            return null;
        });
    }

    public void deleteBulk(Long userId, List<Long> photoIds) {
        TripAttachmentDeletionService.validateIds(photoIds);
        deleteOwned(userId, photoIds, attachments.findDeletionTripIds(photoIds));
    }

    private void deleteOwned(Long userId, List<Long> photoIds, List<Long> tripIds) {
        editLock.executeAll(tripIds, () -> {
            reservations.deleteOwned(userId, UUID.randomUUID().toString(), photoIds);
            return null;
        });
    }
}
