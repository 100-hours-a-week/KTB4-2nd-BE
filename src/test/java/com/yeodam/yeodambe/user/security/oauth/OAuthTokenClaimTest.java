package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OAuthTokenClaimTest {
    @Autowired private LoginTicketStore tickets;
    @Autowired private ProfileTokenStore profiles;
    @Autowired private StringRedisTemplate redis;
    @Autowired private TokenHasher hasher;
    @Autowired private ObjectMapper objectMapper;

    private final KakaoUserIdentity identity = new KakaoUserIdentity("provider", "claim@example.com");

    @Test
    void ticketClaimPreservesBrowserBindingAndOnlyOwnerCanReleaseOrComplete() {
        String token = UUID.randomUUID().toString();
        tickets.save(token, identity, "browser");
        assertThat(tickets.claim(token, "wrong", "intruder")).isEmpty();
        assertThat(tickets.claim(token, "browser", "owner")).isPresent();
        tickets.release(token, "wrong-owner");
        tickets.complete(token, "wrong-owner");
        assertThat(tickets.claim(token, "browser", "another")).isEmpty();
        tickets.release(token, "owner");
        assertThat(tickets.claim(token, "browser", "next")).isPresent();
        tickets.complete(token, "next");
        assertThat(tickets.claim(token, "browser", "again")).isEmpty();
    }

    @Test
    void claimAndReleaseKeepOriginalExpirationAndDoNotRecreateDeletedKey() {
        String token = UUID.randomUUID().toString();
        profiles.save(token, identity);
        String key = "yeodam:test:auth:profile-token:" + hasher.hash(token);
        String original = redis.opsForValue().get(key);
        Long originalExpiration = expiration(key);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(595000L, 600000L);
        assertThat(profiles.claim(token, "owner")).isPresent();
        profiles.release(token, "owner");
        assertThat(redis.opsForValue().get(key)).isEqualTo(original);
        assertThat(expiration(key)).isEqualTo(originalExpiration);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(600000L);
        assertThat(profiles.claim(token, "owner")).isPresent();
        redis.delete(key);
        profiles.release(token, "owner");
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void concurrentProfileClaimsHaveOnlyOneWinner() throws Exception {
        String token = UUID.randomUUID().toString();
        profiles.save(token, identity);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return profiles.claim(token, "first"); });
            var second = executor.submit(() -> { start.await(); return profiles.claim(token, "second"); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).isPresent()
                    ^ second.get(10, TimeUnit.SECONDS).isPresent()).isTrue();
        }
    }

    @Test
    void issuanceCollisionAndForeignCompensationPreserveExistingToken() {
        String token = UUID.randomUUID().toString();
        profiles.save(token, identity, "first-issuance");
        assertThatThrownBy(() -> profiles.save(token, identity, "colliding-issuance"))
                .isInstanceOf(DataIntegrityViolationException.class);
        profiles.deleteIfIssuedBy(token, "colliding-issuance");
        assertThat(profiles.find(token)).contains(identity);
        profiles.deleteIfIssuedBy(token, "first-issuance");
        assertThat(profiles.find(token)).isEmpty();
    }

    @Test
    void corruptTokenFailsClosed() {
        String token = UUID.randomUUID().toString();
        String key = "yeodam:test:auth:profile-token:" + hasher.hash(token);
        redis.opsForValue().set(key, "corrupted-private-value", Duration.ofMinutes(1));
        assertThatThrownBy(() -> profiles.claim(token, "owner"))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageNotContaining("corrupted-private-value");
    }

    @Test
    void missingOrMalformedTicketBrowserBindingIsStorageFailure() {
        for (String replacement : List.of("null", "\"invalid\"")) {
            String token = UUID.randomUUID().toString();
            tickets.save(token, identity, "browser");
            String key = "yeodam:test:auth:login-ticket:" + hasher.hash(token);
            String json = redis.opsForValue().get(key);
            redis.opsForValue().set(key, json.replace("\"" + hasher.hash("browser") + "\"", replacement),
                    Duration.ofMinutes(1));
            assertThatThrownBy(() -> tickets.claim(token, "browser", "owner"))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }
    }

    @Test
    void temporaryStoresWorkWithLuaDisabled() {
        String username = "oauth-test-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        acl("SETUSER", username, "on", ">" + password, "~yeodam:test:auth:*",
                "+@all", "-eval", "-evalsha", "-script");
        var admin = (LettuceConnectionFactory) redis.getConnectionFactory();
        var configuration = new RedisStandaloneConfiguration(
                admin.getHostName(), admin.getPort());
        configuration.setUsername(username);
        configuration.setPassword(password);
        var factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        try {
            var restricted = new StringRedisTemplate(factory);
            assertThatThrownBy(() -> restricted.execute((RedisCallback<Object>)
                    connection -> connection.execute("EVAL", bytes("return 1"), bytes("0"))))
                    .isInstanceOf(DataAccessException.class);
            var operations = new RedisOAuthTokenOperations(restricted, objectMapper);
            var profileStore = new ProfileTokenStore(operations, hasher, "yeodam:test:auth:");
            var stateStore = new OAuthStateStore(operations, hasher, "yeodam:test:auth:");
            var ticketStore = new LoginTicketStore(operations, hasher, "yeodam:test:auth:");
            String token = UUID.randomUUID().toString();
            profileStore.save(token, identity);
            assertThat(profileStore.claim(token, "owner")).isPresent();
            profileStore.release(token, "owner");
            assertThat(profileStore.claim(token, "next")).isPresent();
            profileStore.complete(token, "next");
            assertThat(profileStore.find(token)).isEmpty();
            stateStore.save(token, "browser");
            assertThat(stateStore.consume(token, "browser")).isTrue();
            ticketStore.save(token, identity, "browser");
            assertThat(ticketStore.consume(token, "browser")).contains(identity);
        } finally {
            factory.destroy();
            redis.execute((RedisCallback<Long>) connection -> {
                @SuppressWarnings("unchecked")
                io.lettuce.core.api.async.RedisAsyncCommands<byte[], byte[]> commands =
                        (io.lettuce.core.api.async.RedisAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
                return commands.aclDeluser(username).toCompletableFuture().join();
            });
        }
    }

    private void acl(String... arguments) {
        byte[][] encoded = Arrays.stream(arguments).map(OAuthTokenClaimTest::bytes).toArray(byte[][]::new);
        redis.execute((RedisCallback<Object>) connection -> connection.execute("ACL", encoded));
    }

    private Long expiration(String key) {
        return redis.execute((RedisCallback<Long>) connection -> {
            @SuppressWarnings("unchecked")
            io.lettuce.core.api.async.RedisAsyncCommands<byte[], byte[]> commands =
                    (io.lettuce.core.api.async.RedisAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
            return commands.pexpiretime(bytes(key)).toCompletableFuture().join();
        });
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
