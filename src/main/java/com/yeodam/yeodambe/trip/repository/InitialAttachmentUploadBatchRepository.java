package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InitialAttachmentUploadBatchRepository
        extends JpaRepository<InitialAttachmentUploadBatch, Long> {

    boolean existsByTripId(Long tripId);

    Optional<InitialAttachmentUploadBatch> findByUploadIdAndTripIdAndUserId(
            String uploadId, Long tripId, Long userId);

    boolean existsByTripIdAndStatus(Long tripId, InitialAttachmentUploadStatus status);

    Optional<InitialAttachmentUploadBatch>
    findFirstByTripIdAndUserIdOrderByIdDesc(Long tripId, Long userId);

    Optional<InitialAttachmentUploadBatch>
    findByExecutionIdAndBatchNo(String executionId, Integer batchNo);

    List<InitialAttachmentUploadBatch>
    findAllByExecutionIdOrderByBatchNoAsc(String executionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select batch
            from InitialAttachmentUploadBatch batch
            where batch.uploadId = :uploadId
              and batch.tripId = :tripId
              and batch.userId = :userId
            """)
    Optional<InitialAttachmentUploadBatch> findForUpdate(
            @Param("uploadId") String uploadId,
            @Param("tripId") Long tripId,
            @Param("userId") Long userId
    );
    @Query("""
            select batch from InitialAttachmentUploadBatch batch, TripCreationExecution execution
            where batch.executionId = execution.executionId and execution.endedAt is null
              and batch.status in ('ACCEPTED', 'CONVERTING')
              and (batch.leaseUntil is null or batch.leaseUntil <= :now)
              and (batch.nextAttemptAt is null or batch.nextAttemptAt <= :now)
            order by batch.id
            """)
    List<InitialAttachmentUploadBatch> findPendingConversion(
            @Param("now") LocalDateTime now,
            Pageable page
    );

    @Query("""
            select batch from InitialAttachmentUploadBatch batch, TripCreationExecution execution
            where batch.executionId = execution.executionId and execution.endedAt is null
              and batch.status = 'READY' and batch.deliveryStatus <> 'SENT'
              and (batch.deliveryLeaseUntil is null or batch.deliveryLeaseUntil <= :now)
              and (batch.deliveryNextAttemptAt is null or batch.deliveryNextAttemptAt <= :now)
            order by batch.id
            """)
    List<InitialAttachmentUploadBatch> findPendingDelivery(
            @Param("now") LocalDateTime now,
            Pageable page
    );
}
