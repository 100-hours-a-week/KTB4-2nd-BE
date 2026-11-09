package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TripAnalysisExecutionRepository extends JpaRepository<TripAnalysisExecution, String> {
    @Query(value = """
            SELECT (
                SELECT COUNT(*) FROM trip_analysis_executions execution
                JOIN additional_attachment_upload_outbox input ON input.outbox_id = execution.process_outbox_id
                WHERE (execution.ended_at IS NULL
                       OR (execution.requires_remote_termination_confirmation = TRUE
                           AND execution.remote_terminal_confirmed_at IS NULL))
                  AND JSON_CONTAINS(JSON_EXTRACT(input.payload, '$.attachments[*].analyze_storage_key'),
                                    JSON_QUOTE(:objectKey)) = 1
            ) + (
                SELECT COUNT(*) FROM additional_attachment_executions execution
                JOIN additional_attachment_upload_outbox input ON input.outbox_id = execution.process_outbox_id
                WHERE execution.processing_mode = 'LEGACY'
                  AND (execution.ended_at IS NULL
                       OR ((execution.error_code = 'AI_RESULT_TIMEOUT' OR execution.status = 'CANCELED')
                           AND (execution.process_outbox_id IS NOT NULL OR execution.process_published_at IS NOT NULL)
                           AND execution.result_raw_payload IS NULL
                           AND execution.remote_terminal_confirmed_at IS NULL))
                  AND JSON_CONTAINS(JSON_EXTRACT(input.payload, '$.attachments[*].analyze_storage_key'),
                                    JSON_QUOTE(:objectKey)) = 1
            ) + (
                SELECT COUNT(*) FROM trip_creation_executions execution
                WHERE (execution.ended_at IS NULL
                       OR (execution.requires_remote_termination_confirmation = TRUE
                           AND execution.remote_terminal_confirmed_at IS NULL))
                  AND execution.process_payload IS NOT NULL
                  AND JSON_CONTAINS(JSON_EXTRACT(execution.process_payload, '$.attachments[*].analyze_storage_key'),
                                    JSON_QUOTE(:objectKey)) = 1
            )
            """, nativeQuery = true)
    long countActiveInputReferences(@Param("objectKey") String objectKey);

    @Query(value = """
            SELECT pending.trip_id FROM (
                SELECT trip_id FROM trip_analysis_executions
                WHERE requires_remote_termination_confirmation = TRUE AND remote_terminal_confirmed_at IS NULL
                UNION
                SELECT trip_id FROM additional_attachment_executions
                WHERE processing_mode = 'LEGACY' AND ended_at IS NOT NULL
                  AND (error_code = 'AI_RESULT_TIMEOUT' OR status = 'CANCELED')
                  AND (process_outbox_id IS NOT NULL OR process_published_at IS NOT NULL)
                  AND result_raw_payload IS NULL AND remote_terminal_confirmed_at IS NULL
                UNION
                SELECT trip_id FROM trip_creation_executions
                WHERE requires_remote_termination_confirmation = TRUE AND remote_terminal_confirmed_at IS NULL
            ) pending WHERE pending.trip_id > :afterTripId ORDER BY pending.trip_id
            """, nativeQuery = true)
    List<Long> findUnconfirmedTripIdsAfter(@Param("afterTripId") Long afterTripId, Pageable page);

    Optional<TripAnalysisExecution> findFirstByTripIdAndEndedAtIsNull(Long tripId);

    @Query("""
            select execution from TripAnalysisExecution execution
            where execution.endedAt is null and execution.processPublishedAt is not null
              and execution.resultRawPayload is null and execution.status = 'AI_REQUESTED'
            """)
    List<TripAnalysisExecution> findAwaitingAiStatus();

    @Query("""
            select execution from TripAnalysisExecution execution, TripAnalysisExecutionUpload included
            where included.executionId = execution.executionId and included.additionId = :additionId
            order by included.id desc
            """)
    List<TripAnalysisExecution> findForAddition(@Param("additionId") String additionId, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select execution from TripAnalysisExecution execution where execution.executionId = :executionId")
    Optional<TripAnalysisExecution> findForUpdate(@Param("executionId") String executionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution from TripAnalysisExecution execution
            where execution.tripId = :tripId and execution.endedAt is null
            """)
    Optional<TripAnalysisExecution> findActiveForUpdate(@Param("tripId") Long tripId);

    @Query("""
            select execution from TripAnalysisExecution execution
            where execution.status = 'RESULT_APPLYING' and execution.endedAt is null
              and execution.resultRawPayload is not null
              and (execution.resultLeaseUntil is null or execution.resultLeaseUntil <= :now)
              and (execution.resultNextAttemptAt is null or execution.resultNextAttemptAt <= :now)
            order by execution.createdAt, execution.executionId
            """)
    List<TripAnalysisExecution> findResultsToApply(@Param("now") java.time.LocalDateTime now, Pageable page);

    @Query("""
            select execution from TripAnalysisExecution execution
            where execution.endedAt is null and execution.resultRawPayload is null
              and execution.processPublishedAt <= :before
            order by execution.createdAt, execution.executionId
            """)
    List<TripAnalysisExecution> findExpired(@Param("before") java.time.LocalDateTime before, Pageable page);

    @Query("""
            select execution from TripAnalysisExecution execution
            where execution.requiresRemoteTerminationConfirmation = true
              and execution.remoteTerminalConfirmedAt is null
            order by execution.createdAt, execution.executionId
            """)
    List<TripAnalysisExecution> findUnconfirmedRemoteTerminations(Pageable page);

    @Query("""
            select execution from TripAnalysisExecution execution
            where execution.tripId = :tripId and execution.requiresRemoteTerminationConfirmation = true
              and execution.remoteTerminalConfirmedAt is null
            order by execution.createdAt, execution.executionId
            """)
    List<TripAnalysisExecution> findUnconfirmedRemoteTerminationsByTripId(@Param("tripId") Long tripId);
}
