package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripCreationExecutionRepository extends JpaRepository<TripCreationExecution, String> {
    Optional<TripCreationExecution> findByTripId(Long tripId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select execution from TripCreationExecution execution where execution.executionId = :id")
    Optional<TripCreationExecution> findForUpdate(@Param("id") String id);

    @Query("""
            select execution from TripCreationExecution execution
            where execution.endedAt is null and execution.inputClosed = false
              and execution.lastUploadActivityAt <= :before
            """)
    List<TripCreationExecution> findUploadExpired(@Param("before") LocalDateTime before, Pageable page);

    @Query("""
            select execution from TripCreationExecution execution
            where execution.endedAt is null and execution.resultRawPayload is null
              and execution.processPublishedAt <= :before
            """)
    List<TripCreationExecution> findResultExpired(@Param("before") LocalDateTime before, Pageable page);

    @Query("""
            select execution from TripCreationExecution execution
            where execution.endedAt is null and execution.status = 'RESULT_APPLYING'
              and (execution.resultLeaseUntil is null or execution.resultLeaseUntil <= :now)
              and (execution.resultNextAttemptAt is null or execution.resultNextAttemptAt <= :now)
            order by execution.resultReceivedAt
            """)
    List<TripCreationExecution> findResultsToApply(@Param("now") LocalDateTime now, Pageable page);

    List<TripCreationExecution> findByCancelPendingTrue(Pageable page);
    @Query("""
            select execution
            from TripCreationExecution execution
            where execution.endedAt is null
              and execution.processPublishedAt is not null
              and execution.resultRawPayload is null
              and execution.displayStatus = 'PROCESSING'
            """)
    List<TripCreationExecution> findAwaitingAiStatus();
}
