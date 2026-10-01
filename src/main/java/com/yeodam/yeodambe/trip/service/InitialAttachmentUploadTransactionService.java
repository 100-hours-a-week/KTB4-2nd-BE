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
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;

import java.util.ArrayList;
import java.util.List;
@Service
@RequiredArgsConstructor
public class InitialAttachmentUploadTransactionService {

    private final TripRepository trips;
    private final InitialAttachmentUploadBatchRepository batches;
    private final InitialAttachmentUploadItemRepository items;
    private final StoredFileRepository files;
    private final TripAttachmentRepository attachments;

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

    @Transactional
    public List<TripAttachment> saveAttachments(
            Long tripId,
            Long userId,
            String uploadId,
            List<DerivedPhotoKeys> derived
    ) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        InitialAttachmentUploadBatch batch =
                batches.findForUpdate(uploadId, tripId, userId)
                        .orElseThrow(InvalidAttachmentUploadException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING
                || batch.getStatus() != InitialAttachmentUploadStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        List<InitialAttachmentUploadItem> uploadItems =
                items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId());

        if (derived.size() != uploadItems.size()) {
            throw new IllegalArgumentException(
                    TripInternalErrorMessage
                            .SOURCE_AND_DERIVED_ATTACHMENT_COUNT_MISMATCH.message()
            );
        }

        List<TripAttachment> savedAttachments =
                new ArrayList<>(uploadItems.size());

        for (int i = 0; i < uploadItems.size(); i++) {
            InitialAttachmentUploadItem item = uploadItems.get(i);
            DerivedPhotoKeys photo = derived.get(i);

            if (item.getTripAttachmentId() != null) {
                throw new TripInitialAttachmentUploadNotAllowedException();
            }

            if (!item.getObjectKey().equals(photo.originalKey())) {
                throw new IllegalArgumentException(
                        TripInternalErrorMessage
                                .SOURCE_AND_DERIVED_ATTACHMENT_ORDER_MISMATCH.message()
                );
            }

            StoredFile original = files.save(StoredFile.uploaded(
                    userId,
                    item.getOriginalFileName(),
                    item.getObjectKey(),
                    item.getContentType()
            ));

            TripAttachment attachment = TripAttachment.initial(
                    tripId,
                    original.getId(),
                    photo.analyzeKey(),
                    photo.previewKey(),
                    photo.displayKey()
            );

            attachment.originalMetadata(
                    photo.takenAt(),
                    photo.latitude(),
                    photo.longitude(),
                    photo.deviceModel()
            );

            TripAttachment saved = attachments.save(attachment);
            item.linkAttachment(saved.getId());
            savedAttachments.add(saved);
        }

        return savedAttachments;
    }
}
