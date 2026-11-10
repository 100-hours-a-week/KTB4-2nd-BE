package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
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
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import org.springframework.context.ApplicationEventPublisher;

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
import org.springframework.transaction.annotation.Propagation;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionAttemptResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult;
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
    private final TripPhotoDeletionReservationService deletionReservations;
    private final TripAnalysisExecutionRepository analyses;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdditionalAttachmentAnalysisPreparationService analysisPreparationService;
    private final AdditionalAttachmentUploadOutboxService outboxService;
    private final StoryGenerationJobRepository storyGenerationJobRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AttachmentConversionProperties conversionProperties;
    private final AttachmentConversionEnqueueService conversionEnqueue;
    private final AttachmentConversionTaskRepository conversionTasks;

    @Transactional(propagation = Propagation.MANDATORY)
    public AttachmentConversionAttemptResult startPhotoAttempt(
            AttachmentConversionClaim claim,
            Long itemId
    ) {
        var source = lockConversionSource(claim);
        var item = conversionItem(claim, itemId);
        var step = conversionStep(source, claim);
        if (step != null) {
            return attemptResult(step.status() == AttachmentConversionStepResult.Status.ACK_READY
                    ? AttachmentConversionAttemptResult.Status.ACK_READY : AttachmentConversionAttemptResult.Status.RETRY_LATER,
                    null, step.retryAt());
        }
        var task = source.task();
        if (item.getDerivedJson() != null) {
            return attemptResult(AttachmentConversionAttemptResult.Status.ALREADY_SAVED, null, null);
        }
        if (item.getConversionNextAttemptAt() != null && item.getConversionNextAttemptAt().isAfter(databaseTime())) {
            return attemptResult(AttachmentConversionAttemptResult.Status.RETRY_LATER, null, item.getConversionNextAttemptAt());
        }
        if (task.hasCurrentAttempt(itemId, claim.claimToken()) && item.getConversionStartedAt() != null
                && Objects.equals(task.getCurrentAttemptNo(), item.getConversionAttempts())) {
            return startedAttempt(claim, item);
        }
        if (item.getConversionAttempts() >= 3) {
            failConversion(source, item);
            return attemptResult(AttachmentConversionAttemptResult.Status.ACK_READY, null, null);
        }
        if (task.getCurrentItemId() != null && Objects.equals(task.getCurrentAttemptToken(), claim.claimToken())) {
            return attemptResult(AttachmentConversionAttemptResult.Status.RETRY_LATER, null, null);
        }
        startAttempt(claim.executionId(), item);
        task.startPhotoAttempt(itemId, item.getConversionAttempts(), claim.claimToken());
        return startedAttempt(claim, item);
    }

    private AttachmentConversionAttemptResult startedAttempt(AttachmentConversionClaim claim, AdditionalAttachmentUploadItem item) {
        return attemptResult(AttachmentConversionAttemptResult.Status.STARTED,
                new AttachmentConversionClaim.Attempt(item.getId(), item.getConversionAttempts(),
                        plannedPhoto(claim.executionId(), item)), null);
    }

    private AttachmentConversionAttemptResult attemptResult(
            AttachmentConversionAttemptResult.Status status,
            AttachmentConversionClaim.Attempt attempt,
            LocalDateTime retryAt
    ) {
        return new AttachmentConversionAttemptResult(status, attempt, retryAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AttachmentConversionStepResult savePhotoSuccess(
            AttachmentConversionClaim claim,
            Long itemId,
            DerivedPhotoKeys photo
    ) {
        var source = lockConversionSource(claim);
        var item = conversionItem(claim, itemId);
        int attemptNo = outputAttempt(claim.executionId(), item, photo);
        validatePhoto(claim.executionId(), item, photo, attemptNo);
        var step = conversionStep(source, claim);
        if (step != null) {
            journalLateOutput(source.task(), item, photo);
            return step;
        }
        if (item.getDerivedJson() == null) {
            if (!source.task().hasCurrentAttempt(itemId, claim.claimToken())
                    || !Objects.equals(source.task().getCurrentAttemptNo(), attemptNo)
                    || item.getConversionStartedAt() == null) {
                journalLateOutput(source.task(), item, photo);
                return stepResult(AttachmentConversionStepResult.Status.CONTINUE, null);
            }
            persistPhoto(claim.tripId(), claim.userId(), source.execution(), item, photo);
            source.task().clearPhotoAttempt();
        } else if (!Objects.equals(readDerived(item.getDerivedJson()), photo)) {
            journalLateOutput(source.task(), item, photo);
        }
        return finishConvertedBatch(source, claim);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AttachmentConversionStepResult recordPhotoFailure(
            AttachmentConversionClaim claim,
            Long itemId,
            boolean permanent
    ) {
        var source = lockConversionSource(claim);
        var item = conversionItem(claim, itemId);
        var step = conversionStep(source, claim);
        if (step != null) {
            return step;
        }
        if (item.getDerivedJson() != null) {
            return finishConvertedBatch(source, claim);
        }
        var task = source.task();
        if (item.getConversionAttempts() >= 3
                || permanent && task.hasCurrentAttempt(itemId, claim.claimToken())) {
            failConversion(source, item);
            return stepResult(AttachmentConversionStepResult.Status.ACK_READY, null);
        }
        if (task.hasCurrentAttempt(itemId, claim.claimToken())) {
            scheduleUnreferencedAttempts(item, "CONVERSION_FAILED");
            item.conversionFailed(databaseTime().plusSeconds(10));
            task.retryPhoto(item.getConversionNextAttemptAt());
        }
        return stepResult(AttachmentConversionStepResult.Status.RETRY_LATER, item.getConversionNextAttemptAt());
    }

    private void failConversion(ConversionSource source, AdditionalAttachmentUploadItem item) {
        scheduleUnreferencedAttempts(item, "CONVERSION_FAILED");
        source.task().finish(AttachmentConversionTask.Status.FAILED);
        failExecution(source.execution(), "IMAGE_CONVERSION_FAILED");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AttachmentConversionStepResult completeConvertedBatch(AttachmentConversionClaim claim) {
        var source = lockConversionSource(claim);
        var step = conversionStep(source, claim);
        return step == null ? finishConvertedBatch(source, claim) : step;
    }

    private ConversionSource lockConversionSource(AttachmentConversionClaim claim) {
        if (claim.kind() != Kind.ADDITIONAL) {
            throw new InvalidAttachmentUploadException();
        }
        entityManager.flush();
        var trip = entityManager.find(Trip.class, claim.tripId(), LockModeType.PESSIMISTIC_WRITE);
        if (trip == null) {
            throw new InvalidAttachmentUploadException();
        }
        entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
        var execution = requireExecutionForUpdate(claim.executionId());
        var batch = uploadBatchRepository.findForUpdate(claim.uploadId(), claim.tripId(), claim.userId()).orElseThrow(InvalidAttachmentUploadException::new);
        entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
        var task = conversionTasks.findByIdForUpdate(claim.taskId()).orElseThrow(InvalidAttachmentUploadException::new);
        entityManager.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        if (task.getKind() != Kind.ADDITIONAL || task.getLane() != claim.lane()
                || !Objects.equals(task.getAdditionalBatchId(), batch.getId())
                || !Objects.equals(task.getTripId(), trip.getId())
                || !Objects.equals(batch.getId(), claim.batchId())
                || !Objects.equals(batch.getAdditionId(), execution.getExecutionId())
                || !Objects.equals(trip.getUserId(), claim.userId())
                || !Objects.equals(execution.getTripId(), trip.getId())
                || !Objects.equals(execution.getUserId(), claim.userId())) {
            throw new InvalidAttachmentUploadException();
        }
        return new ConversionSource(trip, execution, batch, task);
    }

    private AttachmentConversionStepResult conversionStep(ConversionSource source, AttachmentConversionClaim claim) {
        var task = source.task();
        LocalDateTime now = databaseTime();
        if (task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(now)
                && !Objects.equals(task.getClaimToken(), claim.claimToken())) {
            return stepResult(AttachmentConversionStepResult.Status.RETRY_LATER, task.getLeaseUntil());
        }
        if (task.isTerminal()) {
            return stepResult(AttachmentConversionStepResult.Status.ACK_READY, null);
        }
        if (source.trip().getDeletedAt() != null || source.trip().getProcessingStatus() == ProcessingStatus.CANCELED) {
            failExecution(source.execution(), "USER_WITHDRAWN");
            task.finish(AttachmentConversionTask.Status.CANCELED);
            return stepResult(AttachmentConversionStepResult.Status.ACK_READY, null);
        }
        if (source.execution().isTerminal()) {
            task.finish(switch (source.execution().getStatus()) {
                case COMPLETED -> AttachmentConversionTask.Status.SUCCEEDED;
                case CANCELED -> AttachmentConversionTask.Status.CANCELED;
                default -> AttachmentConversionTask.Status.FAILED;
            });
            return stepResult(AttachmentConversionStepResult.Status.ACK_READY, null);
        }
        if (source.batch().getConversionRoute() != AttachmentConversionRoute.KAFKA
                || source.batch().getStatus() == AdditionalAttachmentUploadStatus.PENDING
                || source.trip().getProcessingStatus() != ProcessingStatus.COMPLETED
                || !task.isClaimOwner(claim.claimToken(), now)) {
            return stepResult(AttachmentConversionStepResult.Status.RETRY_LATER, null);
        }
        return null;
    }

    private AdditionalAttachmentUploadItem conversionItem(AttachmentConversionClaim claim, Long itemId) {
        var item = uploadItemRepository.findById(itemId).orElseThrow(InvalidAttachmentUploadException::new);
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(item.getBatch().getId(), claim.batchId()) || !inLane(item, claim.lane())) {
            throw new InvalidAttachmentUploadException();
        }
        return item;
    }

    private boolean inLane(AdditionalAttachmentUploadItem item, Lane lane) {
        return ("image/heic".equals(item.getContentType()) ? Lane.HEIC : Lane.LIGHT) == lane;
    }

    private AttachmentConversionStepResult finishConvertedBatch(ConversionSource source, AttachmentConversionClaim claim) {
        entityManager.flush();
        var all = uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(source.batch().getId());
        for (var item : all) {
            entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        }
        var remaining = all.stream().filter(item -> inLane(item, claim.lane()) && item.getDerivedJson() == null).toList();
        source.task().recordRemaining(remaining.size(), remaining.stream().mapToLong(AdditionalAttachmentUploadItem::getSizeBytes).sum());
        if (!remaining.isEmpty()) {
            LocalDateTime next = source.task().getNextAttemptAt();
            return stepResult(next != null && next.isAfter(databaseTime())
                    ? AttachmentConversionStepResult.Status.RETRY_LATER : AttachmentConversionStepResult.Status.CONTINUE, next);
        }
        if (all.stream().allMatch(item -> item.getDerivedJson() != null)) {
            completeBatch(source.batch());
        }
        source.task().finish(AttachmentConversionTask.Status.SUCCEEDED);
        return stepResult(AttachmentConversionStepResult.Status.ACK_READY, null);
    }

    private AttachmentConversionStepResult stepResult(AttachmentConversionStepResult.Status status, LocalDateTime retryAt) {
        return new AttachmentConversionStepResult(status, retryAt);
    }

    private void journalLateOutput(AttachmentConversionTask task, AdditionalAttachmentUploadItem item, DerivedPhotoKeys photo) {
        var protectedKeys = protectedDerivativeKeys(item);
        if (task.isClaimOwner(task.getClaimToken(), databaseTime()) && task.hasCurrentAttempt(item.getId(), task.getClaimToken())) {
            protectedKeys.addAll(derivativeKeys(plannedPhoto(item.getBatch().getAdditionId(), item)));
        }
        journalKeys(item, derivativeKeys(photo), protectedKeys, "STALE_CONVERSION");
    }

    private List<String> protectedDerivativeKeys(AdditionalAttachmentUploadItem item) {
        var keys = new ArrayList<String>();
        if (item.getDerivedJson() != null) {
            keys.addAll(derivativeKeys(readDerived(item.getDerivedJson())));
        }
        if (item.getTripAttachmentId() != null) {
            var attachment = tripAttachmentRepository.findById(item.getTripAttachmentId()).orElseThrow();
            if (attachment.getDeletedAt() == null && attachment.getFile().getDeletedAt() == null) {
                keys.add(attachment.getAnalyzeStorageKey());
                keys.add(attachment.getPreviewStorageKey());
                if (attachment.getDisplayStorageKey() != null) {
                    keys.add(attachment.getDisplayStorageKey());
                }
            }
        }
        return keys;
    }

    private void journalKeys(AdditionalAttachmentUploadItem item, List<String> keys, List<String> protectedKeys, String reason) {
        var journal = new ArrayList<>(cleanupObjects(item));
        LocalDateTime now = databaseTime();
        for (String key : keys) {
            if (!key.equals(item.getObjectKey()) && !protectedKeys.contains(key)
                    && journal.stream().noneMatch(object -> object.key().equals(key))) {
                journal.add(new CleanupObject(key, now, reason, false));
            }
        }
        recordCleanup(item, journal.stream().map(object -> keys.contains(object.key())
                && !object.key().equals(item.getObjectKey()) && !protectedKeys.contains(object.key()) && object.startedAt() == null
                ? new CleanupObject(object.key(), now, reason, object.deleted()) : object).toList());
    }

    private int outputAttempt(String executionId, AdditionalAttachmentUploadItem item, DerivedPhotoKeys photo) {
        if (photo == null) {
            throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_SOURCE_ATTACHMENT_MISMATCH.message());
        }
        for (int attempt = 1; attempt <= item.getConversionAttempts(); attempt++) {
            if (Objects.equals(photo.analyzeKey(), attemptPrefix(executionId, item.getId(), attempt) + "analyze.jpg")) {
                return attempt;
            }
        }
        throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_OUTPUT_KEY_OR_SIZE_INVALID.message());
    }

    private record ConversionSource(
            Trip trip,
            AdditionalAttachmentExecution execution,
            AdditionalAttachmentUploadBatch batch,
            AttachmentConversionTask task
    ) {
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markVerified(Long tripId, Long userId, String uploadId) {
        Trip trip = tripRepository.findOwnedActiveForUpdate(tripId, userId)
                .orElseThrow(TripNotFoundException::new);

        if (trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            throw new TripAttachmentAddNotAllowedException();
        }
        if (storyGenerationJobRepository.existsByTripIdAndStatusIn(
                tripId,
                List.of(
                        StoryGenerationJob.Status.QUEUED,
                        StoryGenerationJob.Status.PROCESSING
                )
        )) {
            throw new TripAttachmentAddNotAllowedException();
        }
        String additionId = uploadBatchRepository.findAdditionId(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        var execution = executionRepository.findForUpdate(additionId).orElse(null);
        if (execution != null) {
            entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        }
        var batch = uploadBatchRepository.findForUpdate(uploadId, tripId, userId)
                .orElseThrow(InvalidAttachmentUploadException::new);
        entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
        if (!additionId.equals(batch.getAdditionId())) {
            throw new TripAttachmentAddNotAllowedException();
        }
        if (execution == null) {
            if (conversionProperties.mode() != AttachmentConversionRoute.DB
                    || batch.getConversionRoute() != AttachmentConversionRoute.DB) {
                throw new TripAttachmentAddNotAllowedException();
            }
        } else if (!execution.getTripId().equals(tripId)
                || !execution.getUserId().equals(userId)
                || execution.isTerminal()) {
            throw new TripAttachmentAddNotAllowedException();
        }
        boolean firstAcceptance = batch.getStatus() == AdditionalAttachmentUploadStatus.PENDING;
        String requestId = org.slf4j.MDC.get("request_id");
        batch.recordRequestId(requestId == null ? batch.getAdditionId() : requestId);
        batch.markVerified(conversionProperties.mode());
        if (firstAcceptance) {
            enqueueConversion(batch);
            logUploadBatchAccepted(tripId, batch.getAdditionId(), batch.getBatchNo());
        }
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

    private void enqueueConversion(AdditionalAttachmentUploadBatch batch) {
        if (batch.getConversionRoute() != AttachmentConversionRoute.KAFKA) {
            return;
        }
        var photos = uploadItemRepository.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).stream()
                .map(item -> new AttachmentConversionSeed.Photo(
                        item.getId(), item.getContentType(), item.getSizeBytes()))
                .toList();
        conversionEnqueue.enqueue(new AttachmentConversionSeed(
                Kind.ADDITIONAL, batch.getId(), batch.getTripId(), batch.getRequestId(), photos));
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

        if (storyGenerationJobRepository.existsByTripIdAndStatusIn(
                tripId,
                List.of(
                        StoryGenerationJob.Status.QUEUED,
                        StoryGenerationJob.Status.PROCESSING
                )
        )) {
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
                batch.getLastBatch(),
                batch.getConversionRoute()
        );
    }

    @Transactional(readOnly = true)
    public List<AdditionalAttachmentUploadBatch> findPendingBatches() {
        return uploadBatchRepository.findPendingConversion(databaseTime(), PageRequest.of(0, 20));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<AdditionalAttachmentUploadBatchSnapshot> claimBatch() {
        if (conversionProperties.mode() != AttachmentConversionRoute.DB || conversionProperties.operations().enabled()) {
            return Optional.empty();
        }
        for (AdditionalAttachmentUploadBatch candidate : findPendingBatches()) {
            Optional<AdditionalAttachmentUploadBatchSnapshot> claim = claimBatch(candidate.getId());
            if (claim.isPresent()) {
                return claim;
            }
        }
        return Optional.empty();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<AdditionalAttachmentUploadBatchSnapshot> claimBatch(Long batchId) {
        if (conversionProperties.mode() != AttachmentConversionRoute.DB || conversionProperties.operations().enabled()) {
            return Optional.empty();
        }
        AdditionalAttachmentUploadBatch candidate = uploadBatchRepository.findById(batchId).orElse(null);
        if (candidate == null) {
            return Optional.empty();
        }
        Trip trip = tripRepository.findOwnedActiveForUpdate(candidate.getTripId(), candidate.getUserId())
                .orElse(null);
        if (trip == null || trip.getProcessingStatus() != ProcessingStatus.COMPLETED) {
            return Optional.empty();
        }
        AdditionalAttachmentExecution execution = executionRepository.findForUpdate(candidate.getAdditionId())
                .orElse(null);
        if (execution == null) {
            return Optional.empty();
        }
        entityManager.flush();
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        AdditionalAttachmentUploadBatch batch = uploadBatchRepository.findForUpdate(
                candidate.getUploadId(), candidate.getTripId(), candidate.getUserId()).orElse(null);
        if (batch == null) {
            return Optional.empty();
        }
        entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
        LocalDateTime now = databaseTime();
        if (batch.getConversionRoute() != AttachmentConversionRoute.DB
                || execution.isTerminal()
                || !execution.getTripId().equals(batch.getTripId())
                || !execution.getUserId().equals(batch.getUserId())
                || (batch.getStatus() != AdditionalAttachmentUploadStatus.VERIFIED
                    && batch.getStatus() != AdditionalAttachmentUploadStatus.CONVERTING)
                || (batch.getLeaseExpiresAt() != null && batch.getLeaseExpiresAt().isAfter(now))
                || (batch.getNextAttemptAt() != null && batch.getNextAttemptAt().isAfter(now))) {
            return Optional.empty();
        }
        if (batch.getStatus() == AdditionalAttachmentUploadStatus.CONVERTING) {
            batch.releaseConversion(batch.getWorkerToken());
        }
        batch.claimConversion(UUID.randomUUID().toString(), now.plusMinutes(10));
        return Optional.of(snapshot(batch));
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
        startAttempt(claim.executionId(), item);
        return derivativeKeys(plannedPhoto(claim.executionId(), item));
    }

    private void startAttempt(String executionId, AdditionalAttachmentUploadItem item) {
        scheduleUnreferencedAttempts(item, "CONVERSION_RETRY");
        item.startConversion(databaseTime());
        var journal = new ArrayList<>(cleanupObjects(item));
        derivativeKeys(plannedPhoto(executionId, item)).forEach(key -> journal.add(new CleanupObject(key, null, null, false)));
        recordCleanup(item, journal);
    }

    private void scheduleUnreferencedAttempts(AdditionalAttachmentUploadItem item, String reason) {
        recordCleanup(item, cleanupObjects(item).stream().map(object -> !object.key().equals(item.getObjectKey()) && object.startedAt() == null
                ? new CleanupObject(object.key(), databaseTime(), reason, object.deleted()) : object).toList());
    }

    private DerivedPhotoKeys plannedPhoto(String executionId, AdditionalAttachmentUploadItem item) {
        String prefix = attemptPrefix(executionId, item.getId(), item.getConversionAttempts());
        return new DerivedPhotoKeys(item.getObjectKey(), prefix + "analyze.jpg", prefix + "preview.webp",
                "image/heic".equals(item.getContentType()) ? prefix + "display.jpg" : null,
                null, null, null, null, item.getSizeBytes(), null, null, null);
    }

    private String attemptPrefix(String executionId, Long itemId, int attemptNo) {
        return "trip-additions/" + executionId + "/derived/" + itemId + "/" + attemptNo + "/";
    }

    private List<String> derivativeKeys(DerivedPhotoKeys photo) {
        return photo.displayKey() == null ? List.of(photo.analyzeKey(), photo.previewKey())
                : List.of(photo.analyzeKey(), photo.previewKey(), photo.displayKey());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void savePhotoSuccess(AdditionalAttachmentUploadBatchSnapshot claim, Long itemId, DerivedPhotoKeys photo) {
        requireCurrentClaim(claim);
        var item = requireItem(claim, itemId);
        if (item.getDerivedJson() != null) {
            return;
        }
        validatePhoto(claim.executionId(), item, photo, item.getConversionAttempts());
        persistPhoto(claim.tripId(), claim.userId(), executionRepository.findById(claim.executionId()).orElseThrow(), item, photo);
    }

    private void validatePhoto(String executionId, AdditionalAttachmentUploadItem item, DerivedPhotoKeys photo, int attemptNo) {
        if (photo == null || !item.getObjectKey().equals(photo.originalKey())
                || !Objects.equals(item.getSizeBytes(), photo.originalSizeBytes())) {
            throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_SOURCE_ATTACHMENT_MISMATCH.message());
        }
        String prefix = attemptPrefix(executionId, item.getId(), attemptNo);
        boolean heic = "image/heic".equals(item.getContentType());
        if (attemptNo < 1 || !Objects.equals(photo.analyzeKey(), prefix + "analyze.jpg")
                || !Objects.equals(photo.previewKey(), prefix + "preview.webp")
                || photo.analyzeSizeBytes() == null || photo.analyzeSizeBytes() < 1
                || photo.previewSizeBytes() == null || photo.previewSizeBytes() < 1
                || heic && (!Objects.equals(photo.displayKey(), prefix + "display.jpg")
                    || photo.displaySizeBytes() == null || photo.displaySizeBytes() < 1)
                || !heic && photo.displayKey() != null) {
            throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_OUTPUT_KEY_OR_SIZE_INVALID.message());
        }
    }

    private void persistPhoto(Long tripId, Long userId, AdditionalAttachmentExecution execution, AdditionalAttachmentUploadItem item, DerivedPhotoKeys photo) {
        try (var scope = WorkflowMdcScope.job("trip_reanalysis", tripId, execution.getExecutionId());
             var timing = startAttachmentPersistenceLogging(item.getId())) {
            var attachment = saveAttachment(tripId, userId, item, photo);
            item.conversionCompleted(objectMapper.writeValueAsString(photo));
            userStatsService.releaseInitialUploadReservation(userId, item.getSizeBytes());
            execution.accountPhoto(item.getSizeBytes());
            userStatsService.refreshFromActiveTrips(userId);
            // 같은 트랜잭션의 photosReady 검증 전에 읽기 전용 file 연관관계도 다시 채운다.
            entityManager.refresh(attachment);
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
        completeBatch(batch);
    }

    private void completeBatch(AdditionalAttachmentUploadBatch batch) {
        batch.markPrepared();
        logPhotoDerivativesPersisted(batch.getTripId(), batch.getAdditionId());
        var ready = analysisPreparationService.photosReady(batch.getTripId(), batch.getUserId(),
                batch.getUploadId(), batch.getAdditionId());
        outboxService.save(batch.getTripId(), batch.getUserId(), batch.getUploadId(),
                new AdditionalAttachmentUploadCompletionService.PreparedQueueBatch(ready, Optional.empty()),
                batch.getRequestId() == null ? batch.getAdditionId() : batch.getRequestId());
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
        cancelUploads(tripId, userId, "USER_WITHDRAWN");
    }

    @Transactional
    public void cancelForDeletion(Long tripId, Long userId) {
        cancelUploads(tripId, userId, AdditionalAttachmentExecution.Status.CANCELED.name());
    }

    private void cancelUploads(Long tripId, Long userId, String reason) {
        for (AdditionalAttachmentExecution candidate : executionRepository.findByTripIdAndUserIdAndEndedAtIsNull(tripId, userId)) {
            AdditionalAttachmentExecution execution = requireExecutionForUpdate(candidate.getExecutionId());
            terminateExecution(execution, reason, AdditionalAttachmentExecution.Status.CANCELED);
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
        failExecution(execution, errorCode);
    }

    private void failExecution(AdditionalAttachmentExecution execution, String errorCode) {
        terminateExecution(execution, errorCode, AdditionalAttachmentExecution.Status.FAILED);
    }

    private void terminateExecution(
            AdditionalAttachmentExecution execution,
            String errorCode,
            AdditionalAttachmentExecution.Status terminalStatus
    ) {
        if (execution.isTerminal()) {
            return;
        }
        String executionId = execution.getExecutionId();
        deletionReservations.settleFailedUpload(executionId);
        LocalDateTime now = databaseTime();
        logWorkflowFailed(execution, now, errorCode);
        for (AdditionalAttachmentUploadItem item : uploadItemRepository.findAdditionItems(executionId)) {
            entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
            if (item.getTripAttachmentId() != null) {
                TripAttachment attachment = tripAttachmentRepository.findById(item.getTripAttachmentId()).orElseThrow();
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
        execution.finish(terminalStatus, errorCode, now);
        deletionReservations.settleEndedAdditions(execution.getTripId());
        publishStatusChanged(execution);
    }

    private void publishStatusChanged(AdditionalAttachmentExecution execution) {
        boolean shared = execution.getProcessingMode() == AdditionalAttachmentExecution.ProcessingMode.V3;
        TripAnalysisExecution analysis = shared
                ? analyses.findForAddition(execution.getExecutionId(), PageRequest.of(0, 1)).stream().findFirst().orElse(null)
                : null;
        TripProcessingStateSnapshot snapshot = new TripProcessingStateSnapshot(
                execution.getTripId(),
                execution.getExecutionId(),
                execution.getStatusVersion(),
                execution.getDisplayStatus(),
                execution.getCurrentStep(),
                execution.getErrorCode(),
                null,
                analysis == null ? null : analysis.getExecutionId(),
                analysis == null ? null : analysis.getStatus(),
                analysis != null && analysis.getStatus() == TripAnalysisExecution.Status.APPLY_BLOCKED
                        ? analysis.getErrorCode() : null,
                shared);
        eventPublisher.publishEvent(new TripProcessingStatusChangedEvent(execution.getUserId(), snapshot));
    }

    private AdditionalAttachmentExecution requireExecutionForUpdate(String executionId) {
        var execution = executionRepository.findForUpdate(executionId).orElseThrow(InvalidAttachmentUploadException::new);
        entityManager.flush();
        entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
        return execution;
    }

    private DerivedPhotoKeys readDerived(String json) {
        return objectMapper.readerFor(DerivedPhotoKeys.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).readValue(json);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void scheduleCleanupKeys(String originalKey, List<String> keys, String reason) {
        entityManager.flush();
        for (var item : uploadItemRepository.findByObjectKey(originalKey)) {
            var hint = item.getBatch();
            var trip = entityManager.find(Trip.class, hint.getTripId(), LockModeType.PESSIMISTIC_WRITE);
            if (trip == null) {
                throw new InvalidAttachmentUploadException();
            }
            entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
            var execution = executionRepository.findForUpdate(hint.getAdditionId()).orElse(null);
            if (execution != null) {
                entityManager.refresh(execution, LockModeType.PESSIMISTIC_WRITE);
            }
            var batch = uploadBatchRepository.findForUpdate(hint.getUploadId(), hint.getTripId(), hint.getUserId()).orElseThrow();
            entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
            var task = conversionTasks.findByAdditionalBatchIdAndLane(batch.getId(), inLane(item, Lane.HEIC) ? Lane.HEIC : Lane.LIGHT)
                    .map(value -> conversionTasks.findByIdForUpdate(value.getId()).orElseThrow()).orElse(null);
            if (task != null) {
                entityManager.refresh(task, LockModeType.PESSIMISTIC_WRITE);
            }
            entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
            var protectedKeys = protectedDerivativeKeys(item);
            if (task != null && task.isClaimOwner(task.getClaimToken(), databaseTime())
                    && task.hasCurrentAttempt(item.getId(), task.getClaimToken())) {
                protectedKeys.addAll(derivativeKeys(plannedPhoto(batch.getAdditionId(), item)));
            }
            journalKeys(item, keys, protectedKeys, reason);
        }
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
        if (conversionProperties.mode() != AttachmentConversionRoute.DB || conversionProperties.operations().enabled()
                || verified.conversionRoute() != AttachmentConversionRoute.DB
                || verified.status() != AdditionalAttachmentUploadStatus.VERIFIED) {
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
            boolean lastBatch,
            AttachmentConversionRoute conversionRoute
    ) {
    }

    private void logAttachmentPersisted(WorkflowLog.Stage timing) {
        timing.committed("attachment_persistence");
    }
}
