package com.yeodam.yeodambe.migrationprobe.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.migrationprobe.exception.MigrationProbeHashConflictException;
import com.yeodam.yeodambe.migrationprobe.exception.MigrationProbeStoreUnavailableException;
import com.yeodam.yeodambe.migrationprobe.service.MigrationProbeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles({"test", "staging"})
@Import(TestcontainersConfiguration.class)
class MigrationProbeRepositoryIntegrationTest {

    @Autowired
    private MigrationProbeService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareTable() {
        jdbc.execute("DROP TABLE IF EXISTS migration_write_probe");
        jdbc.execute("""
                CREATE TABLE migration_write_probe (
                    run_id CHAR(36) NOT NULL,
                    request_id CHAR(36) NOT NULL,
                    payload_hash CHAR(64) NOT NULL,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (run_id, request_id)
                ) ENGINE=InnoDB
                """);
    }

    @Test
    void committedInsertIsVisibleFromIndependentConnection() {
        String runId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();

        assertThat(service.write(runId, requestId, "a".repeat(64)).result()).isEqualTo("created");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_write_probe", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL FROM migration_write_probe", Boolean.class)).isTrue();
        assertThat(service.write(runId, requestId, "a".repeat(64)).result()).isEqualTo("already_exists");
    }

    @Test
    void differentHashPreservesExistingRow() {
        String runId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        service.write(runId, requestId, "a".repeat(64));

        assertThatThrownBy(() -> service.write(runId, requestId, "b".repeat(64)))
                .isInstanceOf(MigrationProbeHashConflictException.class);
        assertThat(jdbc.queryForObject("SELECT payload_hash FROM migration_write_probe", String.class))
                .isEqualTo("a".repeat(64));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_write_probe", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestsCreateOneRow() throws Exception {
        String runId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 8)
                    .mapToObj(index -> executor.submit((Callable<String>) () -> {
                        start.await();
                        return service.write(runId, requestId, "a".repeat(64)).result();
                    }))
                    .toList();
            start.countDown();
            int created = 0;
            for (var future : futures) {
                String result = future.get(15, TimeUnit.SECONDS);
                assertThat(result).isIn("created", "already_exists");
                if (result.equals("created")) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_write_probe", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingTableDoesNotReturnSuccess() {
        jdbc.execute("DROP TABLE migration_write_probe");

        assertThatThrownBy(() -> service.write(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "a".repeat(64)))
                .isInstanceOf(MigrationProbeStoreUnavailableException.class);
    }
}
