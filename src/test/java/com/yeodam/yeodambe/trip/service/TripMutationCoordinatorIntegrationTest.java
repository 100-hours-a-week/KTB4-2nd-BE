package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripMutationCoordinatorIntegrationTest {
    @Autowired
    private TripMutationCoordinator mutations;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 여행_변경은_별도_트랜잭션을_끝낸_후_반환하고_실패한_변경은_롤백한다() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        Long ownerId = fixture.createOwner();
        Long tripId = fixture.createTrip(ownerId);
        mutations.execute(tripId, () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.update("UPDATE trips SET trip_name = '확정한 이름' WHERE trip_id = ?", tripId);
            return null;
        });
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = ?", String.class, tripId))
                .isEqualTo("확정한 이름");
        assertThatThrownBy(() -> mutations.execute(tripId, () -> {
            jdbc.update("UPDATE trips SET trip_name = '실패한 이름' WHERE trip_id = ?", tripId);
            throw new IllegalStateException(TripUploadTestErrorMessage.ANALYSIS_OUTBOX_FAILURE.message());
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = ?", String.class, tripId))
                .isEqualTo("확정한 이름");
    }

    @Test
    void 여러_여행의_변경을_함께_확정하고_실패하면_모두_롤백한다() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        Long firstTripId = fixture.createTrip(fixture.createOwner());
        Long secondTripId = fixture.createTrip(fixture.createOwner());
        List<Long> tripIds = List.of(secondTripId, firstTripId, firstTripId);

        Integer changed = mutations.executeAll(tripIds, () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return jdbc.update("""
                    UPDATE trips SET trip_name = '일괄확정'
                    WHERE trip_id IN (?, ?)
                    """, firstTripId, secondTripId);
        });

        assertThat(changed).isEqualTo(2);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThatThrownBy(() -> mutations.runAll(tripIds, () -> {
            jdbc.update("""
                    UPDATE trips SET trip_name = '일괄실패'
                    WHERE trip_id IN (?, ?)
                    """, firstTripId, secondTripId);
            throw new IllegalStateException(TripUploadTestErrorMessage.ANALYSIS_OUTBOX_FAILURE.message());
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trips
                WHERE trip_id IN (?, ?) AND trip_name = '일괄확정'
                """, Integer.class, firstTripId, secondTripId)).isEqualTo(2);
    }
}
