package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttachmentConversionTaskRepository extends JpaRepository<AttachmentConversionTask, String> {

    @Query(value = """
            SELECT task.* FROM attachment_conversion_tasks task
            JOIN attachment_conversion_outbox box
                ON box.task_id = task.task_id AND box.delivery_no = task.latest_delivery_no
            JOIN trips trip ON trip.trip_id = task.trip_id
            LEFT JOIN initial_attachment_upload_batches initial_batch ON initial_batch.upload_batch_id = task.initial_batch_id
            LEFT JOIN trip_creation_executions initial_execution ON initial_execution.execution_id = initial_batch.execution_id
            LEFT JOIN additional_attachment_upload_batches additional_batch ON additional_batch.upload_batch_id = task.additional_batch_id
            LEFT JOIN additional_attachment_executions additional_execution ON additional_execution.execution_id = additional_batch.addition_id
            WHERE task.status IN ('WAITING', 'RUNNING')
                AND (task.lease_until IS NULL OR task.lease_until <= :now)
                AND box.cdc_managed = true AND box.status = 'PUBLISHED' AND box.published_at <= :cutoff
                AND box.record_partition IS NOT NULL AND box.record_offset IS NOT NULL
                AND trip.deleted_at IS NULL AND trip.processing_status <> 'CANCELED'
                AND ((task.kind = 'INITIAL' AND initial_batch.conversion_route = 'KAFKA'
                        AND initial_execution.ended_at IS NULL AND initial_execution.status NOT IN ('COMPLETED', 'CANCELED', 'FAILED')
                        AND initial_batch.accepted_at IS NOT NULL AND initial_batch.status NOT IN ('PENDING', 'FAILED')
                        AND trip.processing_status = 'PROCESSING')
                    OR (task.kind = 'ADDITIONAL' AND additional_batch.conversion_route = 'KAFKA'
                        AND additional_execution.ended_at IS NULL AND additional_execution.status NOT IN ('COMPLETED', 'CANCELED', 'FAILED')
                        AND additional_batch.status NOT IN ('PENDING', 'FAILED') AND trip.processing_status = 'COMPLETED'))
                AND (:createdAt IS NULL OR task.created_at > :createdAt
                    OR (task.created_at = :createdAt AND task.task_id > :taskId))
            ORDER BY task.created_at, task.task_id LIMIT :limit
            """, nativeQuery = true)
    List<AttachmentConversionTask> findRecoveryCandidates(
            @Param("createdAt") LocalDateTime createdAt,
            @Param("taskId") String taskId,
            @Param("now") LocalDateTime now,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from AttachmentConversionTask task where task.id = :taskId")
    Optional<AttachmentConversionTask> findByIdForUpdate(@Param("taskId") String taskId);

    Optional<AttachmentConversionTask> findByInitialBatchIdAndLane(Long batchId, Lane lane);

    Optional<AttachmentConversionTask> findByAdditionalBatchIdAndLane(Long batchId, Lane lane);
}
