package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdditionalAttachmentExecutionRepository
        extends JpaRepository<AdditionalAttachmentExecution, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution
            from AdditionalAttachmentExecution execution
            where execution.executionId = :executionId
            """)
    Optional<AdditionalAttachmentExecution> findForUpdate(
            @Param("executionId") String executionId
    );

    Optional<AdditionalAttachmentExecution>
    findFirstByTripIdAndUserIdOrderByCreatedAtDescExecutionIdDesc(
            Long tripId,
            Long userId
    );

    boolean existsByTripIdAndEndedAtIsNull(Long tripId);

    java.util.List<AdditionalAttachmentExecution> findByTripIdAndUserIdAndEndedAtIsNull(Long tripId, Long userId);

    @Query("""
            select execution from AdditionalAttachmentExecution execution
            where execution.endedAt is null and execution.resultRawPayload is not null
              and (execution.resultLeaseUntil is null or execution.resultLeaseUntil <= :now)
              and (execution.resultNextAttemptAt is null or execution.resultNextAttemptAt <= :now)
            order by execution.createdAt, execution.executionId
            """)
    java.util.List<AdditionalAttachmentExecution> findResultsToApply(
            @Param("now") java.time.LocalDateTime now, org.springframework.data.domain.Pageable pageable);

    @Query("""
            select execution from AdditionalAttachmentExecution execution
            where execution.endedAt is null and execution.resultRawPayload is null
              and ((execution.inputClosed = false and execution.lastUploadActivityAt <= :uploadBefore)
                or (execution.processPublishedAt is not null and execution.processPublishedAt <= :resultBefore))
            order by execution.createdAt
            """)
    java.util.List<AdditionalAttachmentExecution> findExpired(
            @Param("uploadBefore") java.time.LocalDateTime uploadBefore,
            @Param("resultBefore") java.time.LocalDateTime resultBefore, org.springframework.data.domain.Pageable pageable);
}