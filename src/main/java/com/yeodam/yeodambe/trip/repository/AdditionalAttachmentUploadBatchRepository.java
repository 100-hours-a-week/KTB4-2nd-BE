package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Collection;
import java.util.Optional;

public interface AdditionalAttachmentUploadBatchRepository
        extends JpaRepository<AdditionalAttachmentUploadBatch, Long> {

    boolean existsByTripIdAndStatusIn(Long tripId, Collection<AdditionalAttachmentUploadStatus> statuses);

    boolean existsByTripIdAndAdditionIdNotAndStatusIn(
            Long tripId,
            String additionId,
            Collection<AdditionalAttachmentUploadStatus> statuses
    );

    Optional<AdditionalAttachmentUploadBatch>
    findByAdditionIdAndBatchNo(String additionId, Integer batchNo);

    List<AdditionalAttachmentUploadBatch>
    findAllByAdditionIdOrderByBatchNoAsc(String additionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select batch
            from AdditionalAttachmentUploadBatch batch
            where batch.uploadId = :uploadId
              and batch.tripId = :tripId
              and batch.userId = :userId
            """)
    Optional<AdditionalAttachmentUploadBatch> findForUpdate(
            @Param("uploadId") String uploadId,
            @Param("tripId") Long tripId,
            @Param("userId") Long userId
    );

    @Query("""
            select batch
            from AdditionalAttachmentUploadBatch batch,
                 AdditionalAttachmentExecution execution
            where batch.additionId = execution.executionId
              and batch.tripId = execution.tripId
              and batch.userId = execution.userId
              and execution.endedAt is null
              and batch.status in ('VERIFIED', 'CONVERTING')
              and (batch.leaseExpiresAt is null
                   or batch.leaseExpiresAt <= :now)
              and (batch.nextAttemptAt is null
                   or batch.nextAttemptAt <= :now)
            order by batch.id
            """)
    List<AdditionalAttachmentUploadBatch> findPendingConversion(
            @Param("now") LocalDateTime now,
            Pageable page
    );
}
