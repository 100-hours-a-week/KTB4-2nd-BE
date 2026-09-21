package com.yeodam.yeodambe.user.security.session;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.LoginSessionEntity;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.LoginSessionRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LoginSessionStoreTest {

    @Autowired
    private LoginSessionStore loginSessionStore;

    @Autowired
    private LoginSessionRepository loginSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenHasher tokenHasher;

    @Test
    void savesAndFindsSessionBySidAndRefreshTokenHash() {
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
    void storesSevenDayExpiration() {
        User user = saveUser("expiration");
        String sid = UUID.randomUUID().toString();
        String refreshHash = tokenHasher.hash("refresh-token-expiration");
        LocalDateTime beforeSave = LocalDateTime.now();

        loginSessionStore.save(sid, user.getUserId(), refreshHash);

        LoginSessionEntity saved = loginSessionRepository
                .findBySid(sid)
                .orElseThrow();

        assertThat(saved.getExpiresAt())
                .isBetween(
                        beforeSave.plusDays(7),
                        LocalDateTime.now().plusDays(7)
                );
    }

    @Test
    void rotatesRefreshTokenAndExtendsExpiration() {
        User user = saveUser("rotation");
        String sid = UUID.randomUUID().toString();
        String oldHash = tokenHasher.hash("old-refresh-token");
        String newHash = tokenHasher.hash("new-refresh-token");

        loginSessionStore.save(sid, user.getUserId(), oldHash);
        LocalDateTime beforeRotation = LocalDateTime.now();

        assertThat(loginSessionStore.rotate(oldHash, newHash))
                .contains(user.getUserId());
        assertThat(loginSessionStore.findSidByRefreshTokenHash(oldHash))
                .isEmpty();
        assertThat(loginSessionStore.findSidByRefreshTokenHash(newHash))
                .contains(sid);

        LoginSessionEntity rotated = loginSessionRepository
                .findBySid(sid)
                .orElseThrow();

        assertThat(rotated.getExpiresAt())
                .isBetween(
                        beforeRotation.plusDays(7),
                        LocalDateTime.now().plusDays(7)
                );
    }

    @Test
    void expiredSessionCannotBeFoundAndIsDeleted() {
        User user = saveUser("expired");
        String sid = UUID.randomUUID().toString();
        String refreshHash = tokenHasher.hash("expired-refresh-token");

        loginSessionRepository.save(new LoginSessionEntity(
                sid,
                user,
                refreshHash,
                LocalDateTime.now().minusSeconds(1)
        ));

        assertThat(loginSessionStore.findBySid(sid)).isEmpty();
        assertThat(loginSessionRepository.findBySid(sid)).isEmpty();
    }

    @Test
    void onlyOneConcurrentRotationOfSameTokenSucceeds() throws Exception {
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

    private User saveUser(String suffix) {
        return userRepository.save(new User(
                suffix + "-" + UUID.randomUUID() + "@yeodam.test",
                "세션회원"
        ));
    }
}
