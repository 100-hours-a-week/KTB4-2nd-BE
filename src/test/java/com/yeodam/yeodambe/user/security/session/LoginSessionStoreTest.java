package com.yeodam.yeodambe.user.security.session;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LoginSessionStoreTest {

    @Autowired
    private LoginSessionStore loginSessionStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void savesAndFindsSessionBySid() {
        loginSessionStore.save("sid-1", 42L, "refresh-hash-1");

        assertThat(loginSessionStore.findBySid("sid-1"))
                .contains(new LoginSession(42L, "refresh-hash-1"));
        assertThat(loginSessionStore.findSidByRefreshTokenHash("refresh-hash-1"))
                .contains("sid-1");
        assertThat(loginSessionStore.findBySid("missing-sid"))
                .isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash("missing-hash"))
                .isEmpty();
    }

    @Test
    void sessionExpiresAfterSevenDays() {
        loginSessionStore.save("sid-ttl", 42L, "refresh-hash-2");

        Long ttlSeconds = redisTemplate.getExpire(
                "auth:session:sid-ttl",
                TimeUnit.SECONDS
        );
        Long refreshIndexTtlSeconds = redisTemplate.getExpire(
                "auth:refresh:refresh-hash-2",
                TimeUnit.SECONDS
        );

        assertThat(ttlSeconds).isBetween(604790L, 604800L);
        assertThat(refreshIndexTtlSeconds).isBetween(604790L, 604800L);
    }

    @Test
    void rotatesRefreshTokenAndRemovesOldIndex() {
        String suffix = UUID.randomUUID().toString();
        String sid = "sid-" + suffix;
        String oldHash = "old-" + suffix;
        String newHash = "new-" + suffix;
        loginSessionStore.save(sid, 42L, oldHash);

        assertThat(loginSessionStore.rotate(oldHash, newHash)).contains(42L);

        assertThat(loginSessionStore.findBySid(sid))
                .contains(new LoginSession(42L, newHash));
        assertThat(loginSessionStore.findSidByRefreshTokenHash(oldHash))
                .isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(newHash))
                .contains(sid);
        assertThat(redisTemplate.getExpire("auth:session:" + sid, TimeUnit.SECONDS))
                .isBetween(604790L, 604800L);
        assertThat(redisTemplate.getExpire("auth:refresh:" + newHash, TimeUnit.SECONDS))
                .isBetween(604790L, 604800L);
    }

    @Test
    void onlyOneConcurrentRotationOfSameTokenSucceeds() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String sid = "sid-" + suffix;
        String oldHash = "old-" + suffix;
        String firstNewHash = "first-" + suffix;
        String secondNewHash = "second-" + suffix;
        loginSessionStore.save(sid, 42L, oldHash);

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

            assertThat(firstResult.isPresent() == secondResult.isPresent()).isFalse();
            assertThat(loginSessionStore.findSidByRefreshTokenHash(oldHash)).isEmpty();

            String winningHash = firstResult.isPresent() ? firstNewHash : secondNewHash;
            String losingHash = firstResult.isPresent() ? secondNewHash : firstNewHash;
            assertThat(loginSessionStore.findBySid(sid))
                    .contains(new LoginSession(42L, winningHash));
            assertThat(loginSessionStore.findSidByRefreshTokenHash(winningHash))
                    .contains(sid);
            assertThat(loginSessionStore.findSidByRefreshTokenHash(losingHash))
                    .isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }
}
