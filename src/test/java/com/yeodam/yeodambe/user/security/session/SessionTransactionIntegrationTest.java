package com.yeodam.yeodambe.user.security.session;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.TokenHasher;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;
import com.yeodam.yeodambe.user.service.ProfileRegistrationService;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SessionTransactionIntegrationTest {
    @Autowired
    private UserRepository users;

    @Autowired
    private LoginSessionIssuer issuer;

    @Autowired
    private LoginSessionStore sessions;

    @Autowired
    private TokenHasher hasher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProfileTokenStore profiles;

    @Autowired
    private ProfileRegistrationService registration;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private AccessTokenIssuer accessTokens;

    @MockitoSpyBean
    private SessionIdGenerator sessionIds;


    @Test
    void rollbackDeletesOnlyNewSessionAndItsRefreshIndex() {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "보상회원"));
        IssuedLoginSession existing = issuer.issue(user.getUserId());
        AtomicReference<IssuedLoginSession> created = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            created.set(issuer.issue(user.getUserId()));
            assertThat(sessions.findBySid(created.get().sid())).isPresent();
            status.setRollbackOnly();
        });
        assertThat(sessions.findBySid(created.get().sid())).isEmpty();
        assertThat(sessions.findSidByRefreshTokenHash(hasher.hash(created.get().refreshToken()))).isEmpty();
        assertThat(sessions.findBySid(existing.sid())).isPresent();
        assertThat(sessions.findSidByRefreshTokenHash(hasher.hash(existing.refreshToken()))).contains(existing.sid());
    }

    @Test
    void failedSidCollisionMustNotDeleteExistingSessionOnRollback() {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "충돌회원"));
        IssuedLoginSession existing = issuer.issue(user.getUserId());
        doReturn(existing.sid()).when(sessionIds).generate();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .execute(status -> issuer.issue(user.getUserId())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sessions.findBySid(existing.sid())).isPresent();
        assertThat(sessions.findSidByRefreshTokenHash(hasher.hash(existing.refreshToken()))).contains(existing.sid());
    }

    @Test
    void successfulCommitKeepsIssuedSession() {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@yeodam.test", "커밋회원"));
        IssuedLoginSession issued = new TransactionTemplate(transactionManager)
                .execute(status -> issuer.issue(user.getUserId()));
        assertThat(sessions.findBySid(issued.sid())).isPresent();
        assertThat(sessions.findSidByRefreshTokenHash(hasher.hash(issued.refreshToken()))).contains(issued.sid());
    }

    @Test
    void jwtFailureRollsBackRegistrationAndPreservesProfileToken() {
        String unique = UUID.randomUUID().toString();
        String email = unique + "@yeodam.test";
        profiles.save(unique, new KakaoUserIdentity(unique, email));
        Set<String> sessionsBeforeRegistration = redis.keys("yeodam:test:auth:session:*");
        doThrow(new IllegalStateException("Simulated signing failure"))
                .when(accessTokens).issue(anyLong(), anyString());
        assertThatThrownBy(() -> registration.register(unique, "실패회원"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.existsByEmailAndDeletedAtIsNull(email)).isFalse();
        assertThat(profiles.find(unique)).isPresent();
        assertThat(redis.keys("yeodam:test:auth:session:*")).isEqualTo(sessionsBeforeRegistration);
    }
}
