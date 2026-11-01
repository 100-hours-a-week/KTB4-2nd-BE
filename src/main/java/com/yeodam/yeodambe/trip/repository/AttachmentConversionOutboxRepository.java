package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AttachmentConversionOutboxRepository extends JpaRepository<AttachmentConversionOutbox, String> {
    @Query("""
            select outbox.requestId from AttachmentConversionOutbox outbox
            where outbox.taskId = :taskId and outbox.deliveryNo = :deliveryNo
            """)
    Optional<String> findRequestId(
            @Param("taskId") String taskId,
            @Param("deliveryNo") long deliveryNo
    );
}
