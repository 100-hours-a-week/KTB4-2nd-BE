package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;

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
import java.util.Optional;
import java.util.Objects;
import tools.jackson.core.type.TypeReference;
import java.time.LocalDateTime;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.service.response.AdditionalAttachmentUploadBatchSnapshot;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
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
    private final AdditionalAttachmentExecutionRepository executionRepository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdditionalAttachmentAnalysisPreparationService analysisPreparationService;
    private final AdditionalAttachmentUploadOutboxService outboxService;


    @Transactional
    public void markVerified(Long tripId, Long userId, String uploadId) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripAttachmentAddNotAllowedException();
        }

        var batch = uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        var execution = executionRepository.findForUpdate(batch.getAdditionId()).orElse(null);
        if (execution != null && execution.isTerminal()) {
            throw new TripAttachmentAddNotAllowedException();
        }
        boolean firstAcceptance = batch.getStatus() == AdditionalAttachmentUploadStatus.PENDING;
        batch.markVerified();
        if (firstAcceptance) {
            logUploadBatchAccepted(tripId, batch.getAdditionId(), batch.getBatchNo());
        }
        String requestId = org.slf4j.MDC.get("request_id");
        batch.recordRequestId(requestId == null ? batch.getAdditionId() : requestId);
        if (execution != null) {
            execution.recordUploadActivity(databaseTime());
            if (batch.getLastBatch()) {
                boolean firstCompletion = !execution.isInputClosed();
                execution.closeInput();
                if (firstCompletion) {
                    logOriginalUploadCompleted(tripId, execution);
                }
            }
        }
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
                && batch.getStatus() != AdditionalAttachmentUploadStatus.CONVERTING
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

        return new VerificationBatch(
                batch.getAdditionId(),
                batch.getStatus(),
                List.copyOf(items),
                batch.getLastBatch()
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<AdditionalAttachmentUploadBatchSnapshot> claimBatch() {
        for (AdditionalAttachmentUploadBatch candidate : uploadBatchRepository.findPendingConversion(
                databaseTime(), PageRequest.of(0, 20))) {
            Trip trip = tripRepository.findOwnedActiveForUpdate(candidate.getTripId(), candidate.getUserId())
                    .orElse(null);
            if (trip == null || trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
                continue;
            }
            AdditionalAttachmentExecution execution = executionRepository.findForUpdate(candidate.getAdditionId())
                    .orElse(null);
            if (execution == null) {
                continue;
            }
            entityManager.flush();
            entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
            AdditionalAttachmentUploadBatch batch = uploadBatchRepository.findForUpdate(
                    candidate.getUploadId(), candidate.getTripId(), candidate.getUserId()).orElse(null);
            if (batch == null) {
                continue;
            }
            entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
            LocalDateTime now = databaseTime();
            if (execution.isTerminal()
                    || !execution.getTripId().equals(batch.getTripId())
                    || !execution.getUserId().equals(batch.getUserId())
                    || (batch.getStatus() != AdditionalAttachmentUploadStatus.VERIFIED
                        && batch.getStatus() != AdditionalAttachmentUploadStatus.CONVERTING)
                    || (batch.getLeaseExpiresAt() != null && batch.getLeaseExpiresAt().isAfter(now))
                    || (batch.getNextAttemptAt() != null && batch.getNextAttemptAt().isAfter(now))) {
                continue;
            }
            if (batch.getStatus() == AdditionalAttachmentUploadStatus.CONVERTING) {
                batch.releaseConversion(batch.getWorkerToken());
            }
            batch.claimConversion(UUID.randomUUID().toString(), now.plusMinutes(10));
            return Optional.of(snapshot(batch));
        }
        return Optional.empty();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean renewLease(AdditionalAttachmentUploadBatchSnapshot claim) {
        AdditionalAttachmentUploadBatch batch = currentClaim(claim);
        if (batch == null) {
            return false;
        }
        batch.renewConversion(claim.workerToken(), databaseTime().plusMinutes(10));
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<String> startPhotoAttempt(AdditionalAttachmentUploadBatchSnapshot claim, Long itemId) {
        requireCurrentClaim(claim);
        AdditionalAttachmentUploadItem item = requireItem(claim, itemId);
        if (item.getDerivedJson() != null || item.getConversionAttempts() >= 3) {
            throw new IllegalStateException(TripInternalErrorMessage.ATTACHMENT_CONVERSION_ATTEMPT_NOT_ALLOWED.message());
        }
        LocalDateTime now = databaseTime();
        if (item.getConversionNextAttemptAt() != null && item.getConversionNextAttemptAt().isAfter(now)) {
            throw new TripAttachmentAddNotAllowedException();
        }
        var journal = new ArrayList<>(cleanupObjects(item));
        journal.replaceAll(object -> !object.key().equals(item.getObjectKey()) && object.startedAt() == null
                ? new CleanupObject(object.key(), now, "CONVERSION_RETRY", object.deleted()) : object);
        item.startConversion(now);
        String prefix = derivativePrefix(claim, item);
        List<String> keys = "image/heic".equals(item.getContentType())
                ? List.of(prefix + "analyze.jpg", prefix + "preview.webp", prefix + "display.jpg")
                : List.of(prefix + "analyze.jpg", prefix + "preview.webp");
        keys.forEach(key -> journal.add(new CleanupObject(key, null, null, false)));
        recordCleanup(item, journal);
        return keys;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void savePhotoSuccess(AdditionalAttachmentUploadBatchSnapshot claim, Long itemId, DerivedPhotoKeys photo) {
        try (var scope = WorkflowMdcScope.job("trip_reanalysis", claim.tripId(), claim.executionId());
             var timing = startAttachmentPersistenceLogging(itemId)) {
            requireCurrentClaim(claim);
            AdditionalAttachmentUploadItem item = requireItem(claim, itemId);
            if (item.getDerivedJson() != null) {
                logAttachmentAlreadyPersisted(timing);
                return;
            }
            if (photo == null || !item.getObjectKey().equals(photo.originalKey())
                    || !Objects.equals(item.getSizeBytes(), photo.originalSizeBytes())) {
                throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_SOURCE_ATTACHMENT_MISMATCH.message());
            }
            String prefix = derivativePrefix(claim, item);
            boolean heic = "image/heic".equals(item.getContentType());
            if (item.getConversionAttempts() < 1
                    || !Objects.equals(photo.analyzeKey(), prefix + "analyze.jpg")
                    || !Objects.equals(photo.previewKey(), prefix + "preview.webp")
                    || photo.analyzeSizeBytes() == null || photo.analyzeSizeBytes() < 1
                    || photo.previewSizeBytes() == null || photo.previewSizeBytes() < 1
                    || (heic && (!Objects.equals(photo.displayKey(), prefix + "display.jpg")
                        || photo.displaySizeBytes() == null || photo.displaySizeBytes() < 1))
                    || (!heic && photo.displayKey() != null)) {
                throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_OUTPUT_KEY_OR_SIZE_INVALID.message());
            }
            saveAttachment(claim.tripId(), claim.userId(), item, photo);
            item.conversionCompleted(objectMapper.writeValueAsString(photo));
            AdditionalAttachmentExecution execution = executionRepository.findById(claim.executionId()).orElseThrow();
            userStatsService.releaseInitialUploadReservation(claim.userId(), item.getSizeBytes());
            execution.accountPhoto(item.getSizeBytes());
            userStatsService.refreshFromActiveTrips(claim.userId());

            logAttachmentPersisted(timing);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseSaturatedBatch(AdditionalAttachmentUploadBatchSnapshot claim) {
        AdditionalAttachmentUploadBatch batch = currentClaim(claim);
        if (batch != null) {
            batch.retryConversion(claim.workerToken(), databaseTime().plusSeconds(5));
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void completeBatch(AdditionalAttachmentUploadBatchSnapshot claim) {
        var batch = currentClaim(claim);
        if (batch == null) {
            return;
        }
        var items = uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(batch.getId());
        if (items.stream().anyMatch(item -> item.getDerivedJson() == null)) {
            throw new TripAttachmentAddNotAllowedException();
        }
        batch.releaseConversion(claim.workerToken());
        batch.markPrepared();
        logPhotoDerivativesPersisted(claim.tripId(), claim.executionId());
        var ready = analysisPreparationService.photosReady(claim.tripId(), claim.userId(),
                claim.uploadId(), claim.executionId());
        outboxService.save(claim.tripId(), claim.userId(), claim.uploadId(),
                new AdditionalAttachmentUploadCompletionService.PreparedQueueBatch(ready, Optional.empty()),
                batch.getRequestId() == null ? claim.executionId() : batch.getRequestId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void retryBatch(AdditionalAttachmentUploadBatchSnapshot claim, Long itemId, boolean permanent) {
        var batch = currentClaim(claim);
        if (batch == null) {
            return;
        }
        var item = requireItem(claim, itemId);
        if (permanent || item.getConversionAttempts() >= 3) {
            failExecution(claim.executionId(), "IMAGE_CONVERSION_FAILED");
        } else {
            var next = databaseTime().plusSeconds(10);
            item.conversionFailed(next);
            batch.retryConversion(claim.workerToken(), next);
        }
    }

    @Transactional
    public void cancelForWithdrawal(Long tripId, Long userId) {
        for (var execution : executionRepository.findByTripIdAndUserIdAndEndedAtIsNull(tripId, userId)) {
            failExecution(execution.getExecutionId(), "USER_WITHDRAWN");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failExecution(String executionId, String errorCode) {
        var candidate = executionRepository.findById(executionId).orElse(null);
        if (candidate == null) {
            return;
        }
        tripRepository.findOwnedActiveForUpdate(candidate.getTripId(), candidate.getUserId())
                .orElseThrow(TripNotFoundException::new);
        var execution = executionRepository.findForUpdate(executionId).orElseThrow();
        entityManager.flush();
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        if (execution.isTerminal()) {
            return;
        }
        var now = databaseTime();
        logWorkflowFailed(execution, now, errorCode);
        for (var item : uploadItemRepository.findAdditionItems(executionId)) {
            if (item.getTripAttachmentId() != null) {
                var attachment = tripAttachmentRepository.findById(item.getTripAttachmentId()).orElseThrow();
                attachment.softDelete(now);
                attachment.getFile().softDelete(now);
            }
            recordCleanup(item, cleanupObjects(item).stream().map(object -> object.startedAt() == null
                    ? new CleanupObject(object.key(), now, errorCode, object.deleted()) : object).toList());
        }
        uploadBatchRepository.findAllByAdditionIdOrderByBatchNoAsc(executionId)
                .forEach(AdditionalAttachmentUploadBatch::abort);
        if (!execution.isReservationReleased()) {
            userStatsService.releaseInitialUploadReservation(execution.getUserId(), execution.getReservedBytes());
            execution.releaseReservation(0);
        }
        entityManager.flush();
        userStatsService.refreshFromActiveTrips(execution.getUserId());
        execution.finish(AdditionalAttachmentExecution.Status.FAILED, errorCode, now);
    }

    private String derivativePrefix(AdditionalAttachmentUploadBatchSnapshot claim, AdditionalAttachmentUploadItem item) {
        return "trip-additions/" + claim.executionId() + "/derived/"
                + item.getId() + "/" + item.getConversionAttempts() + "/";
    }

    private AdditionalAttachmentUploadItem requireItem(AdditionalAttachmentUploadBatchSnapshot claim, Long itemId) {
        AdditionalAttachmentUploadItem item = uploadItemRepository.findById(itemId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        if (!item.getBatch().getId().equals(claim.batchId())) {
            throw new InvalidAttachmentUploadException();
        }
        entityManager.flush();
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        return item;
    }

    private void requireCurrentClaim(AdditionalAttachmentUploadBatchSnapshot claim) {
        if (currentClaim(claim) == null) {
            throw new TripAttachmentAddNotAllowedException();
        }
    }

    public List<CleanupObject> cleanupObjects(AdditionalAttachmentUploadItem item) {
        return item.getCleanupJson() == null
                ? List.of(new CleanupObject(item.getObjectKey(), null, null, false))
                : objectMapper.readValue(item.getCleanupJson(), new TypeReference<List<CleanupObject>>() {});
    }

    @Transactional
    public void recordObjectDeleted(Long itemId, String key) {
        var item = uploadItemRepository.findById(itemId).orElseThrow();
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        recordCleanup(item, cleanupObjects(item).stream().map(object -> object.key().equals(key)
                ? new CleanupObject(object.key(), object.startedAt(), object.reason(), true) : object).toList());
    }

    private void recordCleanup(AdditionalAttachmentUploadItem item, List<CleanupObject> objects) {
        LocalDateTime earliest = objects.stream().filter(object -> !object.deleted())
                .map(CleanupObject::startedAt).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        item.recordCleanup(objectMapper.writeValueAsString(objects), earliest);
    }

    public record CleanupObject(
            String key,
            LocalDateTime startedAt,
            String reason,
            boolean deleted
    ) {
    }

    private AdditionalAttachmentUploadBatch currentClaim(AdditionalAttachmentUploadBatchSnapshot claim) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(claim.tripId(), claim.userId()).orElse(null);
        if (trip == null || trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            return null;
        }
        AdditionalAttachmentExecution execution = executionRepository.findForUpdate(claim.executionId()).orElse(null);
        if (execution == null) {
            return null;
        }
        entityManager.flush();
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        if (execution.isTerminal() || !execution.getTripId().equals(claim.tripId())
                || !execution.getUserId().equals(claim.userId())) {
            return null;
        }
        AdditionalAttachmentUploadBatch batch = uploadBatchRepository.findForUpdate(
                claim.uploadId(), claim.tripId(), claim.userId()).orElse(null);
        if (batch == null) {
            return null;
        }
        entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
        return batch.getAdditionId().equals(claim.executionId())
                && batch.getId().equals(claim.batchId())
                && batch.isConversionOwner(claim.workerToken())
                && batch.getLeaseExpiresAt() != null
                && batch.getLeaseExpiresAt().isAfter(databaseTime()) ? batch : null;
    }

    public LocalDateTime databaseTime() {
        return jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    private AdditionalAttachmentUploadBatchSnapshot snapshot(AdditionalAttachmentUploadBatch batch) {
        var photos = uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).stream()
                .map(item -> new AdditionalAttachmentUploadBatchSnapshot.Photo(
                        item.getId(), item.getOriginalFileName(), item.getContentType(), item.getSizeBytes(),
                        item.getObjectKey(), item.getConversionAttempts(), item.getConversionNextAttemptAt(),
                        item.getDerivedJson() == null ? null : objectMapper.readerFor(DerivedPhotoKeys.class)
                                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                                .readValue(item.getDerivedJson())))
                .toList();
        return new AdditionalAttachmentUploadBatchSnapshot(batch.getId(), batch.getUploadId(), batch.getAdditionId(),
                batch.getTripId(), batch.getUserId(), batch.getWorkerToken(),
                batch.getRequestId() == null ? batch.getAdditionId() : batch.getRequestId(), photos);
    }

    @Transactional
    public ConversionBatch claimConversion(Long tripId, Long userId, String uploadId) {
        VerificationBatch verified = loadForVerification(tripId, userId, uploadId);
        if (verified.status() != AdditionalAttachmentUploadStatus.VERIFIED) {
            throw new TripAttachmentAddNotAllowedException();
        }
        List<DerivedPhotoKeys> saved = savedDerived(tripId, verified.items());
        if (!saved.isEmpty()) {
            return new ConversionBatch(null, verified.items(), saved);
        }
        AdditionalAttachmentUploadBatch batch = uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        String token = UUID.randomUUID().toString();
        batch.claimConversion(token);
        return new ConversionBatch(token, verified.items(), List.of());
    }

    @Transactional
    public void saveConverted(
            Long tripId,
            Long userId,
            String uploadId,
            String token,
            List<DerivedPhotoKeys> derived
    ) {
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
        if (items.stream().noneMatch(item -> item.getTripAttachmentId() != null)) {
            return List.of();
        }
        List<DerivedPhotoKeys> result = new ArrayList<>();
        for (AdditionalAttachmentUploadItem item : items) {
            if (item.getTripAttachmentId() == null) {
                throw new TripAttachmentAddNotAllowedException();
            }
            TripAttachment photo = tripAttachmentRepository.findById(item.getTripAttachmentId())
                    .orElseThrow(TripAttachmentAddNotAllowedException::new);
            if (!photo.getTripId().equals(tripId) || photo.getDeletedAt() != null
                    || photo.getFile().getDeletedAt() != null) {
                throw new TripAttachmentAddNotAllowedException();
            }
            result.add(new DerivedPhotoKeys(item.getObjectKey(), photo.getAnalyzeStorageKey(),
                    photo.getPreviewStorageKey(), photo.getDisplayStorageKey(),
                    item.getTakenAtWithOffset() == null ? null : OffsetDateTime.parse(item.getTakenAtWithOffset()),
                    photo.getLatitude(), photo.getLongitude(), photo.getDeviceModel(),
                    photo.getFile().getOriginalSizeBytes(), photo.getAnalyzeSizeBytes(),
                    photo.getPreviewSizeBytes(), photo.getDisplaySizeBytes()));
        }
        return List.copyOf(result);
    }

    public record ConversionBatch(
            String token,
            List<AdditionalAttachmentUploadItem> items,
            List<DerivedPhotoKeys> saved
    ) {
    }

    private WorkflowLog.Stage startAttachmentPersistenceLogging(Long itemId) {
        return WorkflowLog.stage("attachment_persistence").field("item_id", itemId).debug();
    }

    private void logAttachmentAlreadyPersisted(WorkflowLog.Stage timing) {
        timing.field("saved_count", 0).success();
    }

    private void logPhotoDerivativesPersisted(Long tripId, String executionId) {
        WorkflowLog.event("trip_reanalysis", tripId, executionId, "photo_derivatives_persisted");
    }

    private void logUploadBatchAccepted(Long tripId, String executionId, int batchNo) {
        WorkflowLog.batchAccepted("trip_reanalysis", tripId, executionId, batchNo);
    }

    private void logOriginalUploadCompleted(Long tripId, AdditionalAttachmentExecution execution) {
        WorkflowLog.uploadCompleted("trip_reanalysis", tripId, execution.getExecutionId(),
                execution.getCreatedAt(), databaseTime());
    }

    private void logWorkflowFailed(AdditionalAttachmentExecution execution, LocalDateTime now, String errorCode) {
        WorkflowLog.finishedFromDatabase("trip_reanalysis", execution.getTripId(), execution.getExecutionId(),
                execution.getCreatedAt(), now, "failure", errorCode);
    }

    public record VerificationBatch(
            String additionId,
            AdditionalAttachmentUploadStatus status,
            List<AdditionalAttachmentUploadItem> items,
            boolean lastBatch
    ) {
    }

    private void logAttachmentPersisted(WorkflowLog.Stage timing) {
        timing.committed("attachment_persistence");
    }
}
