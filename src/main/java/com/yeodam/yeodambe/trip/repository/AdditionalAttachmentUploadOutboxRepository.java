package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AdditionalAttachmentUploadOutboxRepository
        extends JpaRepository<AdditionalAttachmentUploadOutbox, Long> {

    Optional<AdditionalAttachmentUploadOutbox>
    findByUploadBatchIdAndMessageType(
            Long uploadBatchId,
            String messageType
    );

    @Query("""
            select outbox from AdditionalAttachmentUploadOutbox outbox,
                AdditionalAttachmentUploadBatch batch, AdditionalAttachmentExecution execution
            where outbox.uploadBatchId = batch.id and batch.additionId = execution.executionId
              and execution.endedAt is null
              and outbox.status <> com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadOutbox.Status.PUBLISHED
              and (outbox.leaseUntil is null or outbox.leaseUntil <= :now)
              and (outbox.nextAttemptAt is null or outbox.nextAttemptAt <= :now)
            order by outbox.id
            """)
    java.util.List<AdditionalAttachmentUploadOutbox> findPending(
            @Param("now") java.time.LocalDateTime now, org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select outbox
        from AdditionalAttachmentUploadOutbox outbox
        where outbox.id = :outboxId
        """)
    Optional<AdditionalAttachmentUploadOutbox> findByIdForUpdate(
            @Param("outboxId") Long outboxId
    );
}