package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TripAttachmentTransactionService {
    private final TripRepository tripRepository;
    private final StoredFileRepository storedFileRepository;
    private final TripAttachmentRepository attachmentRepository;
    private final InitialUploadExecutionRegistry executions;

    @Transactional
    public Reservation reserve(Long tripId, Long userId) {
        String executionId = executions.reserve(tripId);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status != STATUS_COMMITTED) executions.release(tripId, executionId);
                        }
                    }
            );
        }

        try {
            if (
                    tripRepository.prepareInitialUpload(
                            tripId, userId, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED
                    ) != 1
            ) {
                if (!tripRepository.existsByIdAndUserIdAndDeletedAtIsNull(tripId, userId)) {
                    throw new TripNotFoundException();
                }

                throw new TripInitialAttachmentUploadNotAllowedException();
            }

            List<TripAttachment> staleAttachments = attachmentRepository.findAllByTripId(tripId);
            List<Long> staleFileIds = staleAttachments.stream()
                    .map(TripAttachment::getFileId)
                    .toList();
            List<StoredFile> staleFiles = storedFileRepository.findAllById(staleFileIds);
            List<String> staleKeys = new ArrayList<>();

            for (StoredFile file : staleFiles) staleKeys.add(file.getObjectKey());

            for (TripAttachment attachment : staleAttachments) {
                staleKeys.add(attachment.getAnalyzeStorageKey());
                staleKeys.add(attachment.getPreviewStorageKey());
            }

            attachmentRepository.deleteAllInBatch(staleAttachments);
            storedFileRepository.deleteAllInBatch(staleFiles);

            return new Reservation(executionId, List.copyOf(staleKeys));

        } catch (RuntimeException failure) {
            executions.release(tripId, executionId);
            throw failure;
        }
    }

    @Transactional
    public SavedAttachments saveFilesAndAttachments(
            Long tripId,
            Long userId,
            String executionId,
            List<MultipartFile> files,
            List<String> objectKeys,
            List<String> mimeTypes,
            List<DerivedPhotoKeys> derived
    ) {
        if (files.size() != objectKeys.size() || files.size() != mimeTypes.size()
                || files.size() != derived.size()) {
            throw new IllegalArgumentException("원본과 파생 사진 수가 다릅니다.");
        }
        requireCurrentExecution(tripId, executionId);

        List<StoredFile> originals = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            originals.add(StoredFile.uploaded(
                    userId,
                    files.get(i).getOriginalFilename(),
                    objectKeys.get(i),
                    mimeTypes.get(i)
            ));
        }
        originals = storedFileRepository.saveAll(originals);

        List<TripAttachment> attachments = new ArrayList<>(originals.size());

        for (int i = 0; i < originals.size(); i++) {
            StoredFile original = originals.get(i);
            DerivedPhotoKeys photo = derived.get(i);

            if (!original.getObjectKey().equals(photo.originalKey())) {
                throw new IllegalArgumentException("원본과 파생 사진의 순서가 다릅니다.");
            }

            TripAttachment attachment = TripAttachment.initial(
                    tripId, original.getId(), photo.analyzeKey(), photo.previewKey());
            attachment.originalMetadata(photo.takenAt(), photo.latitude(), photo.longitude(), photo.deviceModel());
            attachments.add(attachment);
        }
        return new SavedAttachments(originals, attachmentRepository.saveAll(attachments));
    }

    @Transactional
    public void failAndDeleteReference(
            Long tripId,
            Long userId,
            String executionId,
            List<Long> fileIds,
            List<Long> attachmentIds
    ) {
        requireCurrentExecution(tripId, executionId);

        if (tripRepository.finishInitialUpload(
                tripId, userId, ProcessingStatus.PROCESSING, ProcessingStatus.FAILED) != 1) {
            throw new IllegalStateException("실패 처리할 여행 상태가 아닙니다.");
        }

        attachmentRepository.deleteAllByIdInBatch(attachmentIds);
        storedFileRepository.deleteAllByIdInBatch(fileIds);
    }

    private void requireCurrentExecution(Long tripId, String executionId) {
        if (!executions.isCurrent(tripId, executionId)) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
    }

    public record Reservation(String executionId, List<String> staleObjectKeys) {
    }

    public record SavedAttachments(List<StoredFile> originals, List<TripAttachment> attachments) {
    }
}
