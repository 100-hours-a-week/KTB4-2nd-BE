package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidNicknameException;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.oauth.LoginTicketStore;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenGenerator;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OAuthTemporaryDataTransactionIntegrationTest {
    @MockitoSpyBean private LoginTicketStore tickets;
    @MockitoSpyBean private ProfileTokenStore profiles;
    @Autowired private LoginTicketExchangeService exchange;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ProfileRegistrationService registration;
    @Autowired private UserRepository users;
    @MockitoSpyBean private AccessTokenIssuer jwt;
    @MockitoSpyBean private LoginSessionStore sessions;
    @MockitoSpyBean private ProfileTokenGenerator profileGenerator;

    @Test
    void 교환이_롤백되면_티켓을_복원하고_새_가입_토큰을_제거한다() {
        String token = UUID.randomUUID().toString();
        tickets.save(token, new KakaoUserIdentity(UUID.randomUUID().toString(), "retry@example.com"), "browser");
        TransactionTemplate tx = new TransactionTemplate(transactions);
        String profile = tx.execute(status -> {
            LoginExchangeDecision.Onboarding result =
                    (LoginExchangeDecision.Onboarding) exchange.exchange(token, "browser");
            status.setRollbackOnly();
            return result.profileToken();
        });
        assertThat(profiles.find(profile)).isEmpty();
        assertThat(exchange.exchange(token, "browser")).isInstanceOf(LoginExchangeDecision.Onboarding.class);
        assertThat(tickets.consume(token, "browser")).isEmpty();
    }

    @Test
    void 회원_가입이_실패하면_재시도를_위해_가입_토큰을_유지한다() {
        String token = UUID.randomUUID().toString();
        String email = token + "@yeodam.test";
        profiles.save(token, new KakaoUserIdentity(token, email));
        assertThatThrownBy(() -> registration.register(token, "잘못 된닉네임"))
                .isInstanceOf(InvalidNicknameException.class);
        assertThat(users.existsByEmailAndDeletedAtIsNull(email)).isFalse();
        assertThat(profiles.find(token)).isPresent();
        registration.register(token, "여행자");
        assertThat(profiles.find(token)).isEmpty();
    }

    @Test
    void JWT_발급_실패는_회원을_롤백하고_새_세션을_삭제하며_가입_토큰을_유지한다() {
        String token = UUID.randomUUID().toString();
        String email = token + "@yeodam.test";
        profiles.save(token, new KakaoUserIdentity(token, email));
        AtomicReference<String> issuedSid = new AtomicReference<>();
        doAnswer(invocation -> {
            issuedSid.set(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(sessions).save(anyString(), anyLong(), anyString());
        doThrow(new IllegalStateException("JWT failure")).when(jwt).issue(anyLong(), anyString());
        assertThatThrownBy(() -> registration.register(token, "여행자"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(issuedSid.get()).isNotNull();
        assertThat(sessions.findBySid(issuedSid.get())).isEmpty();
        assertThat(users.existsByEmailAndDeletedAtIsNull(email)).isFalse();
        assertThat(profiles.find(token)).isPresent();
    }

    @Test
    void 커밋_전_실패는_가입_토큰_선점을_해제하고_회원을_롤백한다() {
        String token = UUID.randomUUID().toString();
        String email = token + "@yeodam.test";
        profiles.save(token, new KakaoUserIdentity(token, email));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            registration.register(token, "여행자");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("Injected pre-commit failure");
                }
            });
            return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(users.existsByEmailAndDeletedAtIsNull(email)).isFalse();
        assertThat(profiles.find(token)).isPresent();
    }

    @Test
    void 선점_응답을_잃어도_롤백이_확인된_후에만_선점을_해제한다() {
        String token = UUID.randomUUID().toString();
        tickets.save(token, new KakaoUserIdentity(token, token + "@yeodam.test"), "browser");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("Lost claim response");
        }).when(tickets).claim(eq(token), eq("browser"), anyString());
        assertThatThrownBy(() -> exchange.exchange(token, "browser"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        Mockito.doCallRealMethod().when(tickets).claim(eq(token), eq("browser"), anyString());
        assertThat(tickets.consume(token, "browser")).isPresent();
    }

    @Test
    void 완료_처리가_실패해도_커밋된_가입_토큰_선점은_사용할_수_없다() {
        String token = UUID.randomUUID().toString();
        String email = token + "@yeodam.test";
        profiles.save(token, new KakaoUserIdentity(token, email));
        doThrow(new DataAccessResourceFailureException("Cleanup failure"))
                .when(profiles).complete(eq(token), anyString());
        registration.register(token, "여행자");
        assertThat(users.existsByEmailAndDeletedAtIsNull(email)).isTrue();
        assertThat(profiles.claim(token, "retry")).isEmpty();
    }

    @Test
    void 가입_토큰_발급_응답을_잃으면_발급분을_정리하고_티켓으로_재시도할_수_있다() {
        String ticket = UUID.randomUUID().toString();
        String profile = UUID.randomUUID().toString();
        tickets.save(ticket, new KakaoUserIdentity(ticket, ticket + "@yeodam.test"), "browser");
        Mockito.doReturn(profile).when(profileGenerator).generate();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("Lost issuance response");
        }).when(profiles).save(eq(profile), ArgumentMatchers.any(), anyString());
        assertThatThrownBy(() -> exchange.exchange(ticket, "browser"))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(profiles.find(profile)).isEmpty();
        assertThat(tickets.consume(ticket, "browser")).isPresent();
    }

    @Test
    void 완료_여부를_모르면_티켓_선점을_해제하지_않는다() {
        String ticket = UUID.randomUUID().toString();
        tickets.save(ticket, new KakaoUserIdentity(ticket, ticket + "@yeodam.test"), "browser");
        new TransactionTemplate(transactions).execute(status -> {
            exchange.exchange(ticket, "browser");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
            assertThat(tickets.claim(ticket, "browser", "another-request")).isEmpty();
            status.setRollbackOnly();
            return null;
        });
    }

    @Test
    void 가입_토큰_발급_충돌은_기존_토큰을_삭제하지_않고_티켓을_복원한다() {
        String ticket = UUID.randomUUID().toString();
        String profile = UUID.randomUUID().toString();
        KakaoUserIdentity existing = new KakaoUserIdentity("existing", "existing@example.com");
        profiles.save(profile, existing);
        tickets.save(ticket, new KakaoUserIdentity(ticket, ticket + "@yeodam.test"), "browser");
        org.mockito.Mockito.doReturn(profile).when(profileGenerator).generate();
        assertThatThrownBy(() -> exchange.exchange(ticket, "browser"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(profiles.find(profile)).contains(existing);
        assertThat(tickets.consume(ticket, "browser")).isPresent();
    }

    @Test
    void 완료_여부를_모르면_가입_토큰_선점을_해제하지_않는다() {
        String token = UUID.randomUUID().toString();
        profiles.save(token, new KakaoUserIdentity(token, token + "@yeodam.test"));
        new TransactionTemplate(transactions).execute(status -> {
            registration.register(token, "여행자");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
            assertThat(profiles.claim(token, "another-request")).isEmpty();
            status.setRollbackOnly();
            return null;
        });
    }
}
