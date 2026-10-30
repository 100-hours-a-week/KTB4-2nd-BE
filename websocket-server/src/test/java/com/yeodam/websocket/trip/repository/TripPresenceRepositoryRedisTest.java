package com.yeodam.websocket.trip.repository;

import com.yeodam.websocket.RedisTestSupport;
import com.yeodam.websocket.trip.config.TripPresenceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class TripPresenceRepositoryRedisTest extends RedisTestSupport {
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private TripPresenceProperties properties;
    private TripPresenceRepository first;
    private TripPresenceRepository second;

    @BeforeEach
    void 테스트_준비() {
        connectionFactory = new LettuceConnectionFactory(redisHost(), redisPort());
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        properties = new TripPresenceProperties("test:" + UUID.randomUUID(), 16, 100, 10);
        first = new TripPresenceRepository(redis, JsonMapper.builder().build(), properties);
        second = new TripPresenceRepository(redis, JsonMapper.builder().build(), properties);
    }

    @AfterEach
    void Redis_클라이언트_종료() {
        connectionFactory.destroy();
    }

    @Test
    void 기한이_만료되면_참여자를_제거하고_목록_버전을_증가시킨다() {
        Instant now = Instant.now();
        TripPresenceRepository.ConnectionPresence shortGrant = new TripPresenceRepository.ConnectionPresence(
                "a:short", 1L, "지형", null, "READ", now, now.plusMillis(500), now.plusSeconds(30));
        TripPresenceRepository.PresenceChange joined = first.join(20L, shortGrant);
        assertThat(joined.snapshot().members()).hasSize(1);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            TripPresenceRepository.PresenceChange expired = second.expire(20L);
            assertThat(expired.snapshot().members()).isEmpty();
            assertThat(expired.snapshot().revision()).isGreaterThan(joined.snapshot().revision());
        });
    }

    @Test
    void 두_서버가_동시에_입장해도_참여자를_누락하지_않는다() throws Exception {
        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<TripPresenceRepository.PresenceChange> firstJoin = executor.submit(() -> {
                start.await();
                return first.join(21L, 참여_정보_생성("a:parallel", 1L, "지형"));
            });
            java.util.concurrent.Future<TripPresenceRepository.PresenceChange> secondJoin = executor.submit(() -> {
                start.await();
                return second.join(21L, 참여_정보_생성("b:parallel", 2L, "민수"));
            });
            start.countDown();
            firstJoin.get(5, java.util.concurrent.TimeUnit.SECONDS);
            secondJoin.get(5, java.util.concurrent.TimeUnit.SECONDS);
            TripPresenceRepository.PresenceChange current = first.findSnapshot(21L);
            assertThat(current.snapshot().members()).hasSize(2);
            assertThat(current.snapshot().revision()).isEqualTo(2L);
        }
    }

    @Test
    void 메타데이터가_유실되면_이전_참여자_없이_새_epoch를_시작한다() {
        TripPresenceRepository.PresenceChange joined = first.join(22L, 참여_정보_생성("a:old", 1L, "지형"));
        String key = properties.namespace() + ":{presence-" + Math.floorMod(22, properties.partitionCount())
                + "}:trip:22:meta";
        redis.delete(key);
        TripPresenceRepository.PresenceChange recovered = second.findSnapshot(22L);
        assertThat(recovered.snapshot().epoch()).isNotEqualTo(joined.snapshot().epoch());
        assertThat(recovered.snapshot().members()).isEmpty();
    }

    @Test
    void 미등록_여행에서_퇴장해도_영구_메타데이터를_생성하지_않는다() {
        first.leave(999L, "unknown");
        assertThat(first.findEpoch(999L)).isNull();
    }

    @Test
    void 두_서버가_목록을_공유하고_한_탭의_퇴장은_사용자를_제거하지_않는다() {
        TripPresenceRepository.PresenceChange joined = first.join(10L, 참여_정보_생성("server-a:tab1", 1L, "지형"));
        second.join(10L, 참여_정보_생성("server-b:tab2", 1L, "지형"));
        second.join(10L, 참여_정보_생성("server-b:tab3", 2L, "민수"));
        assertThat(first.findSnapshot(10L).snapshot().members()).hasSize(2);
        assertThat(first.leave(10L, "server-a:tab1").changed()).isFalse();
        assertThat(first.findSnapshot(10L).snapshot().members()).hasSize(2);
        assertThat(second.leave(10L, "server-b:tab2").snapshot().members()).hasSize(1);
        assertThat(joined.snapshot().epoch()).isNotBlank();
    }

    @Test
    void 중복_입장과_체류_갱신은_버전을_유지하고_반복_퇴장은_멱등이다() {
        TripPresenceRepository.ConnectionPresence member = 참여_정보_생성("a:tab", 1L, "지형");
        TripPresenceRepository.PresenceChange firstJoin = first.join(11L, member);
        assertThat(first.join(11L, member).snapshot().revision())
                .isEqualTo(firstJoin.snapshot().revision());
        assertThat(first.heartbeat(11L, "a:tab").changed()).isFalse();
        assertThat(first.leave(11L, "a:tab").snapshot().members()).isEmpty();
        assertThat(first.leave(11L, "a:tab").changed()).isFalse();
        assertThatThrownBy(() -> first.heartbeat(11L, "a:tab"))
                .hasMessageContaining("PRESENCE_NOT_JOINED");
    }

    @Test
    void 만료된_권한은_참여를_생성하거나_복구할_수_없다() {
        TripPresenceRepository.ConnectionPresence expired = new TripPresenceRepository.ConnectionPresence("a:expired", 1L, "지형", null,
                "READ", Instant.now(), Instant.now().minusSeconds(1), Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> first.join(12L, expired)).hasMessageContaining("UNAUTHORIZED");
        assertThat(first.findSnapshot(12L).snapshot().members()).isEmpty();
        first.refreshAccess(12L, 참여_정보_생성("a:missing", 1L, "지형"));
        assertThat(first.findSnapshot(12L).snapshot().members()).isEmpty();
    }

    @Test
    void 프로필_변경은_버전을_증가시키고_이전_권한은_덮어쓰지_못한다() {
        TripPresenceRepository.ConnectionPresence old = 참여_정보_생성("a:tab", 1L, "이전");
        TripPresenceRepository.PresenceChange before = first.join(13L, old);
        TripPresenceRepository.ConnectionPresence fresh = new TripPresenceRepository.ConnectionPresence("a:tab", 1L, "변경", null, "READ",
                old.checkedAt().plusSeconds(1), old.accessValidUntil(), old.tokenExpiresAt());
        TripPresenceRepository.PresenceChange updated = first.refreshAccess(13L, fresh);
        assertThat(updated.snapshot().revision()).isGreaterThan(before.snapshot().revision());
        first.refreshAccess(13L, old);
        assertThat(first.findSnapshot(13L).snapshot().members().getFirst().nickname()).isEqualTo("변경");
    }

    private TripPresenceRepository.ConnectionPresence 참여_정보_생성(String key, Long userId, String name) {
        Instant now = Instant.now();
        return new TripPresenceRepository.ConnectionPresence(key, userId, name, null, "READ", now,
                now.plusSeconds(30), now.plusSeconds(300));
    }
}
