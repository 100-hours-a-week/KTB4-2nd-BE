package com.yeodam.yeodambe.user.security;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.oauth.OAuthStateStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RedisTemporaryAuthStoreTest {
    @Autowired private CsrfTokenStore csrf;
    @Autowired private OAuthStateStore states;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TokenHasher hasher;

    @Test
    void csrfLivesInRedisAndLookupDoesNotExtendItsLifetime() {
        String context = UUID.randomUUID().toString();
        csrf.save(context, "known-csrf");
        String key = "yeodam:test:auth:csrf:" + hasher.hash(context);
        assertThat(redis.opsForValue().get(key)).isEqualTo("known-csrf");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(604795000L, 604800000L);
        redis.expire(key, Duration.ofSeconds(30));
        assertThat(csrf.findOrCreate(context, () -> "replacement")).isEqualTo("known-csrf");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(30000L);
        csrf.delete(context);
        assertThat(csrf.find(context)).isNull();
    }

    @Test
    void wrongBrowserDoesNotDeleteRedisState() {
        String state = UUID.randomUUID().toString();
        states.save(state, "correct-browser");
        String key = "yeodam:test:auth:oauth-state:" + hasher.hash(state);
        assertThat(redis.opsForValue().get(key)).isEqualTo(hasher.hash("correct-browser"));
        assertThat(states.consume(state, "wrong-browser")).isFalse();
        assertThat(states.consume(state, "correct-browser")).isTrue();
        assertThat(states.consume(state, "correct-browser")).isFalse();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void concurrentCsrfIssuanceReturnsSameStoredToken() throws Exception {
        String context = UUID.randomUUID().toString();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return csrf.findOrCreate(context, () -> "first"); });
            var second = executor.submit(() -> { start.await(); return csrf.findOrCreate(context, () -> "second"); });
            start.countDown();
            String winner = first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(winner);
            assertThat(csrf.find(context)).isEqualTo(winner);
        }
    }

    @Test
    void concurrentStateConsumptionHasOnlyOneWinner() throws Exception {
        String state = UUID.randomUUID().toString();
        states.save(state, "browser");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return states.consume(state, "browser"); });
            var second = executor.submit(() -> { start.await(); return states.consume(state, "browser"); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
