package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripShareCreateForbiddenException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.TripShareCreateRequest;
import com.yeodam.yeodambe.user.service.ShareUserSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {"app.trip.edit-lock.enabled=true", "app.trip.edit-lock.wait-time=100ms"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripShareMutationIntegrationTest {
    @Autowired
    private ShareTripService sharing;
    @Autowired
    private TripEditAuthorizationService authorization;
    @Autowired
    private TripShareUserRepository shares;
    @Autowired
    private TripRepository trips;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private TripEditLockProperties properties;
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient redis;
    @MockitoSpyBean
    private ShareUserSearchService grantees;

    private Long tripId;
    private Long ownerId;
    private Long actorId;
    private Long foreignId;

    @BeforeEach
    void prepareTrip() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        ownerId = fixture.createOwner();
        actorId = fixture.createOwner();
        foreignId = fixture.createOwner();
        tripId = fixture.createTrip(ownerId);
    }

    @Test
    void 공유_등록은_여행_락을_저장과_커밋까지_유지한다() {
        AtomicInteger commits = new AtomicInteger();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(lock().isHeldByCurrentThread()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    assertThat(lock().isHeldByCurrentThread()).isTrue();
                    commits.incrementAndGet();
                }
            });
            return call.callRealMethod();
        }).when(grantees).requireActiveUser(actorId);

        sharing.register(tripId, ownerId, new TripShareCreateRequest(actorId, TripSharePermission.WRITE));

        assertThat(commits).hasValue(1);
        assertThat(lock().isHeldByCurrentThread()).isFalse();
        assertThat(shares.findByTripIdAndGranteeUserId(tripId, actorId).orElseThrow().getPermission())
                .isEqualTo(TripSharePermission.WRITE);
    }

    @Test
    void 소유자도_여행_락이_경합하면_공유를_등록하지_않는다() {
        assertRejectedWhileLocked(ownerId, TripEditLockRejectedException.class);
    }

    @Test
    void 타인의_공유_등록은_락_경합보다_기존_접근_거절을_먼저_반환한다() {
        assertRejectedWhileLocked(foreignId, TripShareCreateForbiddenException.class);
    }

    @ParameterizedTest
    @EnumSource(GrantChange.class)
    void 영속성_컨텍스트에_WRITE가_남아도_다른_트랜잭션의_권한_변경을_다시_확인한다(GrantChange change) {
        LocalDateTime now = LocalDateTime.now();
        shares.saveAndFlush(new TripShareUser(
                tripId, ownerId, actorId, TripSharePermission.WRITE, now.plusDays(1), now
        ));
        Trip trip = trips.findById(tripId).orElseThrow();
        TransactionTemplate reading = transaction();
        reading.executeWithoutResult(status -> {
            TripShareUser cached = shares.findByTripIdAndGranteeUserId(tripId, actorId).orElseThrow();
            authorization.requireWritePermission(trip, actorId);
            TransactionTemplate changing = transaction();
            changing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            changing.executeWithoutResult(ignored -> {
                if (change == GrantChange.REVOKE) {
                    jdbc.update("UPDATE trip_share_users SET deleted_at = ? WHERE trip_id = ? AND grantee_user_id = ?",
                            LocalDateTime.now(), tripId, actorId);
                } else {
                    jdbc.update("UPDATE trip_share_users SET permission = 'READ' WHERE trip_id = ? AND grantee_user_id = ?",
                            tripId, actorId);
                }
            });

            assertThat(cached.getPermission()).isEqualTo(TripSharePermission.WRITE);
            assertThat(cached.getDeletedAt()).isNull();
            Class<? extends RuntimeException> expected = change == GrantChange.REVOKE
                    ? TripNotFoundException.class : WritePermissionRequiredException.class;
            assertThatThrownBy(() -> authorization.requireWritePermission(trip, actorId))
                    .isInstanceOf(expected);
            status.setRollbackOnly();
        });
    }

    private void assertRejectedWhileLocked(Long requesterId, Class<? extends RuntimeException> expected) {
        RLock lock = lock();
        lock.lock();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> response = executor.submit(() -> sharing.register(
                    tripId, requesterId, new TripShareCreateRequest(actorId, TripSharePermission.WRITE)
            ));
            assertThatThrownBy(() -> response.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(expected);
        } finally {
            lock.unlock();
        }
        assertThat(shares.findByTripIdAndGranteeUserId(tripId, actorId)).isEmpty();
        verifyNoInteractions(grantees);
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private RLock lock() {
        return redis.getLock(properties.namespace() + ":lock:trip:mutation:" + tripId);
    }

    private enum GrantChange {
        REVOKE, DOWNGRADE
    }
}
