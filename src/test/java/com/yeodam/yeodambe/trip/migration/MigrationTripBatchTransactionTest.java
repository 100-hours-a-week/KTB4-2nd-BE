package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JDBC transaction boundaries; H2 is not a substitute for MySQL/load validation. */
class MigrationTripBatchTransactionTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    MigrationTripBatchService batch;
    MigrationTripApplyService apply;
    CountingTransactions tx;

    static class CountingTransactions extends DataSourceTransactionManager {
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        CountingTransactions(DataSource source) {super(source);}
        @Override protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status); commits.incrementAndGet();
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            super.doRollback(status); rollbacks.incrementAndGet();
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcTemplate jdbc(DataSource dataSource) {return new JdbcTemplate(dataSource);}
        @Bean CountingTransactions transactionManager(DataSource source) {return new CountingTransactions(source);}
        @Bean MigrationTripResultRepository results(JdbcTemplate jdbc) {return new MigrationTripResultRepository(jdbc);}
        @Bean TripService trips(JdbcTemplate jdbc) {
            var trips = mock(TripService.class);
            when(trips.createTrip(anyLong(), any())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                var request = (com.yeodam.yeodambe.trip.service.request.TripCreateRequest) i.getArgument(1);
                if (request.tripName().equals("duplicate")) throw new TripNameDuplicatedException();
                if (request.tripName().equals("unavailable")) throw new IllegalStateException("DB unavailable");
                long id = Math.abs((long) request.tripName().hashCode());
                jdbc.update("INSERT INTO test_trips VALUES (?)", id);
                jdbc.update("INSERT INTO test_regions VALUES (?, ?)", id, id);
                return new TripCreateResponse(id, ProcessingStatus.PROCESSING);
            });
            return trips;
        }
        @Bean MigrationTripApplyService apply(MigrationTripResultRepository results, TripService trips) {
            return new MigrationTripApplyService(results, trips);
        }
        @Bean MigrationTripBatchService batches(MigrationTripApplyService apply) {return new MigrationTripBatchService(apply);}
    }

    @BeforeEach void prepare() {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
        tx = context.getBean(CountingTransactions.class);
        batch = context.getBean(MigrationTripBatchService.class);
        apply = context.getBean(MigrationTripApplyService.class);
        jdbc.execute("CREATE TABLE test_trips (trip_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE test_regions (region_id BIGINT PRIMARY KEY, trip_id BIGINT REFERENCES test_trips(trip_id))");
        jdbc.execute("CREATE TABLE migration_trip_request_result (request_id VARCHAR(36) PRIMARY KEY, user_id BIGINT, payload_hash CHAR(64), status VARCHAR(16), trip_id BIGINT, error_code VARCHAR(64), updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    }
    @AfterEach void close() {context.close();}
    MigrationTripCommand command(String name) {
        return new MigrationTripCommand(UUID.randomUUID().toString(), 7L, "hash", name,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), List.of("42150"));
    }
    int count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);}

    @Test void tenCommandsUseOneCommitAndReplayDoesNotDuplicate() {
        var commands = java.util.stream.IntStream.range(0, 10).mapToObj(i -> command("trip" + i)).toList();
        assertThat(batch.applyBatch(commands)).containsExactlyElementsOf(commands);
        assertThat(tx.commits.get()).isEqualTo(1);
        assertThat(count("test_trips")).isEqualTo(10);
        assertThat(count("test_regions")).isEqualTo(10);
        assertThat(batch.applyBatch(commands)).isEmpty();
        assertThat(count("test_trips")).isEqualTo(10);
        assertThat(count("migration_trip_request_result")).isEqualTo(10);
    }

    @Test void businessFailureRollsBackWholeBatchBeforeOrderedSingleFallback() {
        var first = command("first"); var duplicate = command("duplicate"); var last = command("last");
        assertThatThrownBy(() -> batch.applyBatch(List.of(first, duplicate, last)))
                .isInstanceOf(TripNameDuplicatedException.class);
        assertThat(tx.commits.get()).isZero();
        assertThat(tx.rollbacks.get()).isEqualTo(1);
        assertThat(count("test_trips")).isZero();
        assertThat(count("test_regions")).isZero();
        assertThat(count("migration_trip_request_result")).isZero();
        apply.apply(first);
        assertThatThrownBy(() -> apply.apply(duplicate)).isInstanceOf(TripNameDuplicatedException.class);
        apply.recordFailure(duplicate, "TRIP_NAME_DUPLICATED");
        apply.apply(last);
        assertThat(count("test_trips")).isEqualTo(2);
        assertThat(count("migration_trip_request_result")).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT status FROM migration_trip_request_result ORDER BY status", String.class))
                .containsExactly("COMPLETED", "COMPLETED", "FAILED");
    }

    @Test void runtimeFailureCannotLeaveEarlierTripOrJournalCommitted() {
        assertThatThrownBy(() -> batch.applyBatch(List.of(command("first"), command("unavailable"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tx.commits.get()).isZero();
        assertThat(count("test_trips")).isZero();
        assertThat(count("migration_trip_request_result")).isZero();
    }

    @Test void replayWithConflictingPayloadRollsBackNewWorkBeforeIt() {
        var original = command("original"); apply.apply(original);
        var conflict = new MigrationTripCommand(original.requestId(), 7L, "different",
                original.tripName(), original.startDate(), original.endDate(), original.regionCodes());
        assertThatThrownBy(() -> batch.applyBatch(List.of(command("new"), conflict)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("test_trips")).isEqualTo(1);
        assertThat(count("migration_trip_request_result")).isEqualTo(1);
    }
}
