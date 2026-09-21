package com.yeodam.yeodambe.user.repository;

import com.yeodam.yeodambe.user.entity.CsrfTokenEntity;
import com.yeodam.yeodambe.user.entity.LoginSessionEntity;
import com.yeodam.yeodambe.user.entity.LoginTicketEntity;
import com.yeodam.yeodambe.user.entity.OAuthStateEntity;
import com.yeodam.yeodambe.user.entity.ProfileTokenEntity;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AuthRepositoryTest {

    @Autowired
    private OAuthStateRepository oauthStateRepository;
    @Autowired
    private LoginTicketRepository loginTicketRepository;
    @Autowired
    private ProfileTokenRepository profileTokenRepository;
    @Autowired
    private CsrfTokenRepository csrfTokenRepository;
    @Autowired
    private LoginSessionRepository loginSessionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void findsAuthenticationDataThroughItsLookupKey() {
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(7);
        User user = userRepository.save(
                new User("auth-repository@yeodam.test", "저장소회원")
        );

        oauthStateRepository.save(new OAuthStateEntity(
                "a".repeat(64),
                "b".repeat(64),
                expiresAt
        ));
        loginTicketRepository.save(new LoginTicketEntity(
                "c".repeat(64),
                "d".repeat(64),
                "kakao-repository-user",
                "Identity@yeodam.test",
                expiresAt
        ));
        profileTokenRepository.save(new ProfileTokenEntity(
                "e".repeat(64),
                "kakao-repository-user",
                "Identity@yeodam.test",
                expiresAt
        ));
        csrfTokenRepository.save(new CsrfTokenEntity(
                "f".repeat(64),
                "csrf-repository-token",
                expiresAt
        ));
        loginSessionRepository.save(new LoginSessionEntity(
                "00000000-0000-0000-0000-000000000020",
                user,
                "1".repeat(64),
                expiresAt
        ));

        entityManager.flush();
        entityManager.clear();

        assertThat(oauthStateRepository.findByStateHashForUpdate("a".repeat(64)))
                .isPresent();
        assertThat(loginTicketRepository.findByTicketHashForUpdate("c".repeat(64)))
                .isPresent();
        assertThat(profileTokenRepository.findByTokenHashForUpdate("e".repeat(64)))
                .isPresent();
        assertThat(csrfTokenRepository.findByBrowserContextHash("f".repeat(64)))
                .isPresent();
        assertThat(csrfTokenRepository.findByBrowserContextHashForUpdate("f".repeat(64)))
                .isPresent();
        assertThat(loginSessionRepository.findBySid(
                "00000000-0000-0000-0000-000000000020"
        )).isPresent();
        assertThat(loginSessionRepository.findByRefreshTokenHash("1".repeat(64)))
                .isPresent();
        assertThat(loginSessionRepository.findByRefreshTokenHashForUpdate(
                "1".repeat(64)
        )).isPresent();
    }

    @Test
    void deletesOnlyExpiredAuthenticationData() {
        LocalDateTime now = LocalDateTime.now();

        oauthStateRepository.save(new OAuthStateEntity(
                "2".repeat(64),
                "3".repeat(64),
                now.minusSeconds(1)
        ));
        oauthStateRepository.save(new OAuthStateEntity(
                "4".repeat(64),
                "5".repeat(64),
                now.plusMinutes(5)
        ));

        long deletedCount = oauthStateRepository
                .deleteByExpiresAtLessThanEqual(now);

        assertThat(deletedCount).isEqualTo(1L);
        assertThat(oauthStateRepository.findAll())
                .extracting(OAuthStateEntity::getStateHash)
                .containsExactly("4".repeat(64));
    }
}
