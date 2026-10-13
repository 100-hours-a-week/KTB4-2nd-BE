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