package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@JdbcTest
@ActiveProfiles("local")
@Import({MigrationTripResultRepository.class, MigrationTripApplyService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MigrationTripTransactionTest {
    private static final String ID = "51961261-7761-4587-ae2b-55c0452924bd";
    @Autowired JdbcTemplate jdbc;
    @Autowired MigrationTripApplyService apply;
    @MockitoBean TripService trips;

    private final MigrationTripCommand command = new MigrationTripCommand(
            ID, 7L, "a".repeat(64), "강릉", LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 2), List.of("42150"));

    @BeforeEach
    void schema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS migration_trip_request_result (
                    request_id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL,
                    payload_hash CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
                    trip_id BIGINT, error_code VARCHAR(64),
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_trip (trip_id BIGINT PRIMARY KEY)");
        jdbc.execute("DELETE FROM migration_trip_request_result");
        jdbc.execute("DELETE FROM test_trip");
    }

    @Test
    void 재소비해도_여행과_결과는_한번만_커밋된다() {
        when(trips.createTrip(eq(7L), any())).thenAnswer(call -> {
            jdbc.update("INSERT INTO test_trip (trip_id) VALUES (42)");
            return new TripCreateResponse(42L, ProcessingStatus.PROCESSING);
        });

        apply.apply(command);
        apply.apply(command);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_trip", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_trip_request_result", Integer.class))
                .isEqualTo(1);
        verify(trips, times(1)).createTrip(eq(7L), any());
    }

    @Test
    void 여행_작성_도중_실패하면_저널과_여행을_함께_롤백한다() {
        when(trips.createTrip(eq(7L), any())).thenAnswer(call -> {
            jdbc.update("INSERT INTO test_trip (trip_id) VALUES (42)");
            throw new IllegalStateException("simulated failure");
        });

        assertThatThrownBy(() -> apply.apply(command)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_trip", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migration_trip_request_result", Integer.class))
                .isZero();
    }
}
