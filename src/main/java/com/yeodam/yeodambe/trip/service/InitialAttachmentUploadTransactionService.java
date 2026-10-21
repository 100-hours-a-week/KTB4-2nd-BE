package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.common.logging.WorkflowMdcScope;
import com.yeodam.yeodambe.common.event.EventType;
import com.yeodam.yeodambe.integration.config.KafkaTopicProperties;
import com.yeodam.yeodambe.integration.service.request.OutboxReceipt;
import com.yeodam.yeodambe.trip.entity.TripCreationOutbox;
import com.yeodam.yeodambe.trip.repository.TripCreationOutboxRepository;
import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentDeliveryStatus;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.event.TripCreateRequestEvent;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.InitialAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.service.response.InitialAttachmentUploadBatchSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.user.service.UserStatsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InitialAttachmentUploadTransactionService {

    private final TripRepository trips;
    private final InitialAttachmentUploadBatchRepository batches;
    private final InitialAttachmentUploadItemRepository items;
    private final StoredFileRepository files;
    private final TripAttachmentRepository attachments;
    private final TripCreationExecutionRepository executionRepository;
    private final UserStatsService userStats;
    private final ObjectMapper objectMapper;
    private final TripRegionRepository regions;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final KafkaTopicProperties topics;
    private final TripCreationOutboxRepository outboxes;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InitialAttachmentUploadBatchSnapshot prepareBatch(
            Long tripId,
            Long userId,
            InitialAttachmentUploadUrlRequest request
    ) {
        long batchBytes = InitialAttachmentUploadUrlService.validateRequest(request);

        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        InitialAttachmentUploadBatch latest =
                batches.findFirstByTripIdAndUserIdOrderByIdDesc(tripId, userId)
                        .orElse(null);

        String executionId;
        TripCreationExecution current = executionRepository.findByTripId(tripId).orElse(null);
        if (current != null && (current.isTerminal() || current.isInputClosed())) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        if (latest == null) {
            executionId = createExecution(tripId, userId, request);
        } else {
            InitialAttachmentUploadBatch existing =
                    batches.findByExecutionIdAndBatchNo(
                            latest.getExecutionId(),
                            request.batchNo()
                    ).orElse(null);

            if (existing != null) {
                return reissueBatch(existing, request);
            }

            requireNextBatch(latest, request);

            executionId = latest.getExecutionId();
        }

        List<InitialAttachmentUploadItem> previousItems =
                latest == null
                        ? List.of()
                        : items.findAllByBatch_ExecutionId(executionId);

        validateBatchLimits(previousItems, request, batchBytes);

        TripCreationExecution execution = requireExecutionForUpdate(executionId);
        userStats.reserveInitialUpload(userId, batchBytes);
        execution.reserve(batchBytes);
        execution.recordUploadActivity(now());
        return snapshot(saveNewBatch(tripId, userId, executionId, request));
    }

    private String createExecution(
            Long tripId,
            Long userId,
            InitialAttachmentUploadUrlRequest request
    ) {
        if (request.batchNo() != 1
                || !attachments.findAllByTripIdAndDeletedAtIsNull(tripId).isEmpty()) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        String executionId = UUID.randomUUID().toString();
        executionRepository.save(new TripCreationExecution(
                executionId, tripId, userId, request.totalAttachmentCount()));
        logWorkflowStarted(tripId, executionId);
        return executionId;
    }

    private InitialAttachmentUploadBatchSnapshot reissueBatch(
            InitialAttachmentUploadBatch existing,
            InitialAttachmentUploadUrlRequest request
    ) {
        requireSameRequest(existing, request);

        if (existing.getStatus() != InitialAttachmentUploadStatus.PENDING
                && existing.getStatus() != InitialAttachmentUploadStatus.FAILED) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        TripCreationExecution execution = requireExecutionForUpdate(existing.getExecutionId());
        execution.recordUploadActivity(now());
        return snapshot(existing);
    }

    private void requireNextBatch(
            InitialAttachmentUploadBatch latest,
            InitialAttachmentUploadUrlRequest request
    ) {
        if ((latest.getStatus() != InitialAttachmentUploadStatus.ACCEPTED
                && latest.getStatus() != InitialAttachmentUploadStatus.CONVERTING
                && latest.getStatus() != InitialAttachmentUploadStatus.READY)
                || latest.getLastBatch()
                || request.batchNo() != latest.getBatchNo() + 1
                || !latest.getTotalAttachmentCount()
                .equals(request.totalAttachmentCount())) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
    }

    private void validateBatchLimits(
            List<InitialAttachmentUploadItem> previousItems,
            InitialAttachmentUploadUrlRequest request,
            long batchBytes
    ) {
        int attachmentCount =
                previousItems.size() + request.attachments().size();

        if (attachmentCount > request.totalAttachmentCount()
                || request.complete()
                != (attachmentCount == request.totalAttachmentCount())) {
            throw new InvalidAttachmentUploadException();
        }

        long totalBytes = previousItems.stream()
                .mapToLong(InitialAttachmentUploadItem::getSizeBytes)
                .sum() + batchBytes;

        if (totalBytes > 3L * 1024 * 1024 * 1024) {
            throw new AttachmentUploadLimitExceededException();
        }
    }

    private InitialAttachmentUploadBatch saveNewBatch(
            Long tripId,
            Long userId,
            String executionId,
            InitialAttachmentUploadUrlRequest request
    ) {
        InitialAttachmentUploadBatch batch = batches.save(
                new InitialAttachmentUploadBatch(
                        UUID.randomUUID().toString(),
                        executionId,
                        tripId,
                        userId,
                        request.batchNo(),
                        request.totalAttachmentCount(),
                        request.complete()
                )
        );

        List<InitialAttachmentUploadItem> uploadItems = new ArrayList<>();

        for (int i = 0; i < request.attachments().size(); i++) {
            InitialAttachmentUploadUrlRequest.Attachment attachment =
                    request.attachments().get(i);

            String objectKey = "trip-uploads/" + executionId
                    + "/original/" + UUID.randomUUID();

            uploadItems.add(new InitialAttachmentUploadItem(
                    batch,
                    i + 1,
                    attachment.fileName(),
                    attachment.contentType(),
                    attachment.sizeBytes(),
                    objectKey
            ));
        }

        for (InitialAttachmentUploadItem item : uploadItems) {
            item.recordCleanup(objectMapper.writeValueAsString(List.of(
                    new CleanupObject(item.getObjectKey(), null, null, false))), null);
        }
        items.saveAll(uploadItems);
        return batch;
    }

    private void requireSameRequest(
            InitialAttachmentUploadBatch batch,
            InitialAttachmentUploadUrlRequest request
    ) {
        List<InitialAttachmentUploadItem> savedItems =
                items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId());

        if (!batch.getTotalAttachmentCount().equals(request.totalAttachmentCount())
                || !batch.getLastBatch().equals(request.complete())
                || savedItems.size() != request.attachments().size()) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        for (int i = 0; i < savedItems.size(); i++) {
            InitialAttachmentUploadItem saved = savedItems.get(i);
            InitialAttachmentUploadUrlRequest.Attachment requested =
                    request.attachments().get(i);

            if (!saved.getOriginalFileName().equals(requested.fileName())
                    || !saved.getContentType().equals(requested.contentType())
                    || !saved.getSizeBytes().equals(requested.sizeBytes())) {
                throw new TripInitialAttachmentUploadNotAllowedException();
            }
        }
    }

    private TripAttachment saveAttachment(
            Long tripId,
            Long userId,
            InitialAttachmentUploadItem item,
            DerivedPhotoKeys photo
    ) {
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
        return saved;
    }

    private List<TripAttachment> loadConvertedAttachments(List<InitialAttachmentUploadItem> uploadItems) {
        List<Long> ids = uploadItems.stream().map(InitialAttachmentUploadItem::getTripAttachmentId).toList();
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException(TripInternalErrorMessage.UPLOAD_ITEM_ATTACHMENT_LINK_INCOMPLETE.message());
        }
        List<TripAttachment> photos = attachments.findAllById(ids);
        if (photos.size() != ids.size() || photos.stream().anyMatch(photo -> photo.getDeletedAt() != null)) {
            throw new IllegalStateException(TripInternalErrorMessage.UPLOAD_ITEM_ATTACHMENT_EXECUTION_MISMATCH.message());
        }
        Map<Long, TripAttachment> byId = photos.stream().collect(Collectors.toMap(TripAttachment::getId, photo -> photo));
        return ids.stream().map(byId::get).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public InitialAttachmentUploadBatchSnapshot loadAcceptanceCandidate(
            Long tripId, Long userId, String uploadId
    ) {
        Trip trip = trips.findByIdAndUserIdAndDeletedAtIsNull(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        InitialAttachmentUploadBatch batch = batches.findByUploadIdAndTripIdAndUserId(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);

        TripCreationExecution execution = executionRepository.findById(batch.getExecutionId())
                .orElseThrow(TripInitialAttachmentUploadNotAllowedException::new);

        if (trip.getProcessingStatus() == ProcessingStatus.CANCELED
                || execution.getStatus() == TripCreationExecution.Status.CANCELED) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        return snapshot(batch);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TripProcessingStatusResponse startProcessing(
            Long tripId, Long userId, String uploadId
    ) {
        Trip trip = trips.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);
        InitialAttachmentUploadBatch candidate = batches.findByUploadIdAndTripIdAndUserId(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        TripCreationExecution execution = requireExecutionForUpdate(candidate.getExecutionId());

        candidate = batches.findForUpdate(uploadId, tripId, userId).orElseThrow();

        entityManager.refresh(candidate, LockModeType.PESSIMISTIC_WRITE);

        if (candidate.getAcceptedAt() != null) {
            return acceptanceStatus(trip, execution);
        }

        requireActive(trip, execution);

        if (execution.isInputClosed() || candidate.getStatus() != InitialAttachmentUploadStatus.PENDING) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        candidate.accept(now());
        logUploadBatchAccepted(tripId, execution.getExecutionId(), candidate.getBatchNo());
        execution.recordUploadActivity(now());
        if (candidate.getLastBatch()) {
            List<InitialAttachmentUploadBatch> all = batches.findAllByExecutionIdOrderByBatchNoAsc(execution.getExecutionId());
            int acceptedCount = items.findExecutionItems(execution.getExecutionId()).size();

            if (all.size() != candidate.getBatchNo() || acceptedCount != execution.getTotalAttachmentCount()
                    || all.stream().anyMatch(batch -> batch.getAcceptedAt() == null)) {
                throw new InvalidAttachmentUploadException();
            }

            execution.closeInput();
            logOriginalUploadCompleted(trip, execution.getExecutionId());
            enqueueProcessIfReady(execution);
        }
        return acceptanceStatus(trip, execution);
    }

    public TripProcessingStatusResponse acceptanceStatus(
            Trip trip, TripCreationExecution execution
    ) {
        var status = switch (execution.getStatus()) {
            case COMPLETED -> TripProcessingStatusResponse.Status.COMPLETED;
            case FAILED -> TripProcessingStatusResponse.Status.FAILED;
            case CANCELED -> throw new TripInitialAttachmentUploadNotAllowedException();
            default -> TripProcessingStatusResponse.Status.PROCESSING;
        };
        return new TripProcessingStatusResponse(
                trip.getId(), status, null, null, null, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<InitialAttachmentUploadBatchSnapshot> claimBatch() {
        for (InitialAttachmentUploadBatch candidate : batches.findPendingConversion(
                now(), PageRequest.of(0, 20))) {
            Optional<InitialAttachmentUploadBatchSnapshot> claim = claimCandidate(candidate);
            if (claim.isPresent()) {
                return claim;
            }
        }
        return Optional.empty();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<InitialAttachmentUploadBatchSnapshot> claimBatch(
            Long tripId,
            Long userId,
            String uploadId
    ) {
        return batches.findByUploadIdAndTripIdAndUserId(uploadId, tripId, userId)
                .flatMap(this::claimCandidate);
    }

    private Optional<InitialAttachmentUploadBatchSnapshot> claimCandidate(
            InitialAttachmentUploadBatch candidate
    ) {
        Trip trip = trips.findOwnedActiveForUpdate(candidate.getTripId(), candidate.getUserId()).orElse(null);
        if (trip == null) {
            return Optional.empty();
        }
        TripCreationExecution execution = requireExecutionForUpdate(candidate.getExecutionId());
        InitialAttachmentUploadBatch batch = batches.findForUpdate(
                candidate.getUploadId(), candidate.getTripId(), candidate.getUserId()).orElseThrow();
        entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime currentTime = now();
        if (execution.isTerminal() || trip.getProcessingStatus() != ProcessingStatus.PROCESSING
                || (batch.getStatus() != InitialAttachmentUploadStatus.ACCEPTED
                    && batch.getStatus() != InitialAttachmentUploadStatus.CONVERTING)
                || (batch.getLeaseUntil() != null && batch.getLeaseUntil().isAfter(currentTime))
                || (batch.getNextAttemptAt() != null && batch.getNextAttemptAt().isAfter(currentTime))) {
            return Optional.empty();
        }
        batch.claim(UUID.randomUUID().toString(), currentTime.plusMinutes(10));
        return Optional.of(snapshot(batch));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean renewLease(InitialAttachmentUploadBatchSnapshot claim) {
        if (!isCurrentClaim(claim)) {
            return false;
        }
        batches.findById(claim.batchId()).orElseThrow().renew(now().plusMinutes(10));
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<String> startPhotoAttempt(
            InitialAttachmentUploadBatchSnapshot claim,
            Long itemId
    ) {
        requireCurrentClaim(claim);
        InitialAttachmentUploadItem item = requireItem(claim, itemId);
        if (item.getDerivedJson() != null || item.getConversionAttempts() >= 3) {
            throw new IllegalStateException(TripInternalErrorMessage.ATTACHMENT_CONVERSION_ATTEMPT_NOT_ALLOWED.message());
        }
        scheduleUnreferencedAttempts(item, "CONVERSION_RETRY");
        item.startConversion(now());
        String prefix = "trip-uploads/" + claim.executionId() + "/derived/"
                + item.getId() + "/" + item.getConversionAttempts() + "/";
        List<String> keys = "image/heic".equals(item.getContentType())
                ? List.of(prefix + "analyze.jpg", prefix + "preview.webp", prefix + "display.jpg")
                : List.of(prefix + "analyze.jpg", prefix + "preview.webp");
        List<CleanupObject> journal = new ArrayList<>(cleanupObjects(item));
        keys.forEach(key -> journal.add(new CleanupObject(key, null, null, false)));
        recordCleanup(item, journal);
        return keys;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void savePhotoSuccess(
            InitialAttachmentUploadBatchSnapshot claim,
            Long itemId,
            DerivedPhotoKeys photo
    ) {
        try (var scope = WorkflowMdcScope.job("trip_creation", claim.tripId(), claim.executionId());
             var timing = startAttachmentPersistenceLogging(itemId)) {
            requireCurrentClaim(claim);
            InitialAttachmentUploadItem item = requireItem(claim, itemId);
            if (!item.getObjectKey().equals(photo.originalKey()) || photo.originalSizeBytes() == null
                    || !item.getSizeBytes().equals(photo.originalSizeBytes())) {
                throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_SOURCE_ATTACHMENT_MISMATCH.message());
            }
            String prefix = "trip-uploads/" + claim.executionId() + "/derived/"
                    + itemId + "/" + item.getConversionAttempts() + "/";
            boolean heic = "image/heic".equals(item.getContentType());
            if (!Objects.equals(photo.analyzeKey(), prefix + "analyze.jpg")
                    || !Objects.equals(photo.previewKey(), prefix + "preview.webp")
                    || photo.analyzeSizeBytes() == null || photo.analyzeSizeBytes() < 1
                    || photo.previewSizeBytes() == null || photo.previewSizeBytes() < 1
                    || (heic && (!Objects.equals(photo.displayKey(), prefix + "display.jpg")
                        || photo.displaySizeBytes() == null || photo.displaySizeBytes() < 1))
                    || (!heic && photo.displayKey() != null)) {
                throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_OUTPUT_KEY_OR_SIZE_INVALID.message());
            }
            if (item.getDerivedJson() == null) {
                saveAttachment(claim.tripId(), claim.userId(), item, photo);
                item.conversionCompleted(objectMapper.writeValueAsString(photo));
            }

            logAttachmentPersisted(timing);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void retryBatch(
            InitialAttachmentUploadBatchSnapshot claim,
            Long itemId,
            boolean permanent
    ) {
        if (!isCurrentClaim(claim)) {
            return;
        }
        InitialAttachmentUploadItem item = requireItem(claim, itemId);
        if (item.getDerivedJson() != null) {
            batches.findById(claim.batchId()).orElseThrow().retry(now().plusSeconds(5));
            return;
        }
        scheduleUnreferencedAttempts(item, "CONVERSION_FAILED");
        if (permanent || item.getConversionAttempts() >= 3) {
            failExecution(claim.executionId(), ErrorMessage.IMAGE_CONVERSION_FAILED.name());
            return;
        }
        item.conversionFailed(now().plusSeconds(10));
        batches.findById(claim.batchId()).orElseThrow().retry(now().plusSeconds(10));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseSaturatedBatch(InitialAttachmentUploadBatchSnapshot claim) {
        if (isCurrentClaim(claim)) {
            batches.findById(claim.batchId()).orElseThrow().retry(now().plusSeconds(5));
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void completeBatch(InitialAttachmentUploadBatchSnapshot claim) {
        requireCurrentClaim(claim);
        InitialAttachmentUploadBatch batch = batches.findById(claim.batchId()).orElseThrow();
        List<InitialAttachmentUploadItem> all = items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId());
        if (all.stream().anyMatch(item -> item.getDerivedJson() == null)) {
            throw new IllegalStateException(TripInternalErrorMessage.ATTACHMENT_CONVERSION_INCOMPLETE.message());
        }
        List<TripAttachment> saved = loadConvertedAttachments(all);
        var refs = saved.stream().map(photo -> Map.of(
                "trip_attachment_id", photo.getId(), "analyze_storage_key", photo.getAnalyzeStorageKey())).toList();
        var event = new TripCreateRequestEvent(
                "photos_ready", claim.tripId(), claim.executionId(), claim.batchNo(), null, null, null, null,
                objectMapper.valueToTree(refs));
        batch.ready(objectMapper.writeValueAsString(event));
        logPhotoDerivativesPersisted(claim.tripId(), claim.executionId());
        saveCdcOutbox(batch.getOutboxId(), claim.executionId(), "photos_ready",
                claim.batchNo(), claim.tripId(), batch.getOutboxPayload());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failExecution(
            String executionId,
            String errorCode
    ) {
        TripCreationExecution snapshot = executionRepository.findById(executionId).orElseThrow();
        Trip trip = trips.findOwnedActiveForUpdate(snapshot.getTripId(), snapshot.getUserId()).orElse(null);
        TripCreationExecution execution = requireExecutionForUpdate(executionId);
        if (execution.isTerminal()) {
            return;
        }
        execution.finish(TripCreationExecution.Status.FAILED, errorCode, now());
        logWorkflowFailed(execution, trip, errorCode);
        releaseReservation(execution, 0);
        scheduleExecutionCleanup(execution, errorCode);
        softDeleteExecutionPhotos(execution);
        executionRepository.saveAndFlush(execution);
        if (trip != null) {
            trips.finishInitialUpload(trip.getId(), trip.getUserId(), ProcessingStatus.PROCESSING, ProcessingStatus.FAILED);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancelExecution(
            Long tripId,
            Long userId
    ) {
        TripCreationExecution current = executionRepository.findByTripId(tripId).orElse(null);
        if (current == null) {
            return;
        }
        TripCreationExecution execution = requireExecutionForUpdate(current.getExecutionId());
        if (!execution.getUserId().equals(userId) || execution.isTerminal()) {
            return;
        }
        execution.requestCancellation();
        execution.finish(TripCreationExecution.Status.CANCELED, null, now());
        logWorkflowCanceled(tripId, execution.getExecutionId());
        releaseReservation(execution, 0);
        scheduleExecutionCleanup(execution, "CANCELED");
        softDeleteExecutionPhotos(execution);
        executionRepository.saveAndFlush(execution);
    }

    private void softDeleteExecutionPhotos(TripCreationExecution execution) {
        var photoIds = items.findExecutionItems(execution.getExecutionId()).stream()
                .map(InitialAttachmentUploadItem::getTripAttachmentId).filter(Objects::nonNull).toList();
        var fileIds = attachments.findAllById(photoIds).stream().map(TripAttachment::getFileId).toList();
        attachments.softDeleteByTripId(execution.getTripId(), execution.getEndedAt());
        if (!fileIds.isEmpty()) {
            files.softDeleteByIds(fileIds, execution.getEndedAt());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failExpiredExecution(
            String id,
            boolean resultTimeout
    ) {
        var candidate = executionRepository.findById(id).orElseThrow();
        Trip trip = trips.findOwnedActiveForUpdate(candidate.getTripId(), candidate.getUserId()).orElse(null);
        var execution = requireExecutionForUpdate(id);
        if (trip == null || execution.isTerminal()) {
            return;
        }
        boolean expired = resultTimeout
                ? execution.getResultRawPayload() == null && execution.getProcessPublishedAt() != null
                    && !execution.getProcessPublishedAt().isAfter(now().minusHours(1))
                : !execution.isInputClosed() && !execution.getLastUploadActivityAt().isAfter(now().minusMinutes(10));
        if (expired) {
            failExecution(id, resultTimeout ? ErrorMessage.AI_RESULT_TIMEOUT.name() : ErrorMessage.UPLOAD_TIMEOUT.name());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void scheduleCleanupKeys(
            String originalKey,
            List<String> keys,
            String reason
    ) {
        var candidates = items.findByObjectKey(originalKey).stream().toList();
        for (var item : candidates) {
            var batch = item.getBatch();
            trips.findOwnedActiveForUpdate(batch.getTripId(), batch.getUserId());
            requireExecutionForUpdate(batch.getExecutionId());
            batches.findForUpdate(batch.getUploadId(), batch.getTripId(), batch.getUserId());
            entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
            var journal = new ArrayList<>(cleanupObjects(item));
            for (String key : keys) {
                if (journal.stream().noneMatch(object -> object.key().equals(key))) {
                    journal.add(new CleanupObject(key, now(), reason, false));
                }
            }
            recordCleanup(item, journal.stream().map(object -> keys.contains(object.key()) && object.startedAt() == null
                    ? new CleanupObject(object.key(), now(), reason, false) : object).toList());
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordObjectDeleted(
            Long itemId,
            String objectKey
    ) {
        InitialAttachmentUploadItem item = items.findById(itemId).orElseThrow();
        trips.findOwnedActiveForUpdate(item.getBatch().getTripId(), item.getBatch().getUserId());
        requireExecutionForUpdate(item.getBatch().getExecutionId());
        batches.findForUpdate(item.getBatch().getUploadId(), item.getBatch().getTripId(), item.getBatch().getUserId());
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        recordCleanup(item, cleanupObjects(item).stream().map(object -> object.key().equals(objectKey)
                ? new CleanupObject(object.key(), object.startedAt(), object.reason(), true) : object).toList());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordCancellationDelivered(String executionId) {
        var current = executionRepository.findById(executionId).orElseThrow();
        trips.findOwnedActiveForUpdate(current.getTripId(), current.getUserId());
        requireExecutionForUpdate(executionId).cancellationDelivered();
    }

    public void releaseReservation(
            TripCreationExecution execution,
            long accountedBytes
    ) {
        if (!execution.isReservationReleased()) {
            userStats.releaseInitialUploadReservation(execution.getUserId(), execution.getReservedBytes());
            execution.releaseReservation(accountedBytes);
        }
    }

    public void scheduleExecutionCleanup(
            TripCreationExecution execution,
            String reason
    ) {
        for (InitialAttachmentUploadItem item : items.findExecutionItems(execution.getExecutionId())) {
            recordCleanup(item, cleanupObjects(item).stream().map(object -> object.startedAt() == null
                    ? new CleanupObject(object.key(), execution.getEndedAt(), reason, object.deleted()) : object).toList());
        }
    }

    public List<CleanupObject> cleanupObjects(InitialAttachmentUploadItem item) {
        if (item.getCleanupJson() == null) {
            return List.of(new CleanupObject(item.getObjectKey(), null, null, false));
        }
        return objectMapper.readValue(item.getCleanupJson(), new TypeReference<List<CleanupObject>>() {});
    }

    public void recordCleanup(
            InitialAttachmentUploadItem item,
            List<CleanupObject> objects
    ) {
        LocalDateTime earliest = objects.stream().filter(object -> !object.deleted())
                .map(CleanupObject::startedAt).filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(null);
        item.recordCleanup(objectMapper.writeValueAsString(objects), earliest);
    }

    private void scheduleUnreferencedAttempts(
            InitialAttachmentUploadItem item,
            String reason
    ) {
        recordCleanup(item, cleanupObjects(item).stream().map(object ->
                !object.key().equals(item.getObjectKey()) && object.startedAt() == null
                        ? new CleanupObject(object.key(), now(), reason, object.deleted()) : object).toList());
    }

    public record CleanupObject(
            String key,
            LocalDateTime startedAt,
            String reason,
            boolean deleted
    ) {
    }

    private InitialAttachmentUploadItem requireItem(
            InitialAttachmentUploadBatchSnapshot claim,
            Long itemId
    ) {
        InitialAttachmentUploadItem item = items.findById(itemId).orElseThrow();
        if (!item.getBatch().getId().equals(claim.batchId())) {
            throw new InvalidAttachmentUploadException();
        }
        return item;
    }

    private void requireCurrentClaim(InitialAttachmentUploadBatchSnapshot claim) {
        if (!isCurrentClaim(claim)) {
            throw new CancellationException(TripInternalErrorMessage.ATTACHMENT_CONVERSION_CANCELED.message());
        }
    }

    private boolean isCurrentClaim(InitialAttachmentUploadBatchSnapshot claim) {
        Trip trip = trips.findOwnedActiveForUpdate(claim.tripId(), claim.userId()).orElse(null);
        TripCreationExecution execution = requireExecutionForUpdate(claim.executionId());
        InitialAttachmentUploadBatch batch = batches.findForUpdate(claim.uploadId(), claim.tripId(), claim.userId()).orElseThrow();
        entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
        return trip != null && trip.getProcessingStatus() == ProcessingStatus.PROCESSING && !execution.isTerminal()
                && Objects.equals(batch.getClaimToken(), claim.claimToken())
                && batch.getStatus() == InitialAttachmentUploadStatus.CONVERTING
                && batch.getLeaseUntil() != null && batch.getLeaseUntil().isAfter(now());
    }

    private void requireActive(
            Trip trip,
            TripCreationExecution execution
    ) {
        if (trip.getProcessingStatus() != ProcessingStatus.PROCESSING || execution.isTerminal()) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }
    }

    private TripCreationExecution requireExecutionForUpdate(String id) {
        var execution = executionRepository.findForUpdate(id).orElseThrow(TripInitialAttachmentUploadNotAllowedException::new);
        entityManager.flush();
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        return execution;
    }

    public LocalDateTime databaseTime() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    private LocalDateTime now() {
        return databaseTime();
    }

    private DerivedPhotoKeys readDerived(String json) {
        return objectMapper.readerFor(DerivedPhotoKeys.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .readValue(json);
    }

    private InitialAttachmentUploadBatchSnapshot snapshot(InitialAttachmentUploadBatch batch) {
        var photos = items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).stream()
                .map(item -> new InitialAttachmentUploadBatchSnapshot.Photo(
                        item.getId(), item.getOriginalFileName(), item.getContentType(), item.getSizeBytes(),
                        item.getObjectKey(), item.getConversionAttempts(), item.getConversionNextAttemptAt(),
                        item.getDerivedJson() == null ? null : readDerived(item.getDerivedJson())))
                .toList();

        return new InitialAttachmentUploadBatchSnapshot(batch.getId(), batch.getUploadId(), batch.getExecutionId(),
                batch.getTripId(), batch.getUserId(), batch.getBatchNo(), batch.getTotalAttachmentCount(),
                batch.getLastBatch(), batch.getStatus(), batch.getClaimToken(), photos);
    }
    private void enqueueProcessIfReady(TripCreationExecution execution) {
        List<InitialAttachmentUploadBatch> all = batches.findAllByExecutionIdOrderByBatchNoAsc(execution.getExecutionId());
        if (!execution.isInputClosed() || execution.getProcessPayload() != null || all.isEmpty()
                || all.stream().anyMatch(batch -> batch.getDeliveryStatus()
                        != InitialAttachmentDeliveryStatus.SENT)) {
            return;
        }
        Trip trip = trips.findById(execution.getTripId()).orElseThrow();
        List<InitialAttachmentUploadItem> originals = items.findExecutionItems(execution.getExecutionId());
        if (originals.size() != execution.getTotalAttachmentCount()) {
            throw new IllegalStateException(TripInternalErrorMessage.SOURCE_ATTACHMENT_LIST_MISMATCH.message());
        }
        List<TripPhotoAnalysisRequest.Photo> photos = originals.stream()
                .map(item -> {
                    DerivedPhotoKeys derived = readDerived(item.getDerivedJson());
                    return new TripPhotoAnalysisRequest.Photo(
                            item.getTripAttachmentId(), derived.analyzeKey(), derived.takenAt(),
                            derived.latitude(), derived.longitude(), derived.deviceModel());
                }).toList();
        var coordinates = regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(trip.getId()).stream()
                .map(region -> new TripPhotoAnalysisRequest.Region(
                        region.getLatitude(), region.getLongitude())).toList();
        var event = new TripCreateRequestEvent(
                "process", trip.getId(), execution.getExecutionId(), null, "TRIP_CREATED", trip.getTripName(),
                new TripPhotoAnalysisRequest.Period(
                        trip.getStartDate(), trip.getEndDate()), coordinates, objectMapper.valueToTree(photos));
        execution.enqueueProcess(objectMapper.writeValueAsString(event));
        saveCdcOutbox(execution.getProcessOutboxId(), execution.getExecutionId(), "process",
                0, execution.getTripId(), execution.getProcessPayload());
    }

    private void saveCdcOutbox(
            String id,
            String executionId,
            String messageType,
            int batchNo,
            Long tripId,
            String payload
    ) {
        try (var scope = WorkflowMdcScope.job("trip_creation", tripId, executionId);
             var timing = startOutboxPersistenceLogging(messageType, batchNo)) {
            outboxes.save(new TripCreationOutbox(
                    id, executionId, messageType, batchNo, tripId,
                    topics.topicFor(EventType.TRIP_CREATE_REQUEST), payload));
            logAiRequestEnqueued(timing);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordPublished(
            String outboxId,
            OutboxReceipt receipt
    ) {
        var candidate = outboxes.findById(outboxId).orElseThrow();
        var snapshot = executionRepository.findById(candidate.getExecutionId()).orElseThrow();
        Trip trip = trips.findOwnedActiveForUpdate(snapshot.getTripId(), snapshot.getUserId()).orElse(null);
        var execution = requireExecutionForUpdate(candidate.getExecutionId());
        entityManager.refresh(candidate);
        receipt.verify(candidate.getTopic(), candidate.getTripId(), candidate.getPayload());
        if (candidate.getPublishedAt() != null) {
            return;
        }
        var publishedAt = jdbc.queryForObject("SELECT FROM_UNIXTIME(?)", LocalDateTime.class,
                BigDecimal.valueOf(receipt.publishedAt().toEpochMilli(), 3));
        candidate.recordPublished(publishedAt);
        logAiRequestObserved(execution.getTripId(), execution.getExecutionId(), candidate.getMessageType());
        if ("process".equals(candidate.getMessageType())) {
            execution.processSent(publishedAt);
            return;
        }
        var batch = batches.findByExecutionIdAndBatchNo(candidate.getExecutionId(), candidate.getBatchNo())
                .orElseThrow();
        entityManager.refresh(batch);
        batch.deliverySent(publishedAt);
        if (trip != null && !execution.isTerminal()) {
            enqueueProcessIfReady(execution);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void enqueueRequest(TripCreateRequestEvent event) {
        TripCreationExecution current = executionRepository.findById(event.executionId()).orElseThrow();
        Trip trip = trips.findOwnedActiveForUpdate(current.getTripId(), current.getUserId()).orElseThrow();
        TripCreationExecution execution = requireExecutionForUpdate(event.executionId());
        requireActive(trip, execution);
        if (!trip.getId().equals(event.tripId())) {
            throw new IllegalArgumentException(TripInternalErrorMessage.EVENT_TRIP_EXECUTION_MISMATCH.message());
        }
        if ("process".equals(event.type())) {
            enqueueProcessIfReady(execution);
        } else if (!"photos_ready".equals(event.type())) {
            throw new IllegalArgumentException(TripInternalErrorMessage.UNSUPPORTED_PHOTO_REQUEST.message());
        }
    }

    private WorkflowLog.Stage startAttachmentPersistenceLogging(Long itemId) {
        return WorkflowLog.stage("attachment_persistence").field("item_id", itemId).debug();
    }

    private WorkflowLog.Stage startOutboxPersistenceLogging(String messageType, int batchNo) {
        return WorkflowLog.stage("outbox_persistence")
                .field("message_type", messageType)
                .field("batch_no", batchNo);
    }

    private void logWorkflowStarted(Long tripId, String executionId) {
        WorkflowLog.event("trip_creation", tripId, executionId, "workflow_started");
    }

    private void logPhotoDerivativesPersisted(Long tripId, String executionId) {
        WorkflowLog.event("trip_creation", tripId, executionId, "photo_derivatives_persisted");
    }

    private void logUploadBatchAccepted(Long tripId, String executionId, int batchNo) {
        WorkflowLog.batchAccepted("trip_creation", tripId, executionId, batchNo);
    }

    private void logAiRequestObserved(Long tripId, String executionId, String messageType) {
        WorkflowLog.deliveryObserved("trip_creation", tripId, executionId, messageType);
    }

    private void logOriginalUploadCompleted(Trip trip, String executionId) {
        WorkflowLog.uploadCompleted("trip_creation", trip.getId(), executionId,
                trip.getCreatedAt(), LocalDateTime.now());
    }

    private void logWorkflowFailed(TripCreationExecution execution, Trip trip, String errorCode) {
        WorkflowLog.finished("trip_creation", execution.getTripId(), execution.getExecutionId(),
                trip == null ? null : trip.getCreatedAt(), "failure", errorCode);
    }

    private void logWorkflowCanceled(Long tripId, String executionId) {
        WorkflowLog.finished("trip_creation", tripId, executionId,
                trips.findById(tripId).map(Trip::getCreatedAt).orElse(null), "canceled", null);
    }

    private void logAttachmentPersisted(WorkflowLog.Stage timing) {
        timing.committed("attachment_persistence");
    }

    private void logAiRequestEnqueued(WorkflowLog.Stage timing) {
        timing.committed("ai_request_enqueued");
    }
}
