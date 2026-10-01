package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InitialAttachmentUploadTransactionService {

    private final TripRepository trips;
    private final InitialAttachmentUploadBatchRepository batches;

    @Transactional
    public InitialAttachmentUploadBatch startProcessing(
            Long tripId,
            Long userId,
            String uploadId
    ) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new InvalidAttachmentUploadException();
        }

        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        InitialAttachmentUploadBatch batch =
                batches.findForUpdate(uploadId, tripId, userId)
                        .orElseThrow(InvalidAttachmentUploadException::new);

        if (batch.getStatus() == InitialAttachmentUploadStatus.COMPLETED) {
            return batch;
        }

        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        batch.startProcessing();

        return batch;
    }
}
