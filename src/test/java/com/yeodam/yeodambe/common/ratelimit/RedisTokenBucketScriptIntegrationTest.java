package com.yeodam.yeodambe.common.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisTokenBucketScriptIntegrationTest {

    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private static RedisTokenBucketRateLimiter rateLimiter;
    private static final RedisScript<Long> SCRIPT = RedisScript.of(
            new ClassPathResource("redis/request-rate-limit.lua"), Long.class);

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        rateLimiter = new RedisTokenBucketRateLimiter(
                redis,
                new RequestRateLimitProperties(
                        true,
                        "yeodam:test",
                        Duration.ofSeconds(60),
                        new RequestRateLimitProperties.Bucket(5, 30),
                        new RequestRateLimitProperties.Bucket(1, 2)
                )
        );
    }

    @AfterAll
    static void disconnect() {
        if (connections != null) {
            connections.destroy();
        }
    }

    @BeforeEach
    void clearRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void 버킷은_최대_용량으로_시작하고_초과하면_다음_요청까지_대기초를_반환한다() throws InterruptedException {
        String key = "yeodam:test:trip-read:42";

        assertThat(consume(key, 2, 1)).isEqualTo(1L);
        assertThat(consume(key, 2, 1)).isEqualTo(1L);
        assertThat(consume(key, 2, 1)).isEqualTo(-1L);

        Thread.sleep(1_100L);

        assertThat(consume(key, 2, 1)).isEqualTo(1L);
    }

    @Test
    void 제한_그룹과_사용자가_다르면_별도_버킷을_사용한다() {
        assertThat(consume("yeodam:test:trip-read:42", 1, 1)).isEqualTo(1L);
        assertThat(consume("yeodam:test:trip-read:42", 1, 1)).isEqualTo(-1L);

        assertThat(consume("yeodam:test:trip-create:42", 1, 1)).isEqualTo(1L);
        assertThat(consume("yeodam:test:trip-read:43", 1, 1)).isEqualTo(1L);
    }

    @Test
    void 여러_호출이_동시에_와도_최대_용량만_허용한다() {
        String key = "yeodam:test:trip-create:" + UUID.randomUUID();
        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            List<CompletableFuture<Long>> results = IntStream.range(0, 48)
                    .mapToObj(ignored -> CompletableFuture.supplyAsync(
                            () -> consume(key, 10, 1), executor))
                    .toList();

            long accepted = results.stream()
                    .map(CompletableFuture::join)
                    .filter(result -> result == 1L)
                    .count();

            assertThat(accepted).isEqualTo(10L);
        }
    }

    @Test
    void Java_요청_제한기는_그룹별_설정과_사용자별_키를_적용한다() {
        assertThat(rateLimiter.tryAcquire(RequestRateLimitGroup.TRIP_CREATE, 42L)).isZero();
        assertThat(rateLimiter.tryAcquire(RequestRateLimitGroup.TRIP_CREATE, 42L)).isZero();
        assertThat(rateLimiter.tryAcquire(RequestRateLimitGroup.TRIP_CREATE, 42L)).isEqualTo(1L);

        assertThat(rateLimiter.tryAcquire(RequestRateLimitGroup.TRIP_CREATE, 43L)).isZero();
        assertThat(rateLimiter.tryAcquire(RequestRateLimitGroup.TRIP_READ, 42L)).isZero();
    }

    private static Long consume(String key, int capacity, int refillPerSecond) {
        return redis.execute(SCRIPT, List.of(key),
                Integer.toString(capacity), Integer.toString(refillPerSecond), "60000");
    }
}
