package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentConversionMigrationService {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;
    private final AttachmentConversionEnqueueService enqueue;

    @Transactional(readOnly = true)
    public List<Candidate> check(Kind kind, long afterBatchId, int limit) {
        return candidates(kind, afterBatchId, limit, false);
    }

    public int transferPendingBatches(Kind kind, long afterBatchId, int limit) {
        return transferPendingBatches(kind, afterBatchId, limit, Instant.MAX);
    }

    int transferPendingBatches(Kind kind, long afterBatchId, int limit, Instant deadline) {
        List<Candidate> page = candidates(kind, afterBatchId, limit, true);
        int transferred = 0;
        for (Candidate candidate : page) {
            boolean changed = Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                    .execute(status -> transition(kind, candidate.batchId(), AttachmentConversionRoute.KAFKA, deadline)));
            if (changed) {
                transferred++;
            }
            log.info("변환 인계 검사 kind={} last_batch_id={} transferred={}", kind, candidate.batchId(), transferred);
        }
        return transferred;
    }

    public boolean returnBatchToDb(Kind kind, Long batchId) {
        return returnBatchToDb(kind, batchId, Instant.MAX);
    }

    boolean returnBatchToDb(Kind kind, Long batchId, Instant deadline) {
        Assert.notNull(kind, "변환 종류가 필요합니다.");
        Assert.isTrue(batchId != null && batchId > 0, "배치 ID는 양수여야 합니다.");
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                .execute(status -> transition(kind, batchId, AttachmentConversionRoute.DB, deadline)));
    }

    private List<Candidate> candidates(Kind kind, long afterBatchId, int limit, boolean remainingOnly) {
        Assert.notNull(kind, "변환 종류가 필요합니다.");
        Assert.isTrue(afterBatchId >= 0 && limit > 0, "인계 페이지 입력이 유효하지 않습니다.");
        String executionTable = kind == Kind.INITIAL ? "trip_creation_executions" : "additional_attachment_executions";
        String executionColumn = kind == Kind.INITIAL ? "execution_id" : "addition_id";
        String leaseColumn = kind == Kind.INITIAL ? "lease_until" : "lease_expires_at";
        String taskColumn = kind == Kind.INITIAL ? "initial_batch_id" : "additional_batch_id";
        String states = kind == Kind.INITIAL ? "'ACCEPTED', 'CONVERTING'" : "'VERIFIED', 'CONVERTING'";
        String sql = """
                SELECT b.upload_batch_id, b.conversion_route, b.%s AS legacy_lease,
                       e.execution_id IS NULL AS missing_execution,
                       EXISTS (SELECT 1 FROM attachment_conversion_tasks task
                           WHERE task.%s = b.upload_batch_id
                             AND (task.lease_until > CURRENT_TIMESTAMP OR task.current_item_id IS NOT NULL)) AS claimed
                FROM %s b LEFT JOIN %s e ON e.execution_id = b.%s
                WHERE b.upload_batch_id > ? AND b.status IN (%s) %s
                ORDER BY b.upload_batch_id LIMIT ?
                """.formatted(leaseColumn, taskColumn, table(kind), executionTable, executionColumn, states,
                remainingOnly ? "AND b.conversion_route IN ('DB', 'HOLD')" : "");
        return jdbc.query(sql, (row, index) -> new Candidate(row.getLong("upload_batch_id"),
                AttachmentConversionRoute.valueOf(row.getString("conversion_route")),
                row.getObject("legacy_lease", LocalDateTime.class), row.getBoolean("claimed"),
                row.getBoolean("missing_execution")), afterBatchId, limit);
    }

    private boolean transition(Kind kind, Long batchId, AttachmentConversionRoute target, Instant deadline) {
        Source source = lockSource(kind, batchId);
        if (source == null) {
            return false;
        }
        if (source.route() == target) {
            return target == AttachmentConversionRoute.DB;
        }
        Assert.state(deadline.isAfter(Instant.now()), "drain 확인 자료가 만료되었습니다.");
        LocalDateTime now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", LocalDateTime.class);
        Assert.state(source.leaseUntil() == null || !source.leaseUntil().isAfter(now), "기존 변환 lease가 유효합니다.");
        String taskColumn = kind == Kind.INITIAL ? "initialBatchId" : "additionalBatchId";
        List<AttachmentConversionTask> existing = entityManager.createQuery(
                        "select task from AttachmentConversionTask task where task." + taskColumn + " = :batchId order by task.id",
                        AttachmentConversionTask.class)
                .setParameter("batchId", batchId).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (AttachmentConversionTask task : existing) {
            entityManager.refresh(task, LockModeType.PESSIMISTIC_WRITE);
            Assert.state((task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now))
                    && task.getCurrentItemId() == null, "변환 claim 또는 미종료 사진 시도가 있습니다.");
        }
        Assert.state(deadline.isAfter(Instant.now()), "drain 확인 자료가 만료되었습니다.");
        for (AttachmentConversionTask task : existing) {
            if (task.getClaimToken() != null) {
                task.releaseClaim(task.getClaimToken());
            }
        }
        if (target == AttachmentConversionRoute.KAFKA && existing.isEmpty()) {
            String itemTable = kind == Kind.INITIAL ? "initial_attachment_upload_items" : "additional_attachment_upload_items";
            var photos = jdbc.query("SELECT upload_item_id, content_type, size_bytes FROM " + itemTable
                            + " WHERE upload_batch_id = ? ORDER BY file_order",
                    (row, index) -> new AttachmentConversionSeed.Photo(row.getLong(1), row.getString(2), row.getLong(3)), batchId);
            Assert.state(!photos.isEmpty(), "변환할 사진이 없습니다.");
            enqueue.enqueue(new AttachmentConversionSeed(kind, batchId, source.tripId(), source.executionId(), photos));
        }
        if (kind == Kind.INITIAL) {
            var batch = entityManager.find(InitialAttachmentUploadBatch.class, batchId);
            batch.migrateConversionRoute(target);
        } else {
            var batch = entityManager.find(AdditionalAttachmentUploadBatch.class, batchId);
            batch.migrateConversionRoute(target);
        }
        entityManager.flush();
        log.info("변환 route 변경 kind={} batch_id={} route={}", kind, batchId, target);
        return true;
    }

    private Source lockSource(Kind kind, Long batchId) {
        if (kind == Kind.INITIAL) {
            var hint = entityManager.find(InitialAttachmentUploadBatch.class, batchId);
            if (hint == null) {
                return null;
            }
            var trip = lock(Trip.class, hint.getTripId());
            var execution = lock(TripCreationExecution.class, hint.getExecutionId());
            var batch = lock(InitialAttachmentUploadBatch.class, batchId);
            Assert.state(execution != null, "초기 실행 정보가 없습니다.");
            if (trip == null || trip.getDeletedAt() != null || execution.isTerminal()
                    || trip.getProcessingStatus() != ProcessingStatus.PROCESSING
                    || batch.getStatus() != InitialAttachmentUploadStatus.ACCEPTED
                    && batch.getStatus() != InitialAttachmentUploadStatus.CONVERTING) {
                return null;
            }
            Assert.state(Objects.equals(trip.getUserId(), batch.getUserId())
                    && Objects.equals(execution.getTripId(), batch.getTripId())
                    && Objects.equals(execution.getUserId(), batch.getUserId()), "초기 실행 소유자가 일치하지 않습니다.");
            return new Source(batch.getTripId(), batch.getExecutionId(), batch.getConversionRoute(), batch.getLeaseUntil());
        }
        var hint = entityManager.find(AdditionalAttachmentUploadBatch.class, batchId);
        if (hint == null) {
            return null;
        }
        var trip = lock(Trip.class, hint.getTripId());
        var execution = lock(AdditionalAttachmentExecution.class, hint.getAdditionId());
        var batch = lock(AdditionalAttachmentUploadBatch.class, batchId);
        Assert.state(execution != null, "추가 실행 정보가 없습니다.");
        if (trip == null || trip.getDeletedAt() != null || execution.isTerminal()
                || trip.getProcessingStatus() != ProcessingStatus.COMPLETED
                || batch.getStatus() != AdditionalAttachmentUploadStatus.VERIFIED
                && batch.getStatus() != AdditionalAttachmentUploadStatus.CONVERTING) {
            return null;
        }
        Assert.state(Objects.equals(trip.getUserId(), batch.getUserId())
                && Objects.equals(execution.getTripId(), batch.getTripId())
                && Objects.equals(execution.getUserId(), batch.getUserId()), "추가 실행 소유자가 일치하지 않습니다.");
        return new Source(batch.getTripId(), batch.getAdditionId(), batch.getConversionRoute(), batch.getLeaseExpiresAt());
    }

    private <T> T lock(Class<T> type, Object id) {
        T entity = entityManager.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        if (entity != null) {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        }
        return entity;
    }

    private String table(Kind kind) {
        return kind == Kind.INITIAL ? "initial_attachment_upload_batches" : "additional_attachment_upload_batches";
    }

    public record Candidate(
            long batchId,
            AttachmentConversionRoute route,
            LocalDateTime legacyLease,
            boolean claimed,
            boolean missingExecution
    ) {
    }

    private record Source(
            Long tripId,
            String executionId,
            AttachmentConversionRoute route,
            LocalDateTime leaseUntil
    ) {
    }
}
