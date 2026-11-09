package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.TripAnalysisExecutionService;
import com.yeodam.yeodambe.trip.service.TripEditLock;
import com.yeodam.yeodambe.trip.service.TripWithdrawalService;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WithdrawalLockBoundaryIntegrationTest {
    @Autowired
    private WithdrawalService withdrawal;
    @Autowired
    private UserRegistrationService registration;
    @Autowired
    private TripRepository trips;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TripEditLockProperties lockProperties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private TripEditLock editLock;
    @MockitoSpyBean
    private TripWithdrawalService tripWithdrawal;
    @MockitoSpyBean
    private CsrfTokenStore csrf;

    @Test
    void 여행_목록이_바뀌면_락을_다시_모으고_탈퇴_커밋_후_락과_인증을_정리한다() {
        String unique = UUID.randomUUID().toString();
        User owner = registration.register(unique + "@yeodam.test", "탈퇴경계", OAuthProvider.KAKAO, unique);
        Long userId = owner.getUserId();
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        Long firstTrip = fixture.createTrip(userId);
        fixture.prepareAddition(firstTrip, userId);
        String analysisId = scheduling.startNext(firstTrip).orElseThrow();
        AtomicBoolean changed = new AtomicBoolean();
        AtomicBoolean committedWithLock = new AtomicBoolean();
        AtomicReference<Long> laterTrip = new AtomicReference<>();
        AtomicReference<List<Long>> finalLockIds = new AtomicReference<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            List<Long> ids = invocation.getArgument(0);
            if (changed.compareAndSet(false, true)) {
                laterTrip.set(trips.saveAndFlush(new Trip(userId, "뒤늦은 여행", LocalDate.now(), LocalDate.now())).getId());
            } else {
                finalLockIds.set(ids);
            }
            return invocation.callRealMethod();
        }).when(editLock).executeAll(any(), any());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    assertThat(redis.getLock(key(firstTrip)).isHeldByCurrentThread()).isTrue();
                    Boolean deleted = CompletableFuture.supplyAsync(() -> jdbc.queryForObject(
                            "SELECT deleted_at IS NOT NULL FROM users WHERE user_id = ?", Boolean.class, userId)).orTimeout(10, TimeUnit.SECONDS).join();
                    assertThat(deleted).isTrue();
                    committedWithLock.set(true);
                }
            });
            return result;
        }).when(tripWithdrawal).withdrawAll(any(), any());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(redis.getLock(key(firstTrip)).isHeldByCurrentThread()).isFalse();
            return invocation.callRealMethod();
        }).when(csrf).delete("withdrawal-" + unique);

        withdrawal.withdraw(userId, "withdrawal-" + unique);

        assertThat(committedWithLock).isTrue();
        assertThat(finalLockIds.get()).containsExactly(firstTrip, laterTrip.get());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trips WHERE user_id = ? AND deleted_at IS NULL", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM trip_analysis_executions WHERE execution_id = ?", String.class, analysisId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT storage_reserved_bytes FROM user_stats WHERE user_id = ?", Long.class, userId)).isZero();
    }

    private String key(Long tripId) {
        return lockProperties.namespace() + ":lock:trip:mutation:" + tripId;
    }
}
