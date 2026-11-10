package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.controller.TripController;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.fixture.TripUploadTestErrorMessage;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.BeanPropertyBindingResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripMetadataMutationIntegrationTest {
    @Autowired
    private TripController controller;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JdbcConnectionDetails database;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private TripService transactions;
    @MockitoBean
    private TripEditNotificationPublisher publisher;
    private Long ownerId;
    private Long tripId;
    private final AtomicBoolean committedWithLock = new AtomicBoolean();

    @BeforeEach
    void prepareTrip() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
    }

    @Test
    void 여행_정보_수정은_락을_커밋까지_유지하고_변경된_시퀀스로_알림을_한_번_기록한다() {
        doAnswer(invocation -> {
            observeMutation();
            return invocation.callRealMethod();
        }).when(transactions).updateTrip(anyLong(), anyLong(), any(TripUpdateRequest.class));

        update("변경된 여행");
        update("변경된 여행");

        assertThat(committedWithLock).isTrue();
        assertThat(lock().isLocked()).isFalse();
        assertThat(sequence()).isEqualTo(1L);
        assertThat(notificationCount()).isEqualTo(1L);
        JsonNode event = mapper.readTree(jdbc.queryForObject(
                "SELECT event_json FROM trip_edit_notification_outbox WHERE target_id = ?",
                String.class, tripId));
        assertThat(event.path("target").path("type").asString()).isEqualTo("TRIP");
        assertThat(event.path("payload").path("message").asString()).isEqualTo("TRIP_UPDATED");
        JsonNode data = event.path("payload").path("data");
        assertThat(data.path("sequence").asLong()).isEqualTo(sequence());
        assertThat(data.path("changes").path("tripName").asString()).isEqualTo("변경된 여행");
        assertThat(data.path("changes").size()).isEqualTo(1);
    }

    @Test
    void 날짜와_지역을_변경하면_정규화된_저장_결과를_알림에_담는다() {
        TripUpdateRequest request = new TripUpdateRequest(
                null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), List.of("26000")
        );

        controller.updateTrip(tripId, request, new BeanPropertyBindingResult(request, "request"), jwt(ownerId));

        JsonNode event = mapper.readTree(jdbc.queryForObject(
                "SELECT event_json FROM trip_edit_notification_outbox WHERE target_id = ?",
                String.class, tripId));
        JsonNode changes = event.path("payload").path("data").path("changes");
        assertThat(changes.has("tripName")).isFalse();
        assertThat(changes.path("startDate").asString()).isEqualTo("2026-08-01");
        assertThat(changes.path("endDate").asString()).isEqualTo("2026-08-02");
        assertThat(changes.path("regionCodes").get(0).asString()).isEqualTo("26000");
        Long regionId = jdbc.queryForObject(
                "SELECT region_id FROM trip_regions WHERE trip_id = ? AND deleted_at IS NULL",
                Long.class, tripId);
        assertThat(changes.path("regions").get(0).path("regionId").asLong()).isEqualTo(regionId);
        assertThat(changes.path("regions").get(0).path("regionName").asString()).isEqualTo("부산광역시");
        assertThat(sequence()).isEqualTo(1L);
    }

    @Test
    void 알림_저장이_실패하면_정보와_시퀀스를_롤백하고_재시도에서_한_번만_반영한다() throws SQLException {
        String trigger = "reject_metadata_notification_" + tripId;
        createFailureTrigger("CREATE TRIGGER " + trigger
                + " BEFORE INSERT ON trip_edit_notification_outbox FOR EACH ROW BEGIN "
                + "IF NEW.target_type = 'TRIP' AND NEW.target_id = " + tripId + " THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '"
                + TripUploadTestErrorMessage.NOTIFICATION_OUTBOX_FAILURE.message() + "'; END IF; END");
        try {
            assertThatThrownBy(() -> update("롤백할 여행")).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT trip_name FROM trips WHERE trip_id = ?",
                    String.class, tripId)).isEqualTo("분석여행");
            assertThat(sequence()).isZero();
            assertThat(notificationCount()).isZero();
            assertThat(lock().isLocked()).isFalse();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }

        update("롤백할 여행");

        assertThat(sequence()).isEqualTo(1L);
        assertThat(notificationCount()).isEqualTo(1L);
    }

    @Test
    void 즐겨찾기도_락을_커밋까지_유지하고_다른_사용자는_변경하지_못한다() {
        doAnswer(invocation -> {
            observeMutation();
            return invocation.callRealMethod();
        }).when(transactions).registerFavorite(anyLong(), anyLong());
        doAnswer(invocation -> {
            observeMutation();
            return invocation.callRealMethod();
        }).when(transactions).removeFavorite(anyLong(), anyLong());

        controller.registerFavorite(tripId, jwt(ownerId));
        assertThat(favorite()).isTrue();
        assertThat(committedWithLock).isTrue();
        committedWithLock.set(false);
        assertThatThrownBy(() -> controller.removeFavorite(tripId, jwt(ownerId + 1)))
                .isInstanceOf(TripNotFoundException.class);
        assertThat(favorite()).isTrue();
        assertThat(committedWithLock).isFalse();

        controller.removeFavorite(tripId, jwt(ownerId));

        assertThat(favorite()).isFalse();
        assertThat(committedWithLock).isTrue();
        assertThat(lock().isLocked()).isFalse();
        assertThat(notificationCount()).isZero();
    }

    private void createFailureTrigger(String statement) throws SQLException {
        try (Connection connection = DriverManager.getConnection(database.getJdbcUrl(), "root", database.getPassword());
             Statement ddl = connection.createStatement()) {
            ddl.execute(statement);
        }
    }

    private void update(String name) {
        TripUpdateRequest request = new TripUpdateRequest(name, null, null, null);
        controller.updateTrip(tripId, request, new BeanPropertyBindingResult(request, "request"), jwt(ownerId));
    }

    private void observeMutation() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(lock().isHeldByCurrentThread()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                committedWithLock.set(lock().isHeldByCurrentThread());
            }
        });
    }

    private RLock lock() {
        return redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
    }

    private long sequence() {
        return jdbc.queryForObject("SELECT change_sequence FROM trips WHERE trip_id = ?", Long.class, tripId);
    }

    private long notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trip_edit_notification_outbox WHERE target_id = ?",
                Long.class, tripId);
    }

    private boolean favorite() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_favorite FROM trips WHERE trip_id = ?",
                Boolean.class, tripId));
    }

    private Jwt jwt(Long userId) {
        return Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString()).build();
    }
}
