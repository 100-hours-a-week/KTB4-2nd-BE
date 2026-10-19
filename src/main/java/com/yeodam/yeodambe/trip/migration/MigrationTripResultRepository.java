package com.yeodam.yeodambe.trip.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MigrationTripResultRepository {
    private final JdbcTemplate jdbc;

    public MigrationTripResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(String requestId) {
        List<Entry> rows = jdbc.query("""
                SELECT request_id, user_id, payload_hash, status, trip_id, error_code
                FROM migration_trip_request_result WHERE request_id = ?
                """, (rs, rowNum) -> new Entry(
                rs.getString("request_id"), rs.getLong("user_id"),
                rs.getString("payload_hash"), rs.getString("status"),
                rs.getObject("trip_id", Long.class), rs.getString("error_code")), requestId);
        return rows.stream().findFirst();
    }

    public void insertApplying(MigrationTripCommand command) {
        jdbc.update("""
                INSERT INTO migration_trip_request_result
                    (request_id, user_id, payload_hash, status)
                VALUES (?, ?, ?, 'APPLYING')
                """, command.requestId(), command.userId(), command.payloadHash());
    }

    public void complete(String requestId, Long tripId) {
        jdbc.update("""
                UPDATE migration_trip_request_result
                SET status = 'COMPLETED', trip_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE request_id = ?
                """, tripId, requestId);
    }

    public void fail(MigrationTripCommand command, String errorCode) {
        jdbc.update("""
                INSERT INTO migration_trip_request_result
                    (request_id, user_id, payload_hash, status, error_code)
                VALUES (?, ?, ?, 'FAILED', ?)
                """, command.requestId(), command.userId(), command.payloadHash(), errorCode);
    }

    public record Entry(
            String requestId, Long userId, String payloadHash,
            String status, Long tripId, String errorCode
    ) {
        public MigrationTripRequestStatus toStatus() {
            return new MigrationTripRequestStatus(requestId, status, tripId, errorCode);
        }
    }
}
