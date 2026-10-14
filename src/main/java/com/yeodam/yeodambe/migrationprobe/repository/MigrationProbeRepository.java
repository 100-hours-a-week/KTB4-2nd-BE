package com.yeodam.yeodambe.migrationprobe.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@Profile("staging | prod")
@RequiredArgsConstructor
public class MigrationProbeRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(String runId, String requestId, String payloadHash) {
        jdbcTemplate.update("""
                INSERT INTO migration_write_probe (run_id, request_id, payload_hash)
                VALUES (?, ?, ?)
                """, runId, requestId, payloadHash);
    }

    public Optional<String> findPayloadHash(String runId, String requestId) {
        return jdbcTemplate.query("""
                        SELECT payload_hash FROM migration_write_probe
                        WHERE run_id = ? AND request_id = ?
                        """, (row, index) -> row.getString("payload_hash"), runId, requestId)
                .stream()
                .findFirst();
    }
}
