package com.yeodam.yeodambe;

import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.service.TripEditLock;
import com.yeodam.yeodambe.trip.service.TripEditLockTestErrorMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.redisson.client.DefaultNettyHook;
import org.redisson.config.Config;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripEditLockRedisTest {
    private static RedissonClient first;
    private static RedissonClient second;

    @BeforeAll
    static void startClients() {
        DataRedisConnectionDetails.Standalone connection = SharedTestRedis.createDatabase().getStandalone();
        first = client(connection.getHost(), connection.getPort(), connection.getDatabase());
        second = client(connection.getHost(), connection.getPort(), connection.getDatabase());
    }

    @AfterAll
    static void stopClients() {
        if (first != null) {
            first.shutdown();
        }
        if (second != null) {
            second.shutdown();
        }
    }

    @Test
    void 독립_클라이언트는_같은_여행에서만_경합한다() throws Exception {
        String namespace = UUID.randomUUID().toString();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> owner = executor.submit(() -> lock(first, namespace).execute(7L, () -> {
                acquired.countDown();
                await(release);
                return "owner";
            }));
            try {
                assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> lock(second, namespace).execute(7L, () -> "wrong"))
                        .isInstanceOf(TripEditLockRejectedException.class);
                assertThat(lock(second, namespace).execute(8L, () -> "other")).isEqualTo("other");
            } finally {
                release.countDown();
            }
            assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo("owner");
            assertThat(lock(second, namespace).execute(7L, () -> "next")).isEqualTo("next");
        }
    }

    @Test
    void 워치독은_최초_만료_시간_이후에도_락_소유권을_유지한다() throws Exception {
        String namespace = UUID.randomUUID().toString();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> owner = executor.submit(() -> lock(first, namespace).execute(7L, () -> {
                acquired.countDown();
                await(release);
                return "held";
            }));
            try {
                assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(release.await(1400, TimeUnit.MILLISECONDS)).isFalse();
                assertThatThrownBy(() -> lock(second, namespace).execute(7L, () -> "expired"))
                        .isInstanceOf(TripEditLockRejectedException.class);
            } finally {
                release.countDown();
            }
            assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo("held");
        }
    }

    @Test
    void 락은_커밋과_롤백_콜백이_끝난_뒤_해제된다() {
        String namespace = UUID.randomUUID().toString();
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + namespace + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE lock_probe (id INT PRIMARY KEY)");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TripEditLock guard = lock(first, namespace);
        String key = namespace + ":lock:trip:mutation:7";

        guard.execute(7L, () -> transaction.execute(status -> {
            jdbc.update("INSERT INTO lock_probe VALUES (1)");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completion) {
                    assertThat(second.getLock(key).isLocked()).isTrue();
                }
            });
            return "committed";
        }));
        assertThat(first.getLock(key).isLocked()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lock_probe", Integer.class)).isEqualTo(1);

        assertThatThrownBy(() -> guard.execute(7L, () -> transaction.execute(status -> {
            jdbc.update("INSERT INTO lock_probe VALUES (2)");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completion) {
                    assertThat(completion).isEqualTo(STATUS_ROLLED_BACK);
                    assertThat(second.getLock(key).isLocked()).isTrue();
                }
            });
            throw new IllegalStateException(TripEditLockTestErrorMessage.ROLLBACK.message());
        }))).isInstanceOf(IllegalStateException.class).hasMessage("rollback");
        assertThat(first.getLock(key).isLocked()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lock_probe", Integer.class)).isEqualTo(1);
    }

    @Test
    void 소유권이_넘어간_뒤_해제해도_새_소유자의_락을_유지한다() throws Exception {
        String namespace = UUID.randomUUID().toString();
        String key = namespace + ":lock:trip:mutation:7";
        RLock nextOwner = second.getLock(key);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TripEditLock guard = new TripEditLock(first,
                new TripEditLockProperties(true, namespace, Duration.ofMillis(100), Duration.ofMillis(600)),
                meters);
        try {
            assertThat(guard.execute(7L, () -> {
                assertThat(first.getKeys().expire(Duration.ofMillis(1), key)).isEqualTo(1);
                try {
                    assertThat(nextOwner.tryLock(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return "committed";
            })).isEqualTo("committed");
            assertThat(nextOwner.isHeldByCurrentThread()).isTrue();
            assertThat(meters.get("yeodam.trip.edit.lock.release")
                    .tag("outcome", "ownership_lost").counter().count()).isEqualTo(1);
        } finally {
            if (nextOwner.isHeldByCurrentThread()) {
                nextOwner.unlock();
            }
            meters.close();
        }
    }

    @Test
    void 해제_통신_장애가_복구되면_종료된_작업의_락을_계속_갱신하지_않는다() throws Exception {
        DataRedisConnectionDetails.Standalone connection = SharedTestRedis.createDatabase().getStandalone();
        AtomicBoolean failWrites = new AtomicBoolean();
        AtomicInteger failedWrites = new AtomicInteger();
        RedissonClient owner = failingClient(connection, failWrites, failedWrites);
        RedissonClient contender = client(connection.getHost(), connection.getPort(), connection.getDatabase());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        String namespace = UUID.randomUUID().toString();
        String key = namespace + ":lock:trip:mutation:7";
        TripEditLock guard = new TripEditLock(owner,
                new TripEditLockProperties(true, namespace, Duration.ofMillis(100), Duration.ofSeconds(3)),
                meters);
        try {
            assertThat(guard.execute(7L, () -> {
                failWrites.set(true);
                return "committed";
            })).isEqualTo("committed");
            failWrites.set(false);
            assertThat(failedWrites.get()).isPositive();
            assertThat(meters.get("yeodam.trip.edit.lock.release")
                    .tag("outcome", "failed").counter().count()).isEqualTo(1);
            // 송신 전에 실패시켰으므로 서버의 기존 락이 남아 있어야 갱신 취소를 검증할 수 있다.
            assertThat(contender.getLock(key).isLocked()).isTrue();
            assertThat(contender.getLock(key).tryLock(7, TimeUnit.SECONDS)).isTrue();
            contender.getLock(key).unlock();
        } finally {
            failWrites.set(false);
            owner.shutdown();
            contender.shutdown();
            meters.close();
        }
    }

    private static RedissonClient failingClient(
            DataRedisConnectionDetails.Standalone connection,
            AtomicBoolean failWrites,
            AtomicInteger failedWrites
    ) {
        Config config = clientConfig(connection.getHost(), connection.getPort(), connection.getDatabase());
        config.setLockWatchdogTimeout(3000);
        config.useSingleServer().setTimeout(200).setRetryAttempts(0);
        config.setNettyHook(new DefaultNettyHook() {
            @Override
            public void afterChannelInitialization(Channel channel) {
                channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                        if (failWrites.get()) {
                            failedWrites.incrementAndGet();
                            ReferenceCountUtil.release(message);
                            promise.setFailure(new IOException(TripEditLockTestErrorMessage.TRANSPORT_WRITE_FAILED.message()));
                            return;
                        }
                        context.write(message, promise);
                    }
                });
            }
        });
        return Redisson.create(config);
    }

    private static TripEditLock lock(RedissonClient client, String namespace) {
        return new TripEditLock(client,
                new TripEditLockProperties(true, namespace, Duration.ofMillis(100), Duration.ofMillis(600)),
                new SimpleMeterRegistry());
    }

    private static RedissonClient client(String host, int port, int database) {
        return Redisson.create(clientConfig(host, port, database));
    }

    private static Config clientConfig(String host, int port, int database) {
        Config config = new Config();
        config.setThreads(2);
        config.setNettyThreads(2);
        config.setLockWatchdogTimeout(600);
        config.useSingleServer().setAddress("redis://" + host + ":" + port).setDatabase(database)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(4);
        return config;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(TripEditLockTestErrorMessage.OWNER_RELEASE_TIMED_OUT.message());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
