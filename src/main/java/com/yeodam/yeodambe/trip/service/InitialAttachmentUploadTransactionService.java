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
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.time.OffsetDateTime;
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
            original.storageSize(photo.originalSizeBytes());

            TripAttachment attachment = TripAttachment.initial(
                    tripId,
                    original.getId(),
                    photo.analyzeKey(),
                    photo.previewKey(),
                    photo.displayKey()
            );
            attachment.storageSizes(
                    photo.analyzeSizeBytes(),
                    photo.previewSizeBytes(),
                    photo.displaySizeBytes()
            );

            attachment.originalMetadata(
                    photo.takenAt(),
                    photo.latitude(),
                    photo.longitude(),
                    photo.deviceModel()
            );

            TripAttachment saved = attachments.save(attachment);
            item.linkAttachment(saved.getId());
            item.recordTakenAt(photo.takenAt());
            savedAttachments.add(saved);
        }

        return savedAttachments;
    }

    @Transactional
    public void completeBatch(
            Long tripId,
            Long userId,
            String uploadId
    ) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        InitialAttachmentUploadBatch batch =
                batches.findForUpdate(uploadId, tripId, userId)
                        .orElseThrow(InvalidAttachmentUploadException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        List<InitialAttachmentUploadItem> uploadItems =
                items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId());

        if (uploadItems.isEmpty()
                || uploadItems.stream()
                        .anyMatch(item -> item.getTripAttachmentId() == null)) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        batch.completeProcessing();
    }

    @Transactional
    public void failBatch(
            Long tripId,
            Long userId,
            String uploadId
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

        clearBatchReferences(batch);
        batch.failProcessing();
    }

    @Transactional
    public boolean failBatchAfterCleanup(
            Long tripId, Long userId, String uploadId, BooleanSupplier cleanup
    ) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);
        InitialAttachmentUploadBatch batch = batches.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING
                || batch.getStatus() != InitialAttachmentUploadStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        if (!cleanup.getAsBoolean()) return false;
        clearBatchReferences(batch);
        batch.failProcessing();
        return true;
    }

    @Transactional
    public boolean startAnalysis(Long tripId, Long userId, String uploadId) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);
        InitialAttachmentUploadBatch batch = batches.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING) return false;
        batch.startAnalysis();
        return true;
    }

    @Transactional
    public void failAnalysis(Long tripId, Long userId, String uploadId) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);
        InitialAttachmentUploadBatch batch = batches.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        batch.failAnalysis();
    }

    @Transactional(readOnly = true)
    public Photos loadBatchPhotos(Long batchId) {
        return photos(items.findAllByBatch_IdOrderByFileOrderAsc(batchId));
    }

    @Transactional(readOnly = true)
    public ExecutionPhotos loadExecution(Long tripId, Long userId, String uploadId) {
        Trip trip = trips.findByIdAndUserIdAndDeletedAtIsNull(tripId, userId)
                .orElseThrow(TripNotFoundException::new);
        InitialAttachmentUploadBatch last = batches.findFirstByTripIdAndUserIdOrderByIdDesc(tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        if (!last.getUploadId().equals(uploadId) || !last.getLastBatch()
                || trip.getProcessingStatus() != ProcessingStatus.PROCESSING
                || last.getStatus() != InitialAttachmentUploadStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
        List<InitialAttachmentUploadBatch> all = batches.findAllByExecutionIdOrderByBatchNoAsc(last.getExecutionId());
        if (all.size() != last.getBatchNo()) throw new InvalidAttachmentUploadException();
        for (int i = 0; i < all.size(); i++) {
            InitialAttachmentUploadBatch batch = all.get(i);
            if (batch.getBatchNo() != i + 1 || (i < all.size() - 1
                    && batch.getStatus() != InitialAttachmentUploadStatus.COMPLETED)) {
                throw new TripInitialAttachmentUploadNotAllowedException();
            }
        }
        List<InitialAttachmentUploadItem> allItems = items.findExecutionItems(last.getExecutionId());
        if (allItems.size() != last.getTotalAttachmentCount()) throw new InvalidAttachmentUploadException();
        return new ExecutionPhotos(trip, photos(allItems));
    }

    private Photos photos(List<InitialAttachmentUploadItem> uploadItems) {
        if (uploadItems.isEmpty() || uploadItems.stream().anyMatch(item -> item.getTripAttachmentId() == null)) {
            throw new InvalidAttachmentUploadException();
        }
        Map<Long, TripAttachment> byId = attachments.findAllById(uploadItems.stream()
                        .map(InitialAttachmentUploadItem::getTripAttachmentId).toList()).stream()
                .collect(Collectors.toMap(TripAttachment::getId, photo -> photo));
        List<TripAttachment> ordered = new ArrayList<>();
        List<DerivedPhotoKeys> metadata = new ArrayList<>();
        for (InitialAttachmentUploadItem item : uploadItems) {
            TripAttachment photo = byId.get(item.getTripAttachmentId());
            if (photo == null || photo.getDeletedAt() != null
                    || !photo.getTripId().equals(item.getBatch().getTripId())) {
                throw new TripInitialAttachmentUploadNotAllowedException();
            }
            ordered.add(photo);
            metadata.add(new DerivedPhotoKeys(item.getObjectKey(), photo.getAnalyzeStorageKey(),
                    photo.getPreviewStorageKey(), photo.getDisplayStorageKey(),
                    item.getTakenAtWithOffset() == null ? null : OffsetDateTime.parse(item.getTakenAtWithOffset()),
                    photo.getLatitude(), photo.getLongitude(), photo.getDeviceModel()));
        }
        return new Photos(List.copyOf(ordered), List.copyOf(metadata));
    }

    public record Photos(List<TripAttachment> attachments, List<DerivedPhotoKeys> metadata) {}

    public record ExecutionPhotos(Trip trip, Photos photos) {}

    @Transactional(readOnly = true)
    public boolean isAnalyzing(Long tripId, Long userId, String uploadId) {
        return trips.existsByIdAndUserIdAndDeletedAtIsNullAndProcessingStatus(
                tripId, userId, ProcessingStatus.PROCESSING)
                && batches.findFirstByTripIdAndUserIdOrderByIdDesc(tripId, userId)
                .filter(batch -> batch.getUploadId().equals(uploadId)
                        && batch.getStatus() == InitialAttachmentUploadStatus.ANALYZING).isPresent();
    }

    private void clearBatchReferences(InitialAttachmentUploadBatch batch) {

        List<InitialAttachmentUploadItem> uploadItems =
                items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId());

        List<Long> attachmentIds = uploadItems.stream()
                .map(InitialAttachmentUploadItem::getTripAttachmentId)
                .filter(id -> id != null)
                .toList();

        List<Long> fileIds = attachments.findAllById(attachmentIds).stream()
                .map(TripAttachment::getFileId)
                .toList();

        for (InitialAttachmentUploadItem item : uploadItems) {
            item.linkAttachment(null);
            item.recordTakenAt(null);
        }

        items.flush();

        if (!attachmentIds.isEmpty()) {
            attachments.deleteAllByIdInBatch(attachmentIds);
        }

        if (!fileIds.isEmpty()) {
            files.deleteAllByIdInBatch(fileIds);
        }

    }
}
