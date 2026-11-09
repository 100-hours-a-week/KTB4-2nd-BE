package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.trip.edit-lock.enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripMutationLockIntegrationTest {
    @Autowired
    @Qualifier("tripEditRedissonClient")
    private RedissonClient first;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private RedissonClient second;
    private TripEditLockProperties properties;
    private SimpleMeterRegistry meters;
    private ExecutorService executor;

    @BeforeEach
    void prepareClients() {
        second = Redisson.create(new Config(first.getConfig()));
        properties = new TripEditLockProperties(true, "test:" + UUID.randomUUID(),
                Duration.ofMillis(300), Duration.ofSeconds(30));
        meters = new SimpleMeterRegistry();
        executor = Executors.newFixedThreadPool(2);
        jdbc.execute("CREATE TABLE IF NOT EXISTS mutation_lock_probe (id INT PRIMARY KEY, value INT NOT NULL)");
        jdbc.update("INSERT INTO mutation_lock_probe VALUES (1, 0) ON DUPLICATE KEY UPDATE value = 0");
    }

    @AfterEach
    void closeClients() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        second.shutdown();
        meters.close();
    }

    @Test
    void 커밋과_롤백이_끝난_뒤_락을_해제한다() {
        List<Integer> completions = new ArrayList<>();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        TripEditLock lock = lock(first);
        lock.executeAll(List.of(2L, 1L), () -> transactions.execute(status -> {
            jdbc.update("UPDATE mutation_lock_probe SET value = 1 WHERE id = 1");
            recordCompletionWhileLocked(completions);
            return null;
        }));
        assertThat(jdbc.queryForObject("SELECT value FROM mutation_lock_probe WHERE id = 1", Integer.class))
                .isEqualTo(1);
        lock.executeAll(List.of(1L, 2L), () -> transactions.execute(status -> {
            jdbc.update("UPDATE mutation_lock_probe SET value = 2 WHERE id = 1");
            recordCompletionWhileLocked(completions);
            status.setRollbackOnly();
            return null;
        }));
        assertThat(completions).containsExactly(TransactionSynchronization.STATUS_COMMITTED,
                TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(jdbc.queryForObject("SELECT value FROM mutation_lock_probe WHERE id = 1", Integer.class))
                .isEqualTo(1);
        assertThat(first.getLock(key(1L)).isLocked()).isFalse();
        assertThat(first.getLock(key(2L)).isLocked()).isFalse();
    }

    @Test
    void 두_클라이언트의_같은_여행_변경을_직렬화한다() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> owner = executor.submit(() -> lock(first).executeAll(List.of(2L, 1L, 1L), () -> {
            acquired.countDown();
            await(release);
            return 1;
        }));
        try {
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> lock(second).executeAll(List.of(1L, 2L), () -> {
                jdbc.update("UPDATE mutation_lock_probe SET value = 99 WHERE id = 1");
                return 99;
            })).isInstanceOfSatisfying(TripEditLockRejectedException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(TripEditLockRejectedException.Reason.CONTENDED));
            assertThat(jdbc.queryForObject("SELECT value FROM mutation_lock_probe WHERE id = 1", Integer.class))
                    .isZero();
        } finally {
            release.countDown();
        }
        assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(lock(second).executeAll(List.of(1L, 2L), () -> 2)).isEqualTo(2);
    }

    @Test
    void 일부_락_획득_후_실패하면_먼저_얻은_락도_해제한다() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> owner = executor.submit(() -> {
            RLock held = second.getLock(key(2L));
            held.lock();
            try {
                acquired.countDown();
                await(release);
            } finally {
                held.unlock();
            }
        });
        try {
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> lock(first).executeAll(List.of(2L, 1L), () -> 1))
                    .isInstanceOf(TripEditLockRejectedException.class);
            assertThat(first.getLock(key(1L)).isLocked()).isFalse();
        } finally {
            release.countDown();
        }
        owner.get(5, TimeUnit.SECONDS);
    }

    @Test
    void 외부_호출과_대기_중에는_트랜잭션과_여행_락을_보유하지_않는다() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        lock(first).executeAll(List.of(1L), () -> transactions.execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(first.getLock(key(1L)).isLocked()).isFalse();
        assertThat(lock(second).execute(1L, () -> "external-stage")).isEqualTo("external-stage");
        transactions.executeWithoutResult(status -> assertThatThrownBy(
                () -> lock(first).executeAll(List.of(1L), () -> 1)).isInstanceOf(IllegalStateException.class));
    }

    @Test
    void Redis_장애에는_새_변경을_저장하지_않는다() {
        second.shutdown();
        assertThatThrownBy(() -> lock(second).executeAll(List.of(1L, 2L), () -> {
            jdbc.update("UPDATE mutation_lock_probe SET value = 99 WHERE id = 1");
            return 99;
        })).isInstanceOf(TripEditLockRejectedException.class);
        assertThat(jdbc.queryForObject("SELECT value FROM mutation_lock_probe WHERE id = 1", Integer.class))
                .isZero();
    }

    private void recordCompletionWhileLocked(List<Integer> completions) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                assertThat(first.getLock(key(1L)).isHeldByCurrentThread()).isTrue();
                assertThat(first.getLock(key(2L)).isHeldByCurrentThread()).isTrue();
                completions.add(status);
            }
        });
    }

    private TripEditLock lock(RedissonClient client) {
        return new TripEditLock(client, properties, meters);
    }

    private String key(Long tripId) {
        return properties.namespace() + ":lock:trip:mutation:" + tripId;
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(TripEditLockTestErrorMessage.INTERRUPTED.message(), failure);
        }
    }
}
