package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
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
        List<Long> tripIds = findOwnedTripIds(userId, List.of(photoId), true);
        editLock.executeAll(tripIds, () -> {
            reservations.deleteOne(userId, UUID.randomUUID().toString(), photoId);
            return null;
        });
    }

    public void deleteBulk(Long userId, List<Long> photoIds) {
        TripAttachmentDeletionService.validateIds(photoIds);
        deleteOwned(userId, photoIds, findOwnedTripIds(userId, photoIds, false));
    }

    private void deleteOwned(Long userId, List<Long> photoIds, List<Long> tripIds) {
        editLock.executeAll(tripIds, () -> {
            reservations.deleteOwned(userId, UUID.randomUUID().toString(), photoIds);
            return null;
        });
    }

    private List<Long> findOwnedTripIds(Long ownerId, List<Long> photoIds, boolean single) {
        List<TripAttachment> photos = attachments.findAllUndeletedForDeletion(photoIds);
        if (photos.size() != photoIds.size()) {
            if (single) {
                throw new AttachmentNotFoundException();
            }
            throw new InvalidAttachmentIdsException();
        }
        if (photos.stream().anyMatch(photo -> !ownerId.equals(photo.getTrip().getUserId()))) {
            if (single) {
                throw new AttachmentNotFoundException();
            }
            throw new WritePermissionRequiredException();
        }
        return photos.stream().map(TripAttachment::getTripId).distinct().sorted().toList();
    }
}
