package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AttachmentConversionTaskRepository extends JpaRepository<AttachmentConversionTask, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from AttachmentConversionTask task where task.id = :taskId")
    Optional<AttachmentConversionTask> findByIdForUpdate(@Param("taskId") String taskId);

    Optional<AttachmentConversionTask> findByInitialBatchIdAndLane(Long batchId, Lane lane);

    Optional<AttachmentConversionTask> findByAdditionalBatchIdAndLane(Long batchId, Lane lane);
}
