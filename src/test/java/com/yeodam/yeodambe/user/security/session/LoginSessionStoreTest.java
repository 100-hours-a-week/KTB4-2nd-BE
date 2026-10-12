package com.yeodam.yeodambe.user.security.session;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.LoginSessionEntity;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.LoginSessionRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LoginSessionStoreTest {

    @Autowired
    private LoginSessionStore loginSessionStore;

    @Autowired
    private LoginSessionRepository loginSessionRepository;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void SID와_리프레시_토큰_해시로_세션을_저장하고_조회한다() {
        User user = saveUser("lookup");
        String sid = UUID.randomUUID().toString();
        String refreshHash = tokenHasher.hash("refresh-token-lookup");

        loginSessionStore.save(sid, user.getUserId(), refreshHash);

        assertThat(loginSessionStore.findBySid(sid))
                .contains(new LoginSession(user.getUserId(), refreshHash));
        assertThat(loginSessionStore.findSidByRefreshTokenHash(refreshHash))
                .contains(sid);
        assertThat(loginSessionStore.findBySid("missing-sid"))
                .isEmpty();
    }

    @Test
    void 세션의_7일_유효기간을_저장한다() {
        User user = saveUser("expiration");
        String sid = UUID.randomUUID().toString();
        String refreshHash = tokenHasher.hash("refresh-token-expiration");
        LocalDateTime beforeSave = redisNow();

        loginSessionStore.save(sid, user.getUserId(), refreshHash);

        LocalDateTime savedExpiration = expiration(sid);
        assertThat(redis.getExpire(sessionKey(sid), TimeUnit.MILLISECONDS)).isBetween(604795000L, 604800000L);
        assertThat(redis.getExpire("yeodam:test:auth:refresh:" + refreshHash, TimeUnit.MILLISECONDS)).isBetween(604795000L, 604800000L);

        assertThat(savedExpiration)
                .isBetween(
                        beforeSave.plusDays(7),
                        redisNow().plusDays(7)
                );
    }

    @Test
    void 리프레시_토큰을_회전하고_유효기간을_연장한다() {
        User user = saveUser("rotation");
        String sid = UUID.randomUUID().toString();
        String oldHash = tokenHasher.hash("old-refresh-token");
        String newHash = tokenHasher.hash("new-refresh-token");

        loginSessionStore.save(sid, user.getUserId(), oldHash);
        redis.expire(sessionKey(sid), Duration.ofMinutes(1));
        LocalDateTime beforeRotation = redisNow();

        assertThat(loginSessionStore.rotate(oldHash, newHash))
                .contains(user.getUserId());
        assertThat(loginSessionStore.findSidByRefreshTokenHash(oldHash))
                .isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(newHash))
                .contains(sid);

        LocalDateTime rotatedExpiration = expiration(sid);
        assertThat(redis.getExpire(sessionKey(sid), TimeUnit.MILLISECONDS)).isBetween(604795000L, 604800000L);

        assertThat(rotatedExpiration)
                .isBetween(
                        beforeRotation.plusDays(7),
                        redisNow().plusDays(7)
                );
    }

    @Test
    void 만료된_세션은_조회할_수_없고_삭제된다() {
        User user = saveUser("expired");
        String sid = UUID.randomUUID().toString();
        String refreshHash = tokenHasher.hash("expired-refresh-token");

        loginSessionStore.save(sid, user.getUserId(), refreshHash);
        redis.expire(sessionKey(sid), Duration.ofMillis(1));
        redis.expire("yeodam:test:auth:refresh:" + refreshHash, Duration.ofMillis(1));
        try {
            Thread.sleep(20);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }

        assertThat(loginSessionStore.findBySid(sid)).isEmpty();
        assertThat(redis.hasKey(sessionKey(sid))).isFalse();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(refreshHash)).isEmpty();
    }

    @Test
    void 요청한_세션만_삭제한다() {
        User user = saveUser("logout");
        String requestedSid = UUID.randomUUID().toString();
        String otherSid = UUID.randomUUID().toString();

        loginSessionStore.save(
                requestedSid,
                user.getUserId(),
                tokenHasher.hash("logout-requested-refresh-token")
        );
        loginSessionStore.save(
                otherSid,
                user.getUserId(),
                tokenHasher.hash("logout-other-refresh-token")
        );

        loginSessionStore.deleteBySid(requestedSid);

        assertThat(loginSessionStore.findBySid(requestedSid)).isEmpty();
        assertThat(loginSessionStore.findBySid(otherSid))
                .isPresent();
    }

    @Test
    void 같은_토큰의_동시_회전은_하나의_요청만_성공한다() throws Exception {
        User user = saveUser("concurrent");
        String sid = UUID.randomUUID().toString();
        String oldHash = tokenHasher.hash("concurrent-old-token");
        String firstNewHash = tokenHasher.hash("concurrent-first-token");
        String secondNewHash = tokenHasher.hash("concurrent-second-token");
        loginSessionStore.save(sid, user.getUserId(), oldHash);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Optional<Long>> first = executor.submit(() -> {
                start.await();
                return loginSessionStore.rotate(oldHash, firstNewHash);
            });
            Future<Optional<Long>> second = executor.submit(() -> {
                start.await();
                return loginSessionStore.rotate(oldHash, secondNewHash);
            });

            start.countDown();
            Optional<Long> firstResult = first.get(10, TimeUnit.SECONDS);
            Optional<Long> secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.isPresent() == secondResult.isPresent())
                    .isFalse();
            assertThat(loginSessionStore.findSidByRefreshTokenHash(oldHash))
                    .isEmpty();

            String winningHash = firstResult.isPresent()
                    ? firstNewHash
                    : secondNewHash;
            String losingHash = firstResult.isPresent()
                    ? secondNewHash
                    : firstNewHash;

            assertThat(loginSessionStore.findBySid(sid))
                    .contains(new LoginSession(user.getUserId(), winningHash));
            assertThat(loginSessionStore.findSidByRefreshTokenHash(losingHash))
                    .isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 세션을_저장해도_RDB_행을_만들지_않는다() {
        User user = saveUser("redis-only");
        String sid = UUID.randomUUID().toString();
        loginSessionStore.save(sid, user.getUserId(), tokenHasher.hash(sid));
        assertThat(loginSessionRepository.findBySid(sid)).isEmpty();
        assertThat(loginSessionStore.findBySid(sid)).isPresent();
    }

    @Test
    void 요청한_사용자의_모든_세션과_리프레시_인덱스만_삭제한다() {
        User user = saveUser("withdraw");
        User other = saveUser("other-owner");
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String unrelated = UUID.randomUUID().toString();
        loginSessionStore.save(first, user.getUserId(), tokenHasher.hash(first));
        loginSessionStore.save(second, user.getUserId(), tokenHasher.hash(second));
        loginSessionStore.save(unrelated, other.getUserId(), tokenHasher.hash(unrelated));
        loginSessionStore.deleteByUserId(user.getUserId());
        assertThat(loginSessionStore.findBySid(first)).isEmpty();
        assertThat(loginSessionStore.findBySid(second)).isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(tokenHasher.hash(first))).isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(tokenHasher.hash(second))).isEmpty();
        assertThat(redis.hasKey("yeodam:test:auth:user-sessions:" + user.getUserId())).isFalse();
        assertThat(loginSessionStore.findBySid(unrelated)).isPresent();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(tokenHasher.hash(unrelated))).contains(unrelated);
    }

    @Test
    void 회전한_세션을_삭제하면_현재_리프레시_인덱스도_제거한다() {
        User user = saveUser("rotated-logout");
        String sid = UUID.randomUUID().toString();
        String oldHash = tokenHasher.hash(sid);
        String newHash = tokenHasher.hash(sid + "new");
        loginSessionStore.save(sid, user.getUserId(), oldHash);
        loginSessionStore.rotate(oldHash, newHash);
        loginSessionStore.deleteBySid(sid);
        assertThat(loginSessionStore.findBySid(sid)).isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(oldHash)).isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(newHash)).isEmpty();
        assertThat(loginSessionStore.rotate(newHash, tokenHasher.hash("never-resurrect"))).isEmpty();
    }

    @Test
    void 기존_RDB_세션으로_대체하지_않는다() {
        User user = saveUser("legacy");
        String sid = UUID.randomUUID().toString();
        String hash = tokenHasher.hash(sid);
        loginSessionRepository.saveAndFlush(new LoginSessionEntity(sid, user, hash, LocalDateTime.now().plusDays(7)));
        assertThat(loginSessionStore.findBySid(sid)).isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(hash)).isEmpty();
    }

    @Test
    void 남아있는_리프레시_인덱스로_없는_세션을_조회하지_않는다() {
        String hash = tokenHasher.hash(UUID.randomUUID().toString());
        String key = "yeodam:test:auth:refresh:" + hash;
        redis.opsForValue().set(key, "missing-sid", Duration.ofDays(7));
        assertThat(loginSessionStore.findSidByRefreshTokenHash(hash)).isEmpty();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void Redis_시간으로_만료를_계산해도_활성_세션을_사용자_인덱스에서_제거하지_않는다() {
        User user = saveUser("clock-skew");
        String existing = UUID.randomUUID().toString();
        loginSessionStore.save(existing, user.getUserId(), tokenHasher.hash(existing));
        String next = UUID.randomUUID().toString();
        String nextHash = tokenHasher.hash(next);
        LocalDateTime beforeSave = redisNow();
        loginSessionStore.save(next, user.getUserId(), nextHash);
        assertThat(expiration(next)).isBetween(beforeSave.plusDays(7), redisNow().plusDays(7));
        loginSessionStore.deleteByUserId(user.getUserId());
        assertThat(loginSessionStore.findBySid(existing)).isEmpty();
        assertThat(loginSessionStore.findBySid(next)).isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(tokenHasher.hash(existing))).isEmpty();
    }

    @Test
    void 서버에서_Lua를_금지해도_세션_생명주기가_동작한다() {
        String username = "template-test-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        acl("SETUSER", username, "on", ">" + password, "~yeodam:test:auth:*",
                "+@all", "-eval", "-evalsha", "-script");
        LettuceConnectionFactory adminFactory = (LettuceConnectionFactory) redis.getConnectionFactory();
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                adminFactory.getHostName(), adminFactory.getPort());
        config.setUsername(username);
        config.setPassword(password);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        StringRedisTemplate restricted = new StringRedisTemplate(factory);
        try {
            assertThatThrownBy(() -> restricted.execute((RedisCallback<Object>) connection ->
                    connection.execute("EVAL", bytes("return 1"), bytes("0"))))
                    .isInstanceOf(DataAccessException.class);
            LoginSessionStore store = new LoginSessionStore(restricted, "yeodam:test:auth:");
            String sid = UUID.randomUUID().toString();
            String otherSid = UUID.randomUUID().toString();
            String hash = tokenHasher.hash(sid);
            String rotated = tokenHasher.hash(sid + "rotated");
            store.save(sid, 1L, hash);
            assertThat(store.findSidByRefreshTokenHash(hash)).contains(sid);
            assertThat(store.rotate(hash, rotated)).contains(1L);
            store.deleteIfMatches(sid, 1L, hash);
            assertThat(store.findBySid(sid)).contains(new LoginSession(1L, rotated));
            store.deleteBySid(sid);
            assertThat(store.findBySid(sid)).isEmpty();
            assertThat(store.findSidByRefreshTokenHash(rotated)).isEmpty();
            store.save(otherSid, 1L, tokenHasher.hash(otherSid));
            store.deleteByUserId(1L);
            assertThat(store.findBySid(otherSid)).isEmpty();
        } finally {
            factory.destroy();
            redis.execute((RedisCallback<Object>) connection -> {
                @SuppressWarnings("unchecked")
                RedisAsyncCommands<byte[], byte[]> nativeCommands =
                        (RedisAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
                return nativeCommands.aclDeluser(username).toCompletableFuture().join();
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void 트랜잭션이_지연되어도_활성_세션이_사용자_인덱스_점수보다_늦게_만료되지_않는다() {
        StringRedisTemplate delayed = spy(redis);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (result instanceof Long millis && millis > 1_000_000_000_000L) {
                Thread.sleep(50);
            }
            return result;
        }).when(delayed).execute(any(RedisCallback.class));
        LoginSessionStore store = new LoginSessionStore(delayed, "yeodam:test:auth:");
        String sid = UUID.randomUUID().toString();
        String hash = tokenHasher.hash(sid);
        Long userId = saveUser("transaction-delay").getUserId();
        store.save(sid, userId, hash);
        assertMatchingExpiration(sid, hash, userId);
        String nextHash = tokenHasher.hash(sid + "next");
        store.rotate(hash, nextHash);
        assertMatchingExpiration(sid, nextHash, userId);
        store.deleteByUserId(userId);
    }

    private void assertMatchingExpiration(String sid, String hash, Long userId) {
        long expected = Long.parseLong((String) redis.opsForHash().get(sessionKey(sid), "expiresAt"));
        String index = "yeodam:test:auth:user-sessions:" + userId;
        assertThat(redis.opsForZSet().score(index, sid)).isEqualTo((double) expected);
        for (String key : List.of(sessionKey(sid), "yeodam:test:auth:refresh:" + hash, index)) {
            Long actual = redis.execute((RedisCallback<Long>) connection -> {
                @SuppressWarnings("unchecked")
                RedisAsyncCommands<byte[], byte[]> commands =
                        (RedisAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
                return commands.pexpiretime(bytes(key)).toCompletableFuture().join();
            });
            assertThat(actual).isEqualTo(expected);
        }
    }

    @Test
    void 인덱스에_없는_세션을_삭제해도_다른_활성_세션의_인덱스_연결을_유지한다() {
        User user = saveUser("missing-index-member");
        String sid = UUID.randomUUID().toString();
        String otherSid = UUID.randomUUID().toString();
        loginSessionStore.save(sid, user.getUserId(), tokenHasher.hash(sid));
        loginSessionStore.save(otherSid, user.getUserId(), tokenHasher.hash(otherSid));
        redis.opsForZSet().remove("yeodam:test:auth:user-sessions:" + user.getUserId(), sid);
        loginSessionStore.deleteBySid(sid);
        assertThat(redis.opsForZSet().range("yeodam:test:auth:user-sessions:" + user.getUserId(), 0, -1))
                .containsExactly(otherSid);
        loginSessionStore.deleteByUserId(user.getUserId());
        assertThat(loginSessionStore.findBySid(otherSid)).isEmpty();
        assertThat(redis.hasKey("yeodam:test:auth:refresh:" + tokenHasher.hash(otherSid))).isFalse();
    }

    @Test
    void 토큰_회전과_로그아웃이_경합해도_세션과_리프레시_인덱스를_남기지_않는다() throws Exception {
        assertRotationDeletionRace(false);
    }

    @Test
    void 토큰_회전과_탈퇴가_경합해도_세션과_리프레시_인덱스를_남기지_않는다() throws Exception {
        assertRotationDeletionRace(true);
    }

    private void assertRotationDeletionRace(boolean deleteUser) throws Exception {
        User user = saveUser("rotation-deletion-race");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                String sid = UUID.randomUUID().toString();
                String oldHash = tokenHasher.hash(sid);
                String newHash = tokenHasher.hash(sid + "new");
                loginSessionStore.save(sid, user.getUserId(), oldHash);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> rotation = executor.submit(() -> {
                    start.await();
                    return loginSessionStore.rotate(oldHash, newHash);
                });
                Future<?> deletion = executor.submit(() -> {
                    start.await();
                    if (deleteUser) {
                        loginSessionStore.deleteByUserId(user.getUserId());
                    } else {
                        loginSessionStore.deleteBySid(sid);
                    }
                    return null;
                });
                start.countDown();
                rotation.get(10, TimeUnit.SECONDS);
                deletion.get(10, TimeUnit.SECONDS);
                assertThat(loginSessionStore.findBySid(sid)).isEmpty();
                assertThat(redis.hasKey("yeodam:test:auth:refresh:" + oldHash)).isFalse();
                assertThat(redis.hasKey("yeodam:test:auth:refresh:" + newHash)).isFalse();
                assertThat(redis.hasKey("yeodam:test:auth:user-sessions:" + user.getUserId())).isFalse();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void acl(String... arguments) {
        byte[][] encoded = Arrays.stream(arguments)
                .map(LoginSessionStoreTest::bytes).toArray(byte[][]::new);
        redis.execute((RedisCallback<Object>) connection -> connection.execute("ACL", encoded));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String sessionKey(String sid) {
        return "yeodam:test:auth:session:" + sid;
    }

    private LocalDateTime redisNow() {
        Long millis = redis.execute((RedisCallback<Long>) connection ->
                connection.serverCommands().time(TimeUnit.MILLISECONDS));
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    private LocalDateTime expiration(String sid) {
        String expiresAt = (String) redis.opsForHash().get(sessionKey(sid), "expiresAt");
        assertThat(expiresAt).isNotNull();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(expiresAt)), ZoneId.systemDefault());
    }

    private User saveUser(String suffix) {
        return userRepository.save(new User(
                suffix + "-" + UUID.randomUUID() + "@yeodam.test",
                "세션회원"
        ));
    }
}
