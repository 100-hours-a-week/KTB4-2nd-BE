package com.yeodam.yeodambe.trip.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AttachmentCleanupScheduleStoreTest {
    private static final String KEY = "trip:attachment:cleanup:scheduled";
    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private AttachmentCleanupScheduleStore store;
    private final Instant now = Instant.parse("2026-10-16T00:00:00Z");

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
        if (connections != null) connections.destroy();
    }

    @BeforeEach
    void prepare() {
        redis.delete(KEY);
        store = new AttachmentCleanupScheduleStore(redis);
    }

    @Test
    void 현재까지_도래한_예약만_예정시각순으로_제한해서_반환한다() {
        store.schedule(30L, now.plusMillis(1));
        store.schedule(20L, now);
        store.schedule(10L, now.minusSeconds(1));

        assertThat(store.findDue(now, 1)).containsExactly(10L);
        assertThat(store.findDue(now, 100)).containsExactly(10L, 20L);
    }

    @Test
    void 같은_첨부를_다시_등록하면_예정시각만_갱신한다() {
        store.schedule(10L, now.minusSeconds(1));
        store.schedule(10L, now.plusSeconds(60));

        assertThat(store.findDue(now, 100)).isEmpty();
        assertThat(store.findDue(now.plusSeconds(60), 100)).containsExactly(10L);
        assertThat(redis.opsForZSet().zCard(KEY)).isEqualTo(1L);
    }

    @Test
    void 조회는_예약을_제거하지_않고_목록은_자동_만료되지_않는다() {
        store.schedule(10L, now);

        assertThat(store.findDue(now, 100)).containsExactly(10L);
        assertThat(store.findDue(now, 100)).containsExactly(10L);
        assertThat(redis.getExpire(KEY)).isEqualTo(-1L);
    }

    @Test
    void 예약이_없으면_빈_목록을_반환한다() {
        assertThat(store.findDue(now, 100)).isEmpty();
    }
}
