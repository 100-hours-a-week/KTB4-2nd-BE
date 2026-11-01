package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AttachmentConversionOutboxRepository extends JpaRepository<AttachmentConversionOutbox, String> {
    Optional<AttachmentConversionOutbox> findByTaskIdAndDeliveryNo(String taskId, long deliveryNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from AttachmentConversionOutbox outbox where outbox.id = :outboxId")
    Optional<AttachmentConversionOutbox> findByIdForUpdate(@Param("outboxId") String outboxId);

    @Query("""
            select outbox.requestId from AttachmentConversionOutbox outbox
            where outbox.taskId = :taskId and outbox.deliveryNo = :deliveryNo
            """)
    Optional<String> findRequestId(
            @Param("taskId") String taskId,
            @Param("deliveryNo") long deliveryNo
    );
}
