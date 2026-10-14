package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.service.response.TripMapCacheData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TripMapCacheRedisTest {
    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connections = new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connections != null) {
            connections.destroy();
        }
    }

    @Test
    void concurrentVersionInitializationReturnsOneSharedVersion() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var store = store();
            var tasks = IntStream.range(0, 8)
                    .mapToObj(i -> (java.util.concurrent.Callable<String>) () -> store.version(1L).orElseThrow())
                    .toList();
            var futures = pool.invokeAll(tasks);
            var versions = new java.util.HashSet<String>();
            for (var future : futures) {
                versions.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(versions).hasSize(1);
        }
    }

    @Test
    void independentStoresIgnoreLateOldVersionWriteAndKeepMembersSeparate() {
        var first = store();
        var second = store();
        String oldVersion = first.version(2L).orElseThrow();
        assertThat(second.version(2L)).contains(oldVersion);
        second.evict(2L);
        String currentVersion = second.version(2L).orElseThrow();
        first.save(2L, oldVersion, sample());
        assertThat(currentVersion).isNotEqualTo(oldVersion);
        assertThat(second.find(2L, currentVersion)).isEmpty();
        assertThat(second.find(3L, oldVersion)).isEmpty();
        second.save(2L, currentVersion, new TripMapCacheData(List.of()));
        assertThat(first.find(2L, currentVersion)).contains(new TripMapCacheData(List.of()));
    }

    @Test
    void realRedisAppliesDataAndVersionExpiry() {
        var store = store();
        String version = store.version(4L).orElseThrow();
        store.save(4L, version, sample());
        assertThat(redis.getExpire("cache:trip-map:user:4:" + version, TimeUnit.MILLISECONDS))
                .isBetween(30_000L, 60_000L);
        assertThat(redis.getExpire("cache:trip-map:version:user:4", TimeUnit.MILLISECONDS))
                .isBetween(90_000L, 120_000L);
    }

    private TripMapCacheStore store() {
        return new TripMapCacheStore(redis, new ObjectMapper());
    }

    private TripMapCacheData sample() {
        return new TripMapCacheData(List.of(new TripMapCacheData.Marker(
                7L, "trip", new BigDecimal("37.5"), new BigDecimal("127.2"), "preview", 3L)));
    }
}
