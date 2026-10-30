package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.websocket.config.WebSocketEventProperties;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEvent;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTarget;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventTargetType;
import com.yeodam.yeodambe.common.websocket.service.request.WebSocketEventType;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox;
import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox.Status;
import com.yeodam.yeodambe.trip.repository.TripEditNotificationOutboxRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TripEditNotificationOutboxService.class, TripEditNotificationPublisher.class,
        TripEditNotificationPersistenceService.class, WebSocketEventPublisher.class,
        TripEditNotificationOutboxPersistenceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripEditNotificationOutboxPersistenceTest {

    private static final Instant START = Instant.parse("2026-10-30T01:02:03Z");
    private static final String CHANNEL = "test:trip-edit:events";

    @Autowired private TripEditNotificationOutboxService outbox;
    @Autowired private TripEditNotificationPublisher publisher;
    @MockitoSpyBean private TripEditNotificationOutboxRepository records;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private Clock clock;
    @Autowired private StringRedisTemplate redis;
    @Autowired @Qualifier("tripEditNotificationExecutor") private Executor executor;
    @MockitoSpyBean private TripEditNotificationPersistenceService persistence;
    private TransactionTemplate transactions;
    private List<Runnable> submitted;

    @BeforeEach
    void setup() {
        transactions = new TransactionTemplate(transactionManager);
        records.deleteAll();
        reset(clock, redis, executor);
        when(clock.instant()).thenReturn(START);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        submitted = new ArrayList<>();
        doAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));
    }

    @Test
    void 업무_트랜잭션이_있어야_한다() {
        assertThatThrownBy(() -> outbox.enqueue(event(Map.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(records.count()).isZero();
    }

    @Test
    void 롤백하면_업무_변경과_Outbox를_제거하고_발행을_접수하지_않는다() {
        Long tripId = tripFixture();
        transactions.executeWithoutResult(status -> {
            changeTrip(tripId);
            outbox.enqueue(event(Map.of("tripId", tripId)));
            assertThat(records.count()).isEqualTo(1);
            verifyNoInteractions(redis, executor);
            status.setRollbackOnly();
        });
        assertThat(trips.findById(tripId).orElseThrow().getTripName()).isEqualTo("원래 여행");
        assertThat(records.count()).isZero();
        verifyNoInteractions(redis, executor);
    }

    @Test
    void 커밋하면_트랜잭션_밖에서_비동기_발행하고_구독자_0명도_성공이다() {
        Long tripId = tripFixture();
        WebSocketEvent event = event(Map.of("tripId", tripId));
        transactions.executeWithoutResult(status -> {
            changeTrip(tripId);
            outbox.enqueue(event);
            verifyNoInteractions(redis, executor);
        });
        assertThat(trips.findById(tripId).orElseThrow().getTripName()).isEqualTo("변경 여행");
        TripEditNotificationOutbox pending = onlyRecord();
        assertThat(pending.getEventId()).isEqualTo(event.eventId().toString());
        assertThat(pending.getStatus()).isEqualTo(Status.PENDING);
        assertThat(pending.getAttempts()).isZero();
        assertThat(pending.getNextAttemptAt()).isEqualTo(utc(START));
        assertThat(submitted).hasSize(1);
        Thread caller = Thread.currentThread();
        when(redis.convertAndSend(eq(CHANNEL), anyString())).thenAnswer(invocation -> {
            assertThat(Thread.currentThread()).isNotSameAs(caller);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
            return 0L;
        });
        CompletableFuture.runAsync(submitted.getFirst()).join();
        TripEditNotificationOutbox published = onlyRecord();
        assertThat(published.getStatus()).isEqualTo(Status.PUBLISHED);
        assertThat(published.getAttempts()).isEqualTo(1);
        assertThat(published.getPublishedAt()).isEqualTo(utc(START));
        publisher.retryPending();
        publisher.publish(published.getId());
        verify(redis, times(1)).convertAndSend(CHANNEL, pending.getEventJson());
    }

    @Test
    void 실행기가_거절하면_커밋된_작업을_복구_대기로_남긴다() {
        Long tripId = tripFixture();
        doThrow(new RejectedExecutionException("test saturation")).when(executor).execute(any());
        transactions.executeWithoutResult(status -> {
            changeTrip(tripId);
            outbox.enqueue(event(Map.of()));
        });
        assertThat(trips.findById(tripId).orElseThrow().getTripName()).isEqualTo("변경 여행");
        assertThat(onlyRecord().getStatus()).isEqualTo(Status.PENDING);
        publisher.retryPending();
        assertThat(onlyRecord().getStatus()).isEqualTo(Status.PUBLISHED);
    }

    @Test
    void 실패_시각으로_재시도하고_최대_대기_간격은_30초다() {
        enqueue(event(Map.of()));
        doThrow(new RedisConnectionFailureException("test redis failure"))
                .when(redis).convertAndSend(eq(CHANNEL), anyString());
        long[] delays = {1, 2, 4, 8, 16, 30, 30, 30};
        Instant attemptAt = START;
        for (int index = 0; index < delays.length; index++) {
            Instant failureAt = attemptAt.plusSeconds(5);
            when(clock.instant()).thenReturn(attemptAt, failureAt);
            publisher.retryPending();
            TripEditNotificationOutbox pending = onlyRecord();
            assertThat(pending.getStatus()).isEqualTo(Status.PENDING);
            assertThat(pending.getAttempts()).isEqualTo(index + 1);
            assertThat(pending.getNextAttemptAt()).isEqualTo(utc(failureAt.plusSeconds(delays[index])));
            assertThat(pending.getPublishedAt()).isNull();
            attemptAt = failureAt.plusSeconds(delays[index]);
            when(clock.instant()).thenReturn(attemptAt.minusNanos(1_000));
            publisher.retryPending();
            verify(redis, times(index + 1)).convertAndSend(eq(CHANNEL), anyString());
        }
    }

    @Test
    void 호출자가_payload를_바꿔도_동일_JSON과_이벤트_ID로_재시도한다() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "동결 값");
        WebSocketEvent event = event(data);
        enqueue(event);
        String json = onlyRecord().getEventJson();
        data.put("name", "바뀐 값");
        doThrow(new RedisConnectionFailureException("test timeout"))
                .doReturn(2L).when(redis).convertAndSend(eq(CHANNEL), anyString());
        submitted.getFirst().run();
        when(clock.instant()).thenReturn(START.plusSeconds(1));
        publisher.retryPending();
        verify(redis, times(2)).convertAndSend(CHANNEL, json);
        assertThat(json).contains(event.eventId().toString(), "동결 값").doesNotContain("바뀐 값");
        assertThat(onlyRecord().getEventJson()).isEqualTo(json);
    }

    @Test
    void 성공_기록이_실패하면_이벤트_변경_없이_중복_발행할_수_있다() {
        enqueue(event(Map.of()));
        TripEditNotificationOutbox record = onlyRecord();
        doThrow(new IllegalStateException("test write failure"))
                .doCallRealMethod().when(persistence).markPublished(eq(record.getId()), any());
        submitted.getFirst().run();
        assertThat(onlyRecord().getStatus()).isEqualTo(Status.PENDING);
        publisher.retryPending();
        assertThat(onlyRecord().getStatus()).isEqualTo(Status.PUBLISHED);
        verify(redis, times(2)).convertAndSend(CHANNEL, record.getEventJson());
    }

    @Test
    void 실패_기록이_실패해도_원래_대기_행은_복구할_수_있다() {
        enqueue(event(Map.of()));
        doThrow(new RedisConnectionFailureException("test timeout"))
                .doReturn(0L).when(redis).convertAndSend(eq(CHANNEL), anyString());
        doThrow(new IllegalStateException("test write failure"))
                .when(persistence).markFailed(any(TripEditNotificationOutbox.class), any());
        submitted.getFirst().run();
        assertThat(onlyRecord().getStatus()).isEqualTo(Status.PENDING);
        assertThat(onlyRecord().getAttempts()).isZero();
        publisher.retryPending();
        assertThat(onlyRecord().getStatus()).isEqualTo(Status.PUBLISHED);
    }

    @Test
    void 미시도_행은_24시간이_지나도_복구하고_한_번에_최대_50개를_조회한다() {
        transactions.executeWithoutResult(status -> IntStream.range(0, 51)
                .forEach(index -> outbox.enqueue(event(Map.of("index", index)))));
        when(clock.instant()).thenReturn(START.plusSeconds(48 * 60 * 60));
        publisher.retryPending();
        assertThat(records.findAll()).filteredOn(row -> row.getStatus() == Status.PUBLISHED).hasSize(50);
        assertThat(records.findAll()).filteredOn(row -> row.getStatus() == Status.PENDING).hasSize(1);
        publisher.retryPending();
        assertThat(records.findAll()).allMatch(row -> row.getStatus() == Status.PUBLISHED);
        verify(redis, times(51)).convertAndSend(eq(CHANNEL), anyString());
    }

    @Test
    void 잘못되거나_크기가_초과된_이벤트는_업무_변경을_롤백한다() {
        Long tripId = tripFixture();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            changeTrip(tripId);
            outbox.enqueue(event(Map.of("text", "가".repeat(22_000))));
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(trips.findById(tripId).orElseThrow().getTripName()).isEqualTo("원래 여행");
        assertThat(records.count()).isZero();
        verifyNoInteractions(redis, executor);
    }

    @Test
    void 저장_실패는_업무_트랜잭션을_롤백한다() {
        Long tripId = tripFixture();
        doThrow(new DataAccessResourceFailureException("test database failure"))
                .when(records).saveAndFlush(any(TripEditNotificationOutbox.class));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            changeTrip(tripId);
            outbox.enqueue(event(Map.of()));
        })).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(trips.findById(tripId).orElseThrow().getTripName()).isEqualTo("원래 여행");
        assertThat(records.count()).isZero();
        verifyNoInteractions(redis, executor);
    }

    @Test
    void 같은_논리_이벤트_ID를_다른_Outbox_행에서_재발행할_수_있다() {
        WebSocketEvent event = event(Map.of());
        enqueue(event);
        enqueue(event);
        List<TripEditNotificationOutbox> rows = records.findAll();
        assertThat(rows).hasSize(2).extracting(TripEditNotificationOutbox::getEventId)
                .containsOnly(event.eventId().toString());
        assertThat(rows.getFirst().getId()).isNotEqualTo(rows.getLast().getId());
        assertThat(rows.getFirst().getEventJson()).isEqualTo(rows.getLast().getEventJson());
    }

    @Test
    void 늦은_실패로_발행_완료_행을_대기로_되돌리지_않는다() {
        enqueue(event(Map.of()));
        TripEditNotificationOutbox pending = onlyRecord();
        submitted.getFirst().run();
        persistence.markFailed(pending, utc(START.plusSeconds(10)));
        persistence.markPublished(pending.getId(), utc(START.plusSeconds(20)));
        assertThat(onlyRecord().getStatus()).isEqualTo(Status.PUBLISHED);
        assertThat(onlyRecord().getAttempts()).isEqualTo(1);
        assertThat(onlyRecord().getPublishedAt()).isEqualTo(utc(START));
    }

    @Test
    void 일괄_조회가_실패하면_다음_복구를_위해_행을_유지한다() {
        enqueue(event(Map.of()));
        doThrow(new DataAccessResourceFailureException("test database failure"))
                .doCallRealMethod().when(persistence).findDueIds(any());
        publisher.retryPending();
        assertThat(onlyRecord().getStatus()).isEqualTo(Status.PENDING);
        verifyNoInteractions(redis);
        publisher.retryPending();
        assertThat(onlyRecord().getStatus()).isEqualTo(Status.PUBLISHED);
    }

    private void enqueue(WebSocketEvent event) {
        transactions.executeWithoutResult(status -> outbox.enqueue(event));
    }

    private TripEditNotificationOutbox onlyRecord() {
        return records.findAll().getFirst();
    }

    private Long tripFixture() {
        Long userId = users.save(new User(UUID.randomUUID() + "@outbox.test", "회원")).getUserId();
        return trips.saveAndFlush(new Trip(userId, "원래 여행", LocalDate.now(), LocalDate.now())).getId();
    }

    private void changeTrip(Long tripId) {
        Trip trip = trips.findById(tripId).orElseThrow();
        trip.changeInformation("변경 여행", trip.getStartDate(), trip.getEndDate());
    }

    private WebSocketEvent event(Object data) {
        return new WebSocketEvent(UUID.randomUUID(), WebSocketEventType.TRIP_EVENT, 1, START,
                new WebSocketEventTarget(WebSocketEventTargetType.TRIP, 42L),
                new ApiResponse<>("TRIP_UPDATED", data));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        TaskScheduler taskScheduler() {
            return mock(TaskScheduler.class);
        }

        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return mock(Clock.class);
        }

        @Bean
        StringRedisTemplate redis() {
            return mock(StringRedisTemplate.class);
        }

        @Bean("tripEditNotificationExecutor")
        Executor executor() {
            return mock(Executor.class);
        }

        @Bean
        WebSocketEventProperties properties() {
            return new WebSocketEventProperties(CHANNEL);
        }
    }
}
