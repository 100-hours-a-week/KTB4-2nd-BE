package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.logging.WorkflowLog;
import com.yeodam.yeodambe.integration.service.WorkflowTraceService;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionAttemptResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
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
import com.yeodam.yeodambe.trip.event.TripProcessingStatusChangedEvent;
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
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import com.yeodam.yeodambe.user.service.UserStatsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
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

@Slf4j
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
    private final ApplicationEventPublisher eventPublisher;
    private final AttachmentConversionAdmissionService conversionAdmission;
    private final AttachmentConversionProperties conversionProperties;
    private final AttachmentConversionEnqueueService conversionEnqueue;
    private final AttachmentConversionTaskRepository conversionTasks;
    private final WorkflowTraceService traces;

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
        if (item.getConversionNextAttemptAt() != null && item.getConversionNextAttemptAt().isAfter(now())) {
            return attemptResult(AttachmentConversionAttemptResult.Status.RETRY_LATER, null, item.getConversionNextAttemptAt());
        }
        if (task.hasCurrentAttempt(itemId, claim.claimToken()) && item.getConversionStartedAt() != null
                && Objects.equals(task.getCurrentAttemptNo(), item.getConversionAttempts())) {
            return startedAttempt(claim, item);
        }
        if (item.getConversionAttempts() >= 3) {
            failConversion(source, claim, item);
            return attemptResult(AttachmentConversionAttemptResult.Status.ACK_READY, null, null);
        }
        if (task.getCurrentItemId() != null && Objects.equals(task.getCurrentAttemptToken(), claim.claimToken())) {
            return attemptResult(AttachmentConversionAttemptResult.Status.RETRY_LATER, null, null);
        }
        startAttempt(claim.executionId(), item);
        task.startPhotoAttempt(itemId, item.getConversionAttempts(), claim.claimToken());
        return startedAttempt(claim, item);
    }

    private AttachmentConversionAttemptResult startedAttempt(AttachmentConversionClaim claim, InitialAttachmentUploadItem item) {
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
            persistPhoto(claim.tripId(), claim.userId(), claim.executionId(), item, photo);
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
            failConversion(source, claim, item);
            return stepResult(AttachmentConversionStepResult.Status.ACK_READY, null);
        }
        if (task.hasCurrentAttempt(itemId, claim.claimToken())) {
            scheduleUnreferencedAttempts(item, "CONVERSION_FAILED");
            item.conversionFailed(now().plusSeconds(10));
            task.retryPhoto(item.getConversionNextAttemptAt());
        }
        return stepResult(AttachmentConversionStepResult.Status.RETRY_LATER, item.getConversionNextAttemptAt());
    }

    private void failConversion(ConversionSource source, AttachmentConversionClaim claim, InitialAttachmentUploadItem item) {
        scheduleUnreferencedAttempts(item, "CONVERSION_FAILED");
        // 기존 실패 처리의 bulk update가 영속성 컨텍스트를 비우기 전에 task도 저장한다.
        source.task().finish(AttachmentConversionTask.Status.FAILED);
        failExecution(claim.executionId(), ErrorMessage.IMAGE_CONVERSION_FAILED.name());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AttachmentConversionStepResult completeConvertedBatch(AttachmentConversionClaim claim) {
        var source = lockConversionSource(claim);
        var step = conversionStep(source, claim);
        return step == null ? finishConvertedBatch(source, claim) : step;
    }

    private ConversionSource lockConversionSource(AttachmentConversionClaim claim) {
        if (claim.kind() != Kind.INITIAL) {
            throw new InvalidAttachmentUploadException();
        }
        entityManager.flush();
        var trip = entityManager.find(Trip.class, claim.tripId(), LockModeType.PESSIMISTIC_WRITE);
        if (trip == null) {
            throw new InvalidAttachmentUploadException();
        }
        entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
        var execution = requireExecutionForUpdate(claim.executionId());
        var batch = batches.findForUpdate(claim.uploadId(), claim.tripId(), claim.userId()).orElseThrow(InvalidAttachmentUploadException::new);
        entityManager.refresh(batch, LockModeType.PESSIMISTIC_WRITE);
        var task = conversionTasks.findByIdForUpdate(claim.taskId()).orElseThrow(InvalidAttachmentUploadException::new);
        entityManager.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        if (task.getKind() != Kind.INITIAL || task.getLane() != claim.lane()
                || !Objects.equals(task.getInitialBatchId(), batch.getId())
                || !Objects.equals(task.getTripId(), trip.getId())
                || !Objects.equals(batch.getId(), claim.batchId())
                || !Objects.equals(batch.getExecutionId(), execution.getExecutionId())
                || !Objects.equals(trip.getUserId(), claim.userId())
                || !Objects.equals(execution.getTripId(), trip.getId())
                || !Objects.equals(execution.getUserId(), claim.userId())) {
            throw new InvalidAttachmentUploadException();
        }
        return new ConversionSource(trip, execution, batch, task);
    }

    private AttachmentConversionStepResult conversionStep(ConversionSource source, AttachmentConversionClaim claim) {
        var task = source.task();
        LocalDateTime now = now();
        if (task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(now)
                && !Objects.equals(task.getClaimToken(), claim.claimToken())) {
            return stepResult(AttachmentConversionStepResult.Status.RETRY_LATER, task.getLeaseUntil());
        }
        if (task.isTerminal()) {
            return stepResult(AttachmentConversionStepResult.Status.ACK_READY, null);
        }
        if (source.trip().getDeletedAt() != null || source.trip().getProcessingStatus() == ProcessingStatus.CANCELED) {
            cancelExecution(claim.tripId(), claim.userId());
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
                || source.batch().getAcceptedAt() == null
                || source.batch().getStatus() == InitialAttachmentUploadStatus.PENDING
                || source.trip().getProcessingStatus() != ProcessingStatus.PROCESSING
                || !task.isClaimOwner(claim.claimToken(), now)) {
            return stepResult(AttachmentConversionStepResult.Status.RETRY_LATER, null);
        }
        return null;
    }

    private InitialAttachmentUploadItem conversionItem(AttachmentConversionClaim claim, Long itemId) {
        var item = items.findById(itemId).orElseThrow(InvalidAttachmentUploadException::new);
        entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(item.getBatch().getId(), claim.batchId()) || !inLane(item, claim.lane())) {
            throw new InvalidAttachmentUploadException();
        }
        return item;
    }

    private boolean inLane(InitialAttachmentUploadItem item, Lane lane) {
        return ("image/heic".equals(item.getContentType()) ? Lane.HEIC : Lane.LIGHT) == lane;
    }

    private AttachmentConversionStepResult finishConvertedBatch(ConversionSource source, AttachmentConversionClaim claim) {
        entityManager.flush();
        var all = items.findAllByBatch_IdOrderByFileOrderAsc(source.batch().getId());
        for (var item : all) {
            entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        }
        var remaining = all.stream().filter(item -> inLane(item, claim.lane()) && item.getDerivedJson() == null).toList();
        source.task().recordRemaining(remaining.size(), remaining.stream().mapToLong(InitialAttachmentUploadItem::getSizeBytes).sum());
        if (!remaining.isEmpty()) {
            LocalDateTime next = source.task().getNextAttemptAt();
            return stepResult(next != null && next.isAfter(now())
                    ? AttachmentConversionStepResult.Status.RETRY_LATER : AttachmentConversionStepResult.Status.CONTINUE, next);
        }
        if (all.stream().allMatch(item -> item.getDerivedJson() != null) && source.batch().getOutboxId() == null) {
            completeBatch(source.batch(), all);
        }
        source.task().finish(AttachmentConversionTask.Status.SUCCEEDED);
        return stepResult(AttachmentConversionStepResult.Status.ACK_READY, null);
    }

    private AttachmentConversionStepResult stepResult(AttachmentConversionStepResult.Status status, LocalDateTime retryAt) {
        return new AttachmentConversionStepResult(status, retryAt);
    }

    private void journalLateOutput(AttachmentConversionTask task, InitialAttachmentUploadItem item, DerivedPhotoKeys photo) {
        List<String> protectedKeys = new ArrayList<>();
        if (item.getDerivedJson() != null) {
            protectedKeys.addAll(derivativeKeys(readDerived(item.getDerivedJson())));
        }
        if (task.isClaimOwner(task.getClaimToken(), now()) && task.hasCurrentAttempt(item.getId(), task.getClaimToken())) {
            protectedKeys.addAll(derivativeKeys(plannedPhoto(item.getBatch().getExecutionId(), item)));
        }
        var journal = new ArrayList<>(cleanupObjects(item));
        for (String key : derivativeKeys(photo)) {
            if (protectedKeys.contains(key)) {
                continue;
            }
            if (journal.stream().noneMatch(object -> object.key().equals(key))) {
                journal.add(new CleanupObject(key, now(), "STALE_CONVERSION", false));
            }
        }
        recordCleanup(item, journal.stream().map(object -> derivativeKeys(photo).contains(object.key())
                && !protectedKeys.contains(object.key()) && object.startedAt() == null
                ? new CleanupObject(object.key(), now(), "STALE_CONVERSION", object.deleted()) : object).toList());
    }

    private int outputAttempt(String executionId, InitialAttachmentUploadItem item, DerivedPhotoKeys photo) {
        for (int attempt = 1; attempt <= item.getConversionAttempts(); attempt++) {
            if (Objects.equals(photo.analyzeKey(), attemptPrefix(executionId, item.getId(), attempt) + "analyze.jpg")) {
                return attempt;
            }
        }
        throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_OUTPUT_KEY_OR_SIZE_INVALID.message());
    }

    private record ConversionSource(
            Trip trip,
            TripCreationExecution execution,
            InitialAttachmentUploadBatch batch,
            AttachmentConversionTask task
    ) {
    }

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
            executionId = createExecution(tripId, userId, request, batchBytes);
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

        if (latest != null) {
            validateBatchLimits(items.findAllByBatch_ExecutionId(executionId), request, batchBytes);
        }

        TripCreationExecution execution = requireExecutionForUpdate(executionId);
        userStats.reserveInitialUpload(userId, batchBytes);
        execution.reserve(batchBytes);
        execution.recordUploadActivity(now());
        return snapshot(saveNewBatch(tripId, userId, executionId, request));
    }

    private String createExecution(
            Long tripId,
            Long userId,
            InitialAttachmentUploadUrlRequest request,
            long batchBytes
    ) {
        if (request.batchNo() != 1
                || !attachments.findAllByTripIdAndDeletedAtIsNull(tripId).isEmpty()) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        validateBatchLimits(List.of(), request, batchBytes);
        conversionAdmission.assertNewExecutionAllowed();
        String executionId = UUID.randomUUID().toString();
        var execution = executionRepository.save(new TripCreationExecution(
                executionId, tripId, userId, request.totalAttachmentCount()));
        publishStatusChanged(execution);
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

        candidate.accept(now(), conversionProperties.mode());
        enqueueConversion(candidate);
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

    private void enqueueConversion(InitialAttachmentUploadBatch batch) {
        if (batch.getConversionRoute() != AttachmentConversionRoute.KAFKA) {
            return;
        }
        String requestId = org.slf4j.MDC.get("request_id");
        var photos = items.findAllByBatch_IdOrderByFileOrderAsc(batch.getId()).stream()
                .map(item -> new AttachmentConversionSeed.Photo(
                        item.getId(), item.getContentType(), item.getSizeBytes()))
                .toList();
        conversionEnqueue.enqueue(new AttachmentConversionSeed(
                Kind.INITIAL, batch.getId(), batch.getTripId(),
                requestId == null ? batch.getExecutionId() : requestId, photos));
    }

    public TripProcessingStatusResponse acceptanceStatus(
            Trip trip, TripCreationExecution execution
    ) {
        if (execution.getStatus() == TripCreationExecution.Status.CANCELED) {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        var status = TripProcessingStatusResponse.Status.valueOf(execution.getDisplayStatus().name());
        TripProcessingStatusResponse.Error error = null;
        if (status == TripProcessingStatusResponse.Status.FAILED) {
            error = new TripProcessingStatusResponse.Error(
                    execution.getErrorCode(),
                    TripInternalErrorMessage.ATTACHMENT_PROCESSING_FAILED.message());
        }

        return new TripProcessingStatusResponse(
                trip.getId(),
                execution.getExecutionId(),
                execution.getStatusVersion(),
                status,
                execution.getCurrentStep(),
                status == TripProcessingStatusResponse.Status.COMPLETED && execution.getResultRawPayload() != null
                        ? TripProcessingStatusResponse.Result.fromAiResult(trip.getId(),
                        objectMapper.readTree(execution.getResultRawPayload()).path("result")) : null,
                error
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<InitialAttachmentUploadBatchSnapshot> claimBatch() {
        if (conversionProperties.mode() != AttachmentConversionRoute.DB || conversionProperties.operations().enabled()) {
            return Optional.empty();
        }
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
        if (conversionProperties.mode() != AttachmentConversionRoute.DB || conversionProperties.operations().enabled()) {
            return Optional.empty();
        }
        return batches.findByUploadIdAndTripIdAndUserId(uploadId, tripId, userId)
                .flatMap(this::claimCandidate);
    }

    @Transactional(readOnly = true)
    public List<InitialAttachmentUploadBatch> findPendingBatches() {
        return batches.findPendingConversion(now(), PageRequest.of(0, 20));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<InitialAttachmentUploadBatchSnapshot> claimBatch(Long batchId) {
        if (conversionProperties.mode() != AttachmentConversionRoute.DB || conversionProperties.operations().enabled()) {
            return Optional.empty();
        }
        return batches.findById(batchId).flatMap(this::claimCandidate);
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
        if (batch.getConversionRoute() != AttachmentConversionRoute.DB
                || execution.isTerminal() || trip.getProcessingStatus() != ProcessingStatus.PROCESSING
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
        startAttempt(claim.executionId(), item);
        return derivativeKeys(plannedPhoto(claim.executionId(), item));
    }

    private void startAttempt(String executionId, InitialAttachmentUploadItem item) {
        scheduleUnreferencedAttempts(item, "CONVERSION_RETRY");
        item.startConversion(now());
        var journal = new ArrayList<>(cleanupObjects(item));
        derivativeKeys(plannedPhoto(executionId, item)).forEach(key -> journal.add(new CleanupObject(key, null, null, false)));
        recordCleanup(item, journal);
    }

    private DerivedPhotoKeys plannedPhoto(String executionId, InitialAttachmentUploadItem item) {
        String prefix = attemptPrefix(executionId, item.getId(), item.getConversionAttempts());
        return new DerivedPhotoKeys(item.getObjectKey(), prefix + "analyze.jpg", prefix + "preview.webp",
                "image/heic".equals(item.getContentType()) ? prefix + "display.jpg" : null,
                null, null, null, null, item.getSizeBytes(), null, null, null);
    }

    private String attemptPrefix(String executionId, Long itemId, int attemptNo) {
        return "trip-uploads/" + executionId + "/derived/" + itemId + "/" + attemptNo + "/";
    }

    private List<String> derivativeKeys(DerivedPhotoKeys photo) {
        return photo.displayKey() == null ? List.of(photo.analyzeKey(), photo.previewKey())
                : List.of(photo.analyzeKey(), photo.previewKey(), photo.displayKey());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void savePhotoSuccess(
            InitialAttachmentUploadBatchSnapshot claim,
            Long itemId,
            DerivedPhotoKeys photo
    ) {
        requireCurrentClaim(claim);
        var item = requireItem(claim, itemId);
        validatePhoto(claim.executionId(), item, photo, item.getConversionAttempts());
        persistPhoto(claim.tripId(), claim.userId(), claim.executionId(), item, photo);
    }

    private void validatePhoto(String executionId, InitialAttachmentUploadItem item, DerivedPhotoKeys photo, int attemptNo) {
        if (!item.getObjectKey().equals(photo.originalKey()) || photo.originalSizeBytes() == null
                || !item.getSizeBytes().equals(photo.originalSizeBytes())) {
            throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_SOURCE_ATTACHMENT_MISMATCH.message());
        }
        String prefix = attemptPrefix(executionId, item.getId(), attemptNo);
        boolean heic = "image/heic".equals(item.getContentType());
        if (!Objects.equals(photo.analyzeKey(), prefix + "analyze.jpg")
                || !Objects.equals(photo.previewKey(), prefix + "preview.webp")
                || photo.analyzeSizeBytes() == null || photo.analyzeSizeBytes() < 1
                || photo.previewSizeBytes() == null || photo.previewSizeBytes() < 1
                || heic && (!Objects.equals(photo.displayKey(), prefix + "display.jpg")
                    || photo.displaySizeBytes() == null || photo.displaySizeBytes() < 1)
                || !heic && photo.displayKey() != null) {
            throw new IllegalStateException(TripInternalErrorMessage.CONVERSION_OUTPUT_KEY_OR_SIZE_INVALID.message());
        }
    }

    private void persistPhoto(Long tripId, Long userId, String executionId, InitialAttachmentUploadItem item, DerivedPhotoKeys photo) {
        try (var scope = WorkflowMdcScope.job("trip_creation", tripId, executionId);
             var timing = startAttachmentPersistenceLogging(item.getId())) {
            if (item.getDerivedJson() == null) {
                saveAttachment(tripId, userId, item, photo);
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
        completeBatch(batch, all);
    }

    private void completeBatch(InitialAttachmentUploadBatch batch, List<InitialAttachmentUploadItem> all) {
        List<TripAttachment> saved = loadConvertedAttachments(all);
        var refs = saved.stream().map(photo -> Map.of(
                "trip_attachment_id", photo.getId(), "analyze_storage_key", photo.getAnalyzeStorageKey())).toList();
        var event = new TripCreateRequestEvent(
                "photos_ready", batch.getTripId(), batch.getExecutionId(), batch.getBatchNo(), null, null, null, null,
                objectMapper.valueToTree(refs));
        batch.ready(objectMapper.writeValueAsString(event));
        logPhotoDerivativesPersisted(batch.getTripId(), batch.getExecutionId());
        saveCdcOutbox(batch.getOutboxId(), batch.getExecutionId(), "photos_ready",
                batch.getBatchNo(), batch.getTripId(), batch.getOutboxPayload());
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
        publishStatusChanged(execution);
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
        publishStatusChanged(execution);
    }

    private void publishStatusChanged(TripCreationExecution execution) {
        var snapshot = new TripProcessingStateSnapshot(
                execution.getTripId(),
                execution.getExecutionId(),
                execution.getStatusVersion(),
                execution.getDisplayStatus(),
                execution.getCurrentStep(),
                execution.getErrorCode()
        );

        eventPublisher.publishEvent(new TripProcessingStatusChangedEvent(
                execution.getUserId(), snapshot));
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
    public void failUnstartedTrip(Long tripId) {
        Trip trip = trips.findActiveForUpdate(tripId).orElse(null);
        if (trip == null) {
            return;
        }
        LocalDateTime now = databaseTime();
        int updated = trips.failUnstartedUploadIfExpired(tripId, now.minusMinutes(10), now);
        if (updated == 1) {
            log.info(TripInternalErrorMessage.UNSTARTED_UPLOAD_EXPIRED.message(),
                    tripId, ErrorMessage.UPLOAD_TIMEOUT.name());
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
        try (WorkflowMdcScope scope = WorkflowMdcScope.job("trip_creation", tripId, executionId);
             WorkflowLog.Stage timing = startOutboxPersistenceLogging(messageType, batchNo)) {
            outboxes.save(new TripCreationOutbox(
                    id, executionId, messageType, batchNo, tripId,
                    topics.topicFor(EventType.TRIP_CREATE_REQUEST), payload, traces.capture()));
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
                trip.getCreatedAt(), now());
    }

    private void logWorkflowFailed(TripCreationExecution execution, Trip trip, String errorCode) {
        WorkflowLog.finishedFromDatabase("trip_creation", execution.getTripId(), execution.getExecutionId(),
                trip == null ? null : trip.getCreatedAt(), now(), "failure", errorCode);
    }

    private void logWorkflowCanceled(Long tripId, String executionId) {
        WorkflowLog.finishedFromDatabase("trip_creation", tripId, executionId,
                trips.findById(tripId).map(Trip::getCreatedAt).orElse(null), now(), "canceled", null);
    }

    private void logAttachmentPersisted(WorkflowLog.Stage timing) {
        timing.committed("attachment_persistence");
    }

    private void logAiRequestEnqueued(WorkflowLog.Stage timing) {
        timing.committed("ai_request_enqueued");
    }
}
