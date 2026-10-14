package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdditionalAttachmentModificationGuard {
    private final TripRepository tripRepository;
    private final AdditionalAttachmentUploadBatchRepository uploadBatchRepository;

    public void check(Long tripId, Long userId) {
        tripRepository.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        if (uploadBatchRepository.existsByTripIdAndStatusIn(tripId, List.of(
                AdditionalAttachmentUploadStatus.PENDING, AdditionalAttachmentUploadStatus.VERIFIED,
                AdditionalAttachmentUploadStatus.CONVERTING, AdditionalAttachmentUploadStatus.PREPARED,
                AdditionalAttachmentUploadStatus.QUEUED, AdditionalAttachmentUploadStatus.PROCESSING))) {
            throw new TripAttachmentAddNotAllowedException();
        }
    }
}
