package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AdditionalAttachmentUploadBatchRepository
        extends JpaRepository<AdditionalAttachmentUploadBatch, Long> {

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
}
