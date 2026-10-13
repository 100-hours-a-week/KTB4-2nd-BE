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
}