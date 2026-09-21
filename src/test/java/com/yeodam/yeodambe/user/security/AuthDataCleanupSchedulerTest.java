package com.yeodam.yeodambe.user.security;

import com.yeodam.yeodambe.user.entity.CsrfTokenEntity;
import com.yeodam.yeodambe.user.entity.LoginSessionEntity;
import com.yeodam.yeodambe.user.entity.LoginTicketEntity;
import com.yeodam.yeodambe.user.entity.OAuthStateEntity;
import com.yeodam.yeodambe.user.entity.ProfileTokenEntity;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.CsrfTokenRepository;
import com.yeodam.yeodambe.user.repository.LoginSessionRepository;
import com.yeodam.yeodambe.user.repository.LoginTicketRepository;
import com.yeodam.yeodambe.user.repository.OAuthStateRepository;
import com.yeodam.yeodambe.user.repository.ProfileTokenRepository;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(AuthDataCleanupScheduler.class)
class AuthDataCleanupSchedulerTest {

    @Autowired
    private AuthDataCleanupScheduler cleanupScheduler;
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
    void deletesExpiredAuthenticationDataAndKeepsActiveData() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiredAt = now.minusMinutes(1);
        LocalDateTime activeUntil = now.plusDays(1);
        User user = userRepository.save(
                new User("cleanup@yeodam.test", "정리회원")
        );

        oauthStateRepository.saveAll(List.of(
                new OAuthStateEntity(
                        "a".repeat(64), "b".repeat(64), expiredAt
                ),
                new OAuthStateEntity(
                        "c".repeat(64), "d".repeat(64), activeUntil
                )
        ));
        loginTicketRepository.saveAll(List.of(
                new LoginTicketEntity(
                        "e".repeat(64),
                        "f".repeat(64),
                        "expired-kakao-user",
                        "expired@yeodam.test",
                        expiredAt
                ),
                new LoginTicketEntity(
                        "0".repeat(64),
                        "1".repeat(64),
                        "active-kakao-user",
                        "active@yeodam.test",
                        activeUntil
                )
        ));
        profileTokenRepository.saveAll(List.of(
                new ProfileTokenEntity(
                        "2".repeat(64),
                        "expired-kakao-user",
                        "expired@yeodam.test",
                        expiredAt
                ),
                new ProfileTokenEntity(
                        "3".repeat(64),
                        "active-kakao-user",
                        "active@yeodam.test",
                        activeUntil
                )
        ));
        csrfTokenRepository.saveAll(List.of(
                new CsrfTokenEntity(
                        "4".repeat(64), "expired-csrf", expiredAt
                ),
                new CsrfTokenEntity(
                        "5".repeat(64), "active-csrf", activeUntil
                )
        ));
        loginSessionRepository.saveAll(List.of(
                new LoginSessionEntity(
                        UUID.randomUUID().toString(),
                        user,
                        "6".repeat(64),
                        expiredAt
                ),
                new LoginSessionEntity(
                        UUID.randomUUID().toString(),
                        user,
                        "7".repeat(64),
                        activeUntil
                )
        ));
        entityManager.flush();

        cleanupScheduler.deleteExpiredAuthenticationData();
        entityManager.flush();
        entityManager.clear();

        assertThat(oauthStateRepository.findAll())
                .extracting(OAuthStateEntity::getStateHash)
                .containsExactly("c".repeat(64));
        assertThat(loginTicketRepository.findAll())
                .extracting(LoginTicketEntity::getTicketHash)
                .containsExactly("0".repeat(64));
        assertThat(profileTokenRepository.findAll())
                .extracting(ProfileTokenEntity::getTokenHash)
                .containsExactly("3".repeat(64));
        assertThat(csrfTokenRepository.findAll())
                .extracting(CsrfTokenEntity::getBrowserContextHash)
                .containsExactly("5".repeat(64));
        assertThat(loginSessionRepository.findAll())
                .extracting(LoginSessionEntity::getRefreshTokenHash)
                .containsExactly("7".repeat(64));
    }
}
