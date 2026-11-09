package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AdditionalAttachmentExecutionRepository
        extends JpaRepository<AdditionalAttachmentExecution, String> {
    Optional<AdditionalAttachmentExecution> findFirstByTripIdAndUserIdAndEndedAtIsNullOrderByCreatedAtDescExecutionIdDesc(
            Long tripId, Long userId);

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

    @Query("""
            select coalesce(sum(execution.totalAttachmentCount), 0)
            from AdditionalAttachmentExecution execution
            where execution.tripId = :tripId and execution.endedAt is null
            """)
    long countDeclaredPendingAttachments(@Param("tripId") Long tripId);

    @Query("""
            select count(execution) > 0 from AdditionalAttachmentExecution execution
            where execution.tripId = :tripId and execution.processingMode = 'LEGACY'
              and execution.endedAt is null
            """)
    boolean hasActiveLegacyExecution(@Param("tripId") Long tripId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution from AdditionalAttachmentExecution execution
            where execution.tripId = :tripId and execution.processingMode = 'V3'
              and execution.endedAt is null and execution.inputClosed = true
              and execution.quarantinedAt is null
            order by execution.createdAt, execution.executionId
            """)
    List<AdditionalAttachmentExecution> findAnalysisCandidatesForUpdate(@Param("tripId") Long tripId);

    @Query("""
            select distinct execution.tripId from AdditionalAttachmentExecution execution
            where execution.processingMode = 'V3' and execution.endedAt is null
              and execution.inputClosed = true
              and execution.tripId > :afterTripId
              and not exists (
                  select active.executionId from TripAnalysisExecution active
                  where active.tripId = execution.tripId and active.endedAt is null
              )
            order by execution.tripId
            """)
    List<Long> findAnalysisCandidateTripIdsAfter(
            @Param("afterTripId") Long afterTripId,
            org.springframework.data.domain.Pageable page
    );

    @Query("""
            select execution from AdditionalAttachmentExecution execution
            where execution.tripId = :tripId and execution.processingMode = 'LEGACY'
              and execution.endedAt is not null
              and (execution.errorCode = 'AI_RESULT_TIMEOUT' or execution.status = 'CANCELED')
              and (execution.processOutboxId is not null or execution.processPublishedAt is not null)
              and execution.resultRawPayload is null
              and execution.remoteTerminalConfirmedAt is null
            order by execution.createdAt, execution.executionId
            """)
    List<AdditionalAttachmentExecution> findUnconfirmedLegacyTimeouts(@Param("tripId") Long tripId);

    @Query("""
        select distinct execution.tripId
        from AdditionalAttachmentExecution execution
        where execution.userId = :userId
          and execution.tripId in :tripIds
          and execution.endedAt is null
        """)
    List<Long> findActiveTripIds(
            @Param("userId") Long userId,
            @Param("tripIds") List<Long> tripIds
    );

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
    @Query("""
            select execution
            from AdditionalAttachmentExecution execution
            where execution.endedAt is null
              and execution.processPublishedAt is not null
              and execution.resultRawPayload is null
              and execution.displayStatus = 'PROCESSING'
            """)
    List<AdditionalAttachmentExecution> findAwaitingAiStatus();
}
