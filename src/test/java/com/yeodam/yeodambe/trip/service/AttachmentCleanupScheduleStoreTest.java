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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AttachmentCleanupScheduleStoreTest {
    private static final String KEY = "trip:attachment:cleanup:scheduled";
    private static final String PROCESSING_KEY = "trip:attachment:cleanup:processing";
    private static final String OWNER_KEY = "trip:attachment:cleanup:owners";
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
        redis.delete(List.of(KEY, PROCESSING_KEY, OWNER_KEY));
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

    @Test
    void 미래_예약은_선점하지_않는다() {
        store.schedule(10L, Instant.now().plus(Duration.ofDays(1)));

        assertThat(store.claim(Duration.ofMinutes(1))).isEmpty();
        assertThat(redis.opsForZSet().zCard(KEY)).isEqualTo(1L);
        assertThat(redis.hasKey(PROCESSING_KEY)).isFalse();
    }

    @Test
    void 여러_호출자가_동시에_같은_사진을_선점하면_한개만_성공한다() throws Exception {
        store.schedule(10L, Instant.EPOCH);
        var gate = new CyclicBarrier(8);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 8)
                    .mapToObj(i -> (Callable<java.util.Optional<AttachmentCleanupScheduleStore.Claim>>) () -> {
                        var independentStore = new AttachmentCleanupScheduleStore(redis);
                        gate.await(5, TimeUnit.SECONDS);
                        return independentStore.claim(Duration.ofMinutes(1));
                    }).toList();
            var claims = new java.util.ArrayList<AttachmentCleanupScheduleStore.Claim>();
            for (var future : pool.invokeAll(tasks)) {
                future.get(5, TimeUnit.SECONDS).ifPresent(claims::add);
            }
            assertThat(claims).hasSize(1);
            assertThat(claims.getFirst().attachmentId()).isEqualTo(10L);
            assertThat(redis.opsForHash().get(OWNER_KEY, "10")).isEqualTo(claims.getFirst().token());
            assertThat(redis.opsForZSet().score(PROCESSING_KEY, "10")).isNotNull();
            assertThat(redis.opsForZSet().score(KEY, "10")).isNull();
        }
    }

    @Test
    void 만료된_선점은_새_토큰으로_가져오되_별도로_등록된_미래_예약은_보존한다() {
        store.schedule(10L, Instant.EPOCH);
        var first = store.claim(Duration.ofMinutes(1)).orElseThrow();
        Instant future = Instant.now().plus(Duration.ofDays(30));
        store.schedule(10L, future);
        redis.opsForZSet().add(PROCESSING_KEY, "10", 0);

        var second = store.claim(Duration.ofMinutes(1)).orElseThrow();

        assertThat(second.attachmentId()).isEqualTo(10L);
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(redis.opsForHash().get(OWNER_KEY, "10")).isEqualTo(second.token());
        assertThat(redis.opsForZSet().score(KEY, "10")).isEqualTo((double) future.toEpochMilli());
    }

    @Test
    void 처리중인_사진이_다시_예약되어도_건너뛰고_다음_사진을_선점한다() {
        store.schedule(10L, Instant.EPOCH);
        var first = store.claim(Duration.ofMinutes(1)).orElseThrow();
        store.schedule(10L, Instant.EPOCH);
        store.schedule(20L, Instant.EPOCH);

        var second = store.claim(Duration.ofMinutes(1)).orElseThrow();

        assertThat(second.attachmentId()).isEqualTo(20L);
        assertThat(redis.opsForHash().get(OWNER_KEY, "10")).isEqualTo(first.token());
        assertThat(redis.opsForZSet().score(KEY, "10")).isNotNull();
    }

    @Test
    void 선점시간이_0이면_예약을_옮기기_전에_실패한다() {
        store.schedule(10L, Instant.EPOCH);

        assertThatThrownBy(() -> store.claim(Duration.ZERO))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(redis.opsForZSet().score(KEY, "10")).isNotNull();
        assertThat(redis.hasKey(PROCESSING_KEY)).isFalse();
    }

    @Test
    void 토큰_목록의_자료형이_잘못되면_예약을_변경하기_전에_실패한다() {
        store.schedule(10L, Instant.EPOCH);
        redis.opsForValue().set(OWNER_KEY, "wrong-type");

        assertThatThrownBy(() -> store.claim(Duration.ofMinutes(1)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(redis.opsForZSet().score(KEY, "10")).isNotNull();
        assertThat(redis.hasKey(PROCESSING_KEY)).isFalse();
    }
}
