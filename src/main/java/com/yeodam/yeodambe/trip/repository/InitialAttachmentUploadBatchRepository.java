package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InitialAttachmentUploadBatchRepository
        extends JpaRepository<InitialAttachmentUploadBatch, Long> {

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
}