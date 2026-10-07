package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;

import java.util.ArrayList;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.yeodam.yeodambe.user.service.UserStatsService;


@Service
@RequiredArgsConstructor
public class AdditionalAttachmentUploadTransactionService {

    private final TripRepository tripRepository;
    private final AdditionalAttachmentUploadBatchRepository uploadBatchRepository;
    private final AdditionalAttachmentUploadItemRepository uploadItemRepository;
    private final StoredFileRepository storedFileRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final UserStatsService userStatsService;


    @Transactional
    public void markVerified(Long tripId, Long userId, String uploadId) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripAttachmentAddNotAllowedException();
        }

        uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new)
                .markVerified();
    }

    @Transactional
    public List<TripAttachment> saveAttachments(
            Long tripId,
            Long userId,
            String uploadId,
            List<DerivedPhotoKeys> derived
    ) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripAttachmentAddNotAllowedException();
        }

        AdditionalAttachmentUploadBatch batch =
                uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                        .orElseThrow(InvalidAttachmentUploadException::new);

        if (batch.getStatus() != AdditionalAttachmentUploadStatus.VERIFIED) {
            throw new TripAttachmentAddNotAllowedException();
        }

        List<AdditionalAttachmentUploadItem> items =
                uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(
                        batch.getId()
                );

        if (items.isEmpty()) {
            throw new InvalidAttachmentUploadException();
        }

        boolean alreadySaved = items.stream()
                .anyMatch(item -> item.getTripAttachmentId() != null);

        List<TripAttachment> attachments = new ArrayList<>();

        if (alreadySaved) {
            for (AdditionalAttachmentUploadItem item : items) {
                if (item.getTripAttachmentId() == null) {
                    throw new TripAttachmentAddNotAllowedException();
                }

                TripAttachment attachment = tripAttachmentRepository
                        .findById(item.getTripAttachmentId())
                        .orElseThrow(TripAttachmentAddNotAllowedException::new);

                if (!attachment.getTripId().equals(tripId)
                        || attachment.getDeletedAt() != null
                        || attachment.getFile().getDeletedAt() != null) {
                    throw new TripAttachmentAddNotAllowedException();
                }

                attachments.add(attachment);
            }

            return attachments;
        }

        if (derived == null || derived.size() != items.size()) {
            throw new IllegalStateException(
                    TripInternalErrorMessage
                            .SOURCE_AND_DERIVED_ATTACHMENT_COUNT_MISMATCH.message()
            );
        }

        long existingCount = tripAttachmentRepository.countForEditByTripId(tripId);

        if (existingCount + items.size() > 200) {
            throw new AttachmentUploadLimitExceededException();
        }

        for (int index = 0; index < items.size(); index++) {
            AdditionalAttachmentUploadItem item = items.get(index);
            DerivedPhotoKeys photo = derived.get(index);

            if (photo == null
                    || !item.getObjectKey().equals(photo.originalKey())) {
                throw new IllegalStateException(
                        TripInternalErrorMessage
                                .SOURCE_AND_DERIVED_ATTACHMENT_ORDER_MISMATCH.message()
                );
            }

            attachments.add(saveAttachment(tripId, userId, item, photo));
        }

        tripAttachmentRepository.flush();
        userStatsService.refreshFromActiveTrips(userId);
        return attachments;
    }

    private TripAttachment saveAttachment(
            Long tripId,
            Long userId,
            AdditionalAttachmentUploadItem item,
            DerivedPhotoKeys photo
    ) {
        if (photo.originalSizeBytes() == null
                || photo.analyzeSizeBytes() == null
                || photo.previewSizeBytes() == null
                || (photo.displayKey() != null
                && photo.displaySizeBytes() == null)) {
            throw new IllegalStateException(
                    TripInternalErrorMessage.DERIVED_ATTACHMENT_RESULT_INVALID.message()
            );
        }

        StoredFile original = StoredFile.uploaded(
                userId,
                item.getOriginalFileName(),
                item.getObjectKey(),
                item.getContentType()
        );

        original.storageSize(photo.originalSizeBytes());
        StoredFile savedFile = storedFileRepository.save(original);

        TripAttachment attachment = TripAttachment.initial(
                tripId,
                savedFile.getId(),
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

        TripAttachment savedAttachment =
                tripAttachmentRepository.save(attachment);

        item.linkAttachment(savedAttachment.getId());
        item.recordTakenAt(photo.takenAt());

        return savedAttachment;
    }

    @Transactional
    public VerificationBatch loadForVerification(
            Long tripId,
            Long userId,
            String uploadId
    ) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripAttachmentAddNotAllowedException();
        }

        AdditionalAttachmentUploadBatch batch =
                uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                        .orElseThrow(InvalidAttachmentUploadException::new);

        if (batch.getStatus() != AdditionalAttachmentUploadStatus.PENDING
                && batch.getStatus() != AdditionalAttachmentUploadStatus.VERIFIED
                && batch.getStatus() != AdditionalAttachmentUploadStatus.PREPARED
                && batch.getStatus() != AdditionalAttachmentUploadStatus.COMPLETED) {
            throw new TripAttachmentAddNotAllowedException();
        }

        List<AdditionalAttachmentUploadItem> items =
                uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(
                        batch.getId()
                );

        if (items.isEmpty()) {
            throw new InvalidAttachmentUploadException();
        }

        return new VerificationBatch(batch.getStatus(), List.copyOf(items), batch.getLastBatch());
    }

    @Transactional
    public ConversionBatch claimConversion(Long tripId, Long userId, String uploadId) {
        VerificationBatch verified = loadForVerification(tripId, userId, uploadId);
        if (verified.status() != AdditionalAttachmentUploadStatus.VERIFIED) {
            throw new TripAttachmentAddNotAllowedException();
        }
        List<DerivedPhotoKeys> saved = savedDerived(tripId, verified.items());
        if (!saved.isEmpty()) return new ConversionBatch(null, verified.items(), saved);
        AdditionalAttachmentUploadBatch batch = uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        String token = UUID.randomUUID().toString();
        batch.claimConversion(token);
        return new ConversionBatch(token, verified.items(), List.of());
    }

    @Transactional
    public void saveConverted(Long tripId, Long userId, String uploadId,
                              String token, List<DerivedPhotoKeys> derived) {
        tripRepository.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        AdditionalAttachmentUploadBatch batch = uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        batch.releaseConversion(token);
        saveAttachments(tripId, userId, uploadId, derived);
    }

    @Transactional
    public void releaseConversion(Long tripId, Long userId, String uploadId, String token) {
        tripRepository.findOwnedActiveForUpdate(tripId, userId).orElseThrow(TripNotFoundException::new);
        AdditionalAttachmentUploadBatch batch = uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        batch.releaseConversion(token);
    }

    private List<DerivedPhotoKeys> savedDerived(Long tripId, List<AdditionalAttachmentUploadItem> items) {
        if (items.stream().noneMatch(item -> item.getTripAttachmentId() != null)) return List.of();
        List<DerivedPhotoKeys> result = new ArrayList<>();
        for (AdditionalAttachmentUploadItem item : items) {
            if (item.getTripAttachmentId() == null) throw new TripAttachmentAddNotAllowedException();
            TripAttachment photo = tripAttachmentRepository.findById(item.getTripAttachmentId())
                    .orElseThrow(TripAttachmentAddNotAllowedException::new);
            if (!photo.getTripId().equals(tripId) || photo.getDeletedAt() != null
                    || photo.getFile().getDeletedAt() != null) throw new TripAttachmentAddNotAllowedException();
            result.add(new DerivedPhotoKeys(item.getObjectKey(), photo.getAnalyzeStorageKey(),
                    photo.getPreviewStorageKey(), photo.getDisplayStorageKey(),
                    item.getTakenAtWithOffset() == null ? null : OffsetDateTime.parse(item.getTakenAtWithOffset()),
                    photo.getLatitude(), photo.getLongitude(), photo.getDeviceModel(),
                    photo.getFile().getOriginalSizeBytes(), photo.getAnalyzeSizeBytes(),
                    photo.getPreviewSizeBytes(), photo.getDisplaySizeBytes()));
        }
        return List.copyOf(result);
    }

    public record ConversionBatch(String token, List<AdditionalAttachmentUploadItem> items,
                                  List<DerivedPhotoKeys> saved) {}

    public record VerificationBatch(
            AdditionalAttachmentUploadStatus status,
            List<AdditionalAttachmentUploadItem> items,
            boolean lastBatch
    ) {
    }
}