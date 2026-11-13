package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.response.WorkflowTraceCarrier;

import com.yeodam.yeodambe.trip.client.InvalidAttachmentConversionMessageException;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Status;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionOutboxRepository;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaim.Photo;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionClaimResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionAttemptResult;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionStepResult;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionMessage;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionPublication;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionRecoveryCandidate;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionRecoveryCandidate.Cursor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AttachmentConversionTransactionService {
    private final AttachmentConversionTaskRepository tasks;
    private final AttachmentConversionOutboxRepository outboxes;
    private final InitialAttachmentUploadItemRepository initialItems;
    private final AdditionalAttachmentUploadItemRepository additionalItems;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InitialAttachmentUploadTransactionService initialSource;
    private final AdditionalAttachmentUploadTransactionService additionalSource;

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<AttachmentConversionRecoveryCandidate> findRecoveryCandidates(
            Cursor cursor,
            int limit,
            Duration minInterval
    ) {
        requireRecoveryInterval(minInterval);
        Assert.isTrue(limit > 0, "재알림 페이지 크기는 양수여야 합니다.");
        LocalDateTime now = databaseTime();
        return tasks.findRecoveryCandidates(cursor == null ? null : cursor.createdAt(),
                        cursor == null ? null : cursor.taskId(), now, now.minus(minInterval), limit).stream()
                .map(task -> {
                    var box = outboxes.findByTaskIdAndDeliveryNo(task.getId(), task.getLatestDeliveryNo()).orElseThrow();
                    return new AttachmentConversionRecoveryCandidate(task.getId(), task.getCreatedAt(),
                            task.getLatestDeliveryNo(), box.getPublicationVersion(), publication(box));
                }).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean renotifyIfEligible(AttachmentConversionRecoveryCandidate expected, Duration minInterval) {
        requireRecoveryInterval(minInterval);
        Assert.notNull(expected, TripInternalErrorMessage.CONVERSION_RECOVERY_CANDIDATE_MISSING.message());
        AttachmentConversionTask hint = tasks.findById(expected.taskId()).orElse(null);
        if (hint == null) {
            return false;
        }
        Source source = lockSource(hint);
        AttachmentConversionTask task = lockTask(expected.taskId());
        LocalDateTime now = databaseTime();
        if (source == null || !matchesSource(task, source) || source.route() != AttachmentConversionRoute.KAFKA
                || !source.processable() || source.terminalStatus() != null || task.isTerminal()
                || task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(now)
                || task.getLatestDeliveryNo() != expected.latestDeliveryNo()
                || !Objects.equals(task.getCreatedAt(), expected.createdAt()) || expected.publication() == null) {
            return false;
        }
        AttachmentConversionOutbox box = outboxes.findByIdForUpdate(expected.publication().outboxId()).orElse(null);
        if (box == null) {
            return false;
        }
        entityManager.refresh(box, LockModeType.PESSIMISTIC_WRITE);
        if (!box.isCdcManaged() || !box.getTaskId().equals(task.getId())
                || box.getDeliveryNo() != task.getLatestDeliveryNo() || !box.getTripId().equals(task.getTripId())
                || box.getStatus() != AttachmentConversionOutbox.Status.PUBLISHED
                || box.getPublicationVersion() != expected.publicationVersion()
                || box.getRecordPartition() == null || box.getRecordOffset() == null
                || !publication(box).equals(expected.publication())
                || box.getPublishedAt() == null || box.getPublishedAt().isAfter(now.minus(minInterval))) {
            return false;
        }
        outboxes.save(new AttachmentConversionOutbox(UUID.randomUUID().toString(), task.getId(), task.nextDelivery(),
                task.getTripId(), box.getTopic(), box.getPayload(), box.getRequestId(),
                new WorkflowTraceCarrier(box.getTraceparent(), box.getTracestate())));
        return true;
    }

    private AttachmentConversionPublication publication(AttachmentConversionOutbox box) {
        return new AttachmentConversionPublication(box.getId(), box.getTopic(), box.getTripId().toString(), box.getPayload(),
                box.getTopicId(), box.getRecordPartition(), box.getRecordOffset(), box.getBrokerTimestamp(), box.getLeaderEpoch());
    }

    private void requireRecoveryInterval(Duration minInterval) {
        Assert.isTrue(minInterval != null && minInterval.isPositive(),
                TripInternalErrorMessage.CONVERSION_RECOVERY_INTERVAL_INVALID.message());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordPublished(AttachmentConversionPublication publication) {
        Assert.notNull(publication, "변환 발행 관측이 필요합니다.");
        Assert.isTrue(publication.partition() >= 0 && publication.offset() >= 0
                && publication.brokerTimestamp() != null, "변환 발행 위치가 유효하지 않습니다.");
        var hint = outboxes.findById(publication.outboxId()).orElseThrow(IllegalArgumentException::new);
        var task = lockTask(hint.getTaskId());
        var outbox = outboxes.findByIdForUpdate(publication.outboxId()).orElseThrow(IllegalArgumentException::new);
        entityManager.refresh(outbox, LockModeType.PESSIMISTIC_WRITE);
        Assert.isTrue(outbox.isCdcManaged() && outbox.getTaskId().equals(task.getId())
                && outbox.getTripId().equals(task.getTripId())
                && outbox.getDeliveryNo() <= task.getLatestDeliveryNo()
                && outbox.getTopic().equals(publication.topic())
                && outbox.getTripId().toString().equals(publication.key())
                && outbox.getPayload().equals(publication.payload()), "변환 발행 관측이 Outbox와 일치하지 않습니다.");
        requirePublicationTask(publication.payload(), task.getId());
        outbox.recordPublished(publication, databaseTime());
    }

    private void requirePublicationTask(String payload, String taskId) {
        try {
            var message = json.readValue(payload, AttachmentConversionMessage.class);
            Assert.isTrue(message != null && message.schemaVersion() == 1 && taskId.equals(message.taskId()),
                    "변환 발행 관측의 작업이 일치하지 않습니다.");
        } catch (JacksonException invalid) {
            throw new InvalidAttachmentConversionMessageException();
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AttachmentConversionAttemptResult beginPhoto(AttachmentConversionClaim claim, Long itemId) {
        return claim.kind() == Kind.INITIAL ? initialSource.startPhotoAttempt(claim, itemId)
                : additionalSource.startPhotoAttempt(claim, itemId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AttachmentConversionStepResult completePhoto(AttachmentConversionClaim claim, Long itemId, DerivedPhotoKeys photo) {
        return claim.kind() == Kind.INITIAL ? initialSource.savePhotoSuccess(claim, itemId, photo)
                : additionalSource.savePhotoSuccess(claim, itemId, photo);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AttachmentConversionStepResult recordPhotoFailure(AttachmentConversionClaim claim, Long itemId, boolean permanent) {
        return claim.kind() == Kind.INITIAL ? initialSource.recordPhotoFailure(claim, itemId, permanent)
                : additionalSource.recordPhotoFailure(claim, itemId, permanent);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AttachmentConversionStepResult finishSubBatch(AttachmentConversionClaim claim) {
        return claim.kind() == Kind.INITIAL ? initialSource.completeConvertedBatch(claim)
                : additionalSource.completeConvertedBatch(claim);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AttachmentConversionClaimResult claim(String taskId, Kind expectedKind, Lane expectedLane) {
        var candidate = tasks.findById(taskId).orElseThrow(InvalidAttachmentConversionMessageException::new);
        requireRouting(candidate, expectedKind, expectedLane);
        var source = lockSource(candidate);
        var task = lockTask(taskId);
        requireRouting(task, expectedKind, expectedLane);
        LocalDateTime now = databaseTime();
        if (task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(now)) {
            return retry(task, now);
        }
        if (task.isTerminal()) {
            return terminal();
        }
        if (source == null || !matchesSource(task, source) || source.route() != AttachmentConversionRoute.KAFKA) {
            return retry(task, now);
        }
        if (source.terminalStatus() != null) {
            task.finish(source.terminalStatus());
            return terminal();
        }
        if (!source.processable()) {
            return retry(task, now);
        }
        var photos = photos(task);
        if (photos.isEmpty()) {
            return retry(task, now);
        }
        var remaining = photos.stream().filter(photo -> photo.derived() == null).toList();
        if (!remaining.isEmpty() && task.getNextAttemptAt() != null && task.getNextAttemptAt().isAfter(now)) {
            return retry(task, now);
        }
        task.recordRemaining(remaining.size(), remaining.stream().mapToLong(Photo::sizeBytes).sum());
        task.claim(UUID.randomUUID().toString(), now.plusMinutes(10));
        String requestId = outboxes.findRequestId(taskId, task.getLatestDeliveryNo()).orElse(source.executionId());
        return new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.CLAIMED,
                new AttachmentConversionClaim(taskId, task.getClaimToken(), task.getKind(), task.getLane(),
                        source.batchId(), source.uploadId(), source.executionId(), source.tripId(), source.userId(),
                        requestId, photos), null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean releaseClaim(AttachmentConversionClaim claim) {
        var candidate = tasks.findById(claim.taskId()).orElse(null);
        if (candidate == null) {
            return false;
        }
        var source = lockSource(candidate);
        var task = lockTask(claim.taskId());
        if (source == null || !matchesSource(task, source) || !matchesClaim(task, source, claim)) {
            return false;
        }
        Long itemId = task.getCurrentItemId();
        boolean abandonedAttempt = claim.claimToken() != null && claim.claimToken().equals(task.getCurrentAttemptToken());
        boolean released = task.releaseClaim(claim.claimToken());
        // 다른 소유자의 claim은 유지하고 이전 token이 버린 시도의 정리만 확정한다.
        if (itemId != null && (released || abandonedAttempt)) {
            journalReleasedAttempt(task, source, itemId);
        }
        return released;
    }

    private void journalReleasedAttempt(AttachmentConversionTask task, Source source, Long itemId) {
        if (task.getKind() == Kind.INITIAL) {
            var item = lockFresh(InitialAttachmentUploadItem.class, itemId);
            if (item == null || !Objects.equals(item.getBatch().getId(), source.batchId()) || !inLane(item.getContentType(), task.getLane())) {
                throw new InvalidAttachmentConversionMessageException();
            }
            if (item.getDerivedJson() == null && item.getTripAttachmentId() == null) {
                var keys = initialSource.cleanupObjects(item).stream().filter(object -> !object.deleted())
                        .map(InitialAttachmentUploadTransactionService.CleanupObject::key)
                        .filter(key -> !key.equals(item.getObjectKey())).toList();
                initialSource.scheduleCleanupKeys(item.getObjectKey(), keys, "CONVERSION_CLAIM_RELEASED");
            }
        } else {
            var item = lockFresh(AdditionalAttachmentUploadItem.class, itemId);
            if (item == null || !Objects.equals(item.getBatch().getId(), source.batchId()) || !inLane(item.getContentType(), task.getLane())) {
                throw new InvalidAttachmentConversionMessageException();
            }
            if (item.getDerivedJson() == null && item.getTripAttachmentId() == null) {
                var keys = additionalSource.cleanupObjects(item).stream().filter(object -> !object.deleted())
                        .map(AdditionalAttachmentUploadTransactionService.CleanupObject::key)
                        .filter(key -> !key.equals(item.getObjectKey())).toList();
                additionalSource.scheduleCleanupKeys(item.getObjectKey(), keys, "CONVERSION_CLAIM_RELEASED");
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean renewLease(AttachmentConversionClaim claim) {
        var candidate = tasks.findById(claim.taskId()).orElse(null);
        if (candidate == null) {
            return false;
        }
        var source = lockSource(candidate);
        var task = lockTask(claim.taskId());
        LocalDateTime now = databaseTime();
        if (source == null || !matchesSource(task, source) || source.route() != AttachmentConversionRoute.KAFKA
                || source.terminalStatus() != null || !source.processable()
                || !matchesClaim(task, source, claim)
                || !task.isClaimOwner(claim.claimToken(), now)) {
            return false;
        }
        task.renewLease(now.plusMinutes(10));
        return true;
    }

    private Source lockSource(AttachmentConversionTask task) {
        return task.getKind() == Kind.INITIAL ? lockInitialSource(task) : lockAdditionalSource(task);
    }

    private Source lockInitialSource(AttachmentConversionTask task) {
        var hint = entityManager.find(InitialAttachmentUploadBatch.class, task.getInitialBatchId());
        if (hint == null || !Objects.equals(hint.getTripId(), task.getTripId())) {
            return null;
        }
        var trip = lockFresh(Trip.class, task.getTripId());
        var execution = lockFresh(TripCreationExecution.class, hint.getExecutionId());
        var batch = lockFresh(InitialAttachmentUploadBatch.class, task.getInitialBatchId());
        if (trip == null || execution == null || batch == null
                || !Objects.equals(trip.getUserId(), batch.getUserId())
                || !Objects.equals(execution.getTripId(), batch.getTripId())
                || !Objects.equals(execution.getUserId(), batch.getUserId())
                || !Objects.equals(execution.getExecutionId(), batch.getExecutionId())) {
            return null;
        }
        Status terminal = execution.isTerminal() ? switch (execution.getStatus()) {
            case COMPLETED -> Status.SUCCEEDED;
            case CANCELED -> Status.CANCELED;
            case FAILED -> Status.FAILED;
            default -> null;
        } : null;
        if (trip.getDeletedAt() != null || trip.getProcessingStatus() == ProcessingStatus.CANCELED) {
            terminal = Status.CANCELED;
        } else if (batch.getStatus() == InitialAttachmentUploadStatus.FAILED) {
            terminal = Status.FAILED;
        }
        boolean processable = !execution.isTerminal() && trip.getProcessingStatus() == ProcessingStatus.PROCESSING
                && batch.getAcceptedAt() != null && batch.getStatus() != InitialAttachmentUploadStatus.PENDING;
        return new Source(batch.getId(), batch.getUploadId(), batch.getExecutionId(), batch.getTripId(), batch.getUserId(),
                batch.getConversionRoute(), processable, terminal);
    }

    private Source lockAdditionalSource(AttachmentConversionTask task) {
        var hint = entityManager.find(AdditionalAttachmentUploadBatch.class, task.getAdditionalBatchId());
        if (hint == null || !Objects.equals(hint.getTripId(), task.getTripId())) {
            return null;
        }
        var trip = lockFresh(Trip.class, task.getTripId());
        var execution = lockFresh(AdditionalAttachmentExecution.class, hint.getAdditionId());
        var batch = lockFresh(AdditionalAttachmentUploadBatch.class, task.getAdditionalBatchId());
        if (trip == null || execution == null || batch == null
                || !Objects.equals(trip.getUserId(), batch.getUserId())
                || !Objects.equals(execution.getTripId(), batch.getTripId())
                || !Objects.equals(execution.getUserId(), batch.getUserId())
                || !Objects.equals(execution.getExecutionId(), batch.getAdditionId())) {
            return null;
        }
        Status terminal = execution.isTerminal() ? switch (execution.getStatus()) {
            case COMPLETED -> Status.SUCCEEDED;
            case CANCELED -> Status.CANCELED;
            case FAILED -> Status.FAILED;
            default -> null;
        } : null;
        if (trip.getDeletedAt() != null || trip.getProcessingStatus() == ProcessingStatus.CANCELED) {
            terminal = Status.CANCELED;
        } else if (batch.getStatus() == AdditionalAttachmentUploadStatus.FAILED) {
            terminal = Status.FAILED;
        }
        boolean processable = !execution.isTerminal() && trip.getProcessingStatus() == ProcessingStatus.COMPLETED
                && batch.getStatus() != AdditionalAttachmentUploadStatus.PENDING;
        return new Source(batch.getId(), batch.getUploadId(), batch.getAdditionId(), batch.getTripId(), batch.getUserId(),
                batch.getConversionRoute(), processable, terminal);
    }

    private <T> T lockFresh(Class<T> type, Object id) {
        entityManager.flush();
        T value = entityManager.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        if (value != null) {
            entityManager.refresh(value, LockModeType.PESSIMISTIC_WRITE);
        }
        return value;
    }

    private AttachmentConversionTask lockTask(String taskId) {
        var task = tasks.findByIdForUpdate(taskId).orElseThrow(InvalidAttachmentConversionMessageException::new);
        entityManager.refresh(task, LockModeType.PESSIMISTIC_WRITE);
        return task;
    }

    private boolean matchesSource(AttachmentConversionTask task, Source source) {
        Long batchId = task.getKind() == Kind.INITIAL ? task.getInitialBatchId() : task.getAdditionalBatchId();
        return Objects.equals(batchId, source.batchId()) && Objects.equals(task.getTripId(), source.tripId());
    }

    private boolean matchesClaim(AttachmentConversionTask task, Source source, AttachmentConversionClaim claim) {
        return task.getKind() == claim.kind() && task.getLane() == claim.lane()
                && Objects.equals(source.batchId(), claim.batchId())
                && Objects.equals(source.uploadId(), claim.uploadId())
                && Objects.equals(source.executionId(), claim.executionId())
                && Objects.equals(source.tripId(), claim.tripId())
                && Objects.equals(source.userId(), claim.userId());
    }

    private void requireRouting(AttachmentConversionTask task, Kind kind, Lane lane) {
        if (task.getKind() != kind || task.getLane() != lane) {
            throw new InvalidAttachmentConversionMessageException();
        }
    }

    private List<Photo> photos(AttachmentConversionTask task) {
        if (task.getKind() == Kind.INITIAL) {
            var items = initialItems.findAllByBatch_IdOrderByFileOrderAsc(task.getInitialBatchId());
            for (var item : items) {
                entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
            }
            return items.stream()
                    .filter(item -> inLane(item.getContentType(), task.getLane()))
                    .map(item -> new Photo(item.getId(), item.getContentType(), item.getObjectKey(), item.getSizeBytes(),
                            item.getConversionAttempts(), item.getConversionNextAttemptAt(), readDerived(item.getDerivedJson())))
                    .toList();
        }
        var items = additionalItems.findAllByBatch_IdOrderByFileOrderAsc(task.getAdditionalBatchId());
        for (var item : items) {
            entityManager.refresh(item, LockModeType.PESSIMISTIC_WRITE);
        }
        return items.stream()
                .filter(item -> inLane(item.getContentType(), task.getLane()))
                .map(item -> new Photo(item.getId(), item.getContentType(), item.getObjectKey(), item.getSizeBytes(),
                        item.getConversionAttempts(), item.getConversionNextAttemptAt(), readDerived(item.getDerivedJson())))
                .toList();
    }

    private boolean inLane(String contentType, Lane lane) {
        return ("image/heic".equals(contentType) ? Lane.HEIC : Lane.LIGHT) == lane;
    }

    private DerivedPhotoKeys readDerived(String value) {
        return value == null ? null : json.readerFor(DerivedPhotoKeys.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).readValue(value);
    }

    private LocalDateTime databaseTime() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", LocalDateTime.class);
    }

    private AttachmentConversionClaimResult retry(AttachmentConversionTask task, LocalDateTime now) {
        LocalDateTime retryAt = Stream.of(task.getLeaseUntil(), task.getNextAttemptAt())
                .filter(Objects::nonNull).filter(value -> value.isAfter(now)).max(LocalDateTime::compareTo).orElse(null);
        return new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.RETRY_LATER, null, retryAt);
    }

    private AttachmentConversionClaimResult terminal() {
        return new AttachmentConversionClaimResult(AttachmentConversionClaimResult.Status.ACK_TERMINAL, null, null);
    }

    private record Source(
            Long batchId,
            String uploadId,
            String executionId,
            Long tripId,
            Long userId,
            AttachmentConversionRoute route,
            boolean processable,
            Status terminalStatus
    ) {
    }
}
