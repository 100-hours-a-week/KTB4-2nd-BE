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
            where execution.requiresRemoteTerminationConfirmation = true
              and execution.remoteTerminalConfirmedAt is null
            order by execution.createdAt, execution.executionId
            """)
    List<TripAnalysisExecution> findUnconfirmedRemoteTerminations(Pageable page);
}
