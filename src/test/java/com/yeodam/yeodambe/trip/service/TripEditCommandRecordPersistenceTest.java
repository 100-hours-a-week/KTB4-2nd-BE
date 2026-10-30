package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.common.exception.TripEditCommandResultExpiredException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord.CommandType;
import com.yeodam.yeodambe.trip.repository.TripEditCommandRecordRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripEditCommandResultResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TripEditCommandRecordService.class, TripEditCommandRecordPersistenceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripEditCommandRecordPersistenceTest {
    private static final LocalDateTime COMPLETED = LocalDateTime.of(2026, 10, 29, 12, 0);
    private static final String HASH = "a".repeat(64);
    @Autowired private TripEditCommandRecordService service;
    @Autowired private TripEditCommandRecordRepository records;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ObjectMapper json;
    private TransactionTemplate transactions;
    private Long userId;
    private Long tripId;

    @BeforeEach
    void fixture() {
        transactions = new TransactionTemplate(transactionManager);
        userId = users.save(new User(UUID.randomUUID() + "@record.test", "회원")).getUserId();
        tripId = trips.saveAndFlush(new Trip(userId, "원래여행", LocalDate.now(), LocalDate.now())).getId();
    }

    @Test
    void 업무_변경과_결과를_같은_트랜잭션으로_커밋한다() {
        transactions.executeWithoutResult(status -> {
            changeTrip();
            save("req-A");
        });

        assertThat(trips.findById(tripId).orElseThrow().getTripName()).isEqualTo("바뀐여행");
        assertThat(service.findResult(userId, "req-A", tripId, CommandType.UPDATE_TRIP, HASH, COMPLETED))
                .contains(result("req-A"));
    }

    @Test
    void 업무가_롤백되면_결과_기록도_남지_않는다() {
        transactions.executeWithoutResult(status -> {
            changeTrip();
            save("req-A");
            status.setRollbackOnly();
        });

        assertOriginalTrip();
        assertThat(records.findByUserIdAndRequestId(userId, "req-A")).isEmpty();
    }

    @Test
    void 결과_직렬화에_실패하면_여행만_부분_커밋하지_않는다() {
        doThrow(mock(JacksonException.class)).when(json).writeValueAsString(result("req-A"));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            changeTrip();
            save("req-A");
        })).isInstanceOf(IllegalStateException.class);

        assertOriginalTrip();
        assertThat(records.findByUserIdAndRequestId(userId, "req-A")).isEmpty();
    }

    @Test
    void 기록_DB_제약_위반이면_업무를_롤백하고_기존_결과를_보존한다() {
        transactions.executeWithoutResult(status -> save("req-A"));
        TripEditCommandRecord original = records.findByUserIdAndRequestId(userId, "req-A").orElseThrow();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            changeTrip();
            save("req-A");
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertOriginalTrip();
        assertThat(records.findByUserIdAndRequestId(userId, "req-A").orElseThrow().getId())
                .isEqualTo(original.getId());
    }

    @Test
    void 업무_트랜잭션_밖에서는_결과를_저장하지_않는다() {
        assertThatThrownBy(() -> save("req-A")).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(records.findByUserIdAndRequestId(userId, "req-A")).isEmpty();
    }

    @Test
    void 사용자와_요청번호_대소문자를_구분한다() {
        transactions.executeWithoutResult(status -> {
            save("req-A");
            save("req-a");
        });
        assertThat(records.findByUserIdAndRequestId(userId, "req-A").orElseThrow().getId())
                .isNotEqualTo(records.findByUserIdAndRequestId(userId, "req-a").orElseThrow().getId());
        assertThat(service.findResult(userId + 1, "req-A", tripId, CommandType.UPDATE_TRIP, HASH, COMPLETED))
                .isEmpty();
    }

    @Test
    void 물리_정리_전에도_정확히_24시간부터_재확인을_차단한다() {
        transactions.executeWithoutResult(status -> save("req-A"));
        LocalDateTime expiresAt = COMPLETED.plusHours(24);
        assertThat(service.findResult(userId, "req-A", tripId, CommandType.UPDATE_TRIP, HASH,
                expiresAt.minusNanos(1_000))).isPresent();
        assertThatThrownBy(() -> service.findResult(userId, "req-A", tripId, CommandType.UPDATE_TRIP, HASH, expiresAt))
                .isInstanceOf(TripEditCommandResultExpiredException.class);
        assertThat(records.findByUserIdAndRequestId(userId, "req-A").orElseThrow().getResultJson()).isNotNull();
    }

    private void changeTrip() {
        Trip trip = trips.findById(tripId).orElseThrow();
        trip.changeInformation("바뀐여행", trip.getStartDate(), trip.getEndDate());
    }

    private void assertOriginalTrip() {
        assertThat(trips.findById(tripId).orElseThrow().getTripName()).isEqualTo("원래여행");
    }

    private void save(String requestId) {
        service.saveResult(userId, tripId, requestId, CommandType.UPDATE_TRIP, HASH, result(requestId), COMPLETED);
    }

    private ApiResponse<TripEditCommandResultResponse> result(String requestId) {
        return new ApiResponse<>("TRIP_UPDATED", new TripEditCommandResultResponse(requestId, tripId, "SUCCEEDED", 1L));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
