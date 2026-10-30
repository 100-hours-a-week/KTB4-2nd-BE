package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface TripEditCommandRecordRepository extends JpaRepository<TripEditCommandRecord, Long> {
    Optional<TripEditCommandRecord> findByUserIdAndRequestId(Long userId, String requestId);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE trip_edit_command_records SET result_json = NULL
            WHERE result_expires_at <= :now AND result_json IS NOT NULL
            ORDER BY result_expires_at, command_record_id LIMIT :batchSize
            """, nativeQuery = true)
    int clearExpiredResultBodies(@Param("now") LocalDateTime now, @Param("batchSize") int batchSize);
}
