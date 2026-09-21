package com.yeodam.yeodambe.user.entity;

import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AuthEntityMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void mapsAuthenticationEntitiesToLocalDatabase() {
        LocalDateTime now = LocalDateTime.now();
        User user = new User("auth-entity@yeodam.test", "인증회원");
        entityManager.persist(user);

        OAuthStateEntity oauthState = new OAuthStateEntity(
                "a".repeat(64),
                "b".repeat(64),
                now.plusMinutes(5)
        );
        LoginTicketEntity loginTicket = new LoginTicketEntity(
                "c".repeat(64),
                "d".repeat(64),
                "kakao-user-entity",
                "Identity@yeodam.test",
                now.plusMinutes(1)
        );
        ProfileTokenEntity profileToken = new ProfileTokenEntity(
                "e".repeat(64),
                "kakao-user-entity",
                "Identity@yeodam.test",
                now.plusMinutes(10)
        );
        CsrfTokenEntity csrfToken = new CsrfTokenEntity(
                "f".repeat(64),
                "csrf-token-value",
                now.plusDays(7)
        );
        LoginSessionEntity loginSession = new LoginSessionEntity(
                "00000000-0000-0000-0000-000000000010",
                user,
                "1".repeat(64),
                now.plusDays(7)
        );

        entityManager.persist(oauthState);
        entityManager.persist(loginTicket);
        entityManager.persist(profileToken);
        entityManager.persist(csrfToken);
        entityManager.persist(loginSession);
        entityManager.flush();

        Long oauthStateId = oauthState.getOauthStateId();
        Long loginTicketId = loginTicket.getLoginTicketId();
        Long profileTokenId = profileToken.getProfileTokenId();
        Long csrfTokenId = csrfToken.getCsrfTokenId();
        Long loginSessionId = loginSession.getLoginSessionId();
        entityManager.clear();

        assertThat(entityManager.find(OAuthStateEntity.class, oauthStateId))
                .isNotNull();
        assertThat(entityManager.find(LoginTicketEntity.class, loginTicketId))
                .isNotNull();
        assertThat(entityManager.find(ProfileTokenEntity.class, profileTokenId))
                .isNotNull();
        assertThat(entityManager.find(CsrfTokenEntity.class, csrfTokenId))
                .isNotNull();

        LoginSessionEntity foundSession = entityManager.find(
                LoginSessionEntity.class,
                loginSessionId
        );

        assertThat(foundSession).isNotNull();
        assertThat(Hibernate.isInitialized(foundSession.getUser())).isFalse();
        assertThat(foundSession.getUser().getUserId()).isEqualTo(user.getUserId());
    }
}
