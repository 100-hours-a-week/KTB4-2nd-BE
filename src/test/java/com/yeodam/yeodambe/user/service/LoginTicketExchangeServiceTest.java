package com.yeodam.yeodambe.user.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.common.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.security.oauth.LoginTicketStore;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenGenerator;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;
import com.yeodam.yeodambe.user.security.session.IssuedLoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import com.yeodam.yeodambe.user.security.oauth.OAuthTokenClaim;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LoginTicketExchangeServiceTest {

    @Mock
    private LoginTicketStore loginTicketStore;

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    @Mock
    private ProfileTokenGenerator profileTokenGenerator;

    @Mock
    private ProfileTokenStore profileTokenStore;

    @Mock
    private LoginSessionIssuer loginSessionIssuer;

    @Mock
    private AccessTokenIssuer accessTokenIssuer;

    private LoginTicketExchangeService service;
    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        service = new LoginTicketExchangeService(
                loginTicketStore,
                oauthAccountRepository,
                profileTokenGenerator,
                profileTokenStore,
                loginSessionIssuer,
                accessTokenIssuer
        );
        serviceLogger = (Logger) LoggerFactory.getLogger(LoginTicketExchangeService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Test
    void rejectsExpiredOrAlreadyConsumedTicket() {
        given(loginTicketStore.claim(eq("expired-ticket"), eq("browser-1"), anyString()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.exchange("expired-ticket", "browser-1"))
                .isInstanceOf(LoginTicketInvalidOrExpiredException.class);

        then(loginTicketStore).should()
                .claim(eq("expired-ticket"), eq("browser-1"), anyString());
        verifyNoInteractions(oauthAccountRepository, loginSessionIssuer, accessTokenIssuer);

        ILoggingEvent event = findAuthLoginEvent("failure");
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(keyValue(event, "failure_stage")).isEqualTo("ticket_exchange");
        assertThat(keyValue(event, "error_code")).isEqualTo("LOGIN_TICKET_INVALID_OR_EXPIRED");
        assertThat(event.getFormattedMessage())
                .doesNotContain("expired-ticket");
    }

    @Test
    void returnsOnboardingDecisionWhenKakaoAccountIsNotLinked() {
        KakaoUserIdentity identity =
                new KakaoUserIdentity("kakao-user-1", "user@example.com");
        given(loginTicketStore.claim(eq("valid-ticket"), eq("browser-1"), anyString()))
                .willAnswer(invocation -> Optional.of(new OAuthTokenClaim(identity, invocation.getArgument(2))));
        given(oauthAccountRepository
                .findByProviderAndProviderUserIdAndDeletedAtIsNull(
                        OAuthProvider.KAKAO,
                        "kakao-user-1"
                ))
                .willReturn(Optional.empty());
        given(profileTokenGenerator.generate())
                .willReturn("new-profile-token");

        LoginExchangeDecision result =
                service.exchange("valid-ticket", "browser-1");

        assertThat(result).isEqualTo(
                new LoginExchangeDecision.Onboarding("new-profile-token")
        );
        then(loginTicketStore).should()
                .claim(eq("valid-ticket"), eq("browser-1"), anyString());
        then(profileTokenStore).should()
                .save(eq("new-profile-token"), eq(identity), anyString());
        verifyNoInteractions(loginSessionIssuer, accessTokenIssuer);

        ILoggingEvent event = findAuthLoginEvent("onboarding_required");
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .doesNotContain("new-profile-token")
                .doesNotContain("user@example.com");
    }

    @Test
    void returnsExistingMemberDecisionWhenKakaoAccountIsLinked() {
        KakaoUserIdentity identity =
                new KakaoUserIdentity(
                        "kakao-user-2",
                        "kakao@example.com",
                        "https://k.kakaocdn.net/current-thumbnail.jpg"
                );
        User user = mock(User.class);
        OAuthAccount account = mock(OAuthAccount.class);

        given(loginTicketStore.claim(eq("valid-ticket"), eq("browser-2"), anyString()))
                .willAnswer(invocation -> Optional.of(new OAuthTokenClaim(identity, invocation.getArgument(2))));
        given(oauthAccountRepository
                .findByProviderAndProviderUserIdAndDeletedAtIsNull(
                        OAuthProvider.KAKAO,
                        "kakao-user-2"
                ))
                .willReturn(Optional.of(account));
        given(account.getUser()).willReturn(user);
        given(user.getUserId()).willReturn(42L);
        given(user.getEmail()).willReturn("member@example.com");
        given(user.getNickname()).willReturn("여행자");
        given(loginSessionIssuer.issue(42L))
                .willReturn(new IssuedLoginSession("sid-1", "refresh-1"));
        given(accessTokenIssuer.issue(42L, "sid-1"))
                .willReturn("access-1");

        LoginExchangeDecision result =
                service.exchange("valid-ticket", "browser-2");

        assertThat(result).isEqualTo(
                new LoginExchangeDecision.ExistingMember(
                        42L,
                        "member@example.com",
                        "여행자",
                        "access-1",
                        "refresh-1"
                )
        );
        then(loginSessionIssuer).should().issue(42L);
        then(accessTokenIssuer).should().issue(42L, "sid-1");
        then(user).should().updateProfileImageUrl(
                "https://k.kakaocdn.net/current-thumbnail.jpg"
        );
        verifyNoInteractions(profileTokenGenerator, profileTokenStore);

        ILoggingEvent event = findAuthLoginEvent("success");
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .doesNotContain("access-1")
                .doesNotContain("refresh-1")
                .doesNotContain("member@example.com");
    }

    private ILoggingEvent findAuthLoginEvent(String result) {
        return logAppender.list.stream()
                .filter(event -> "auth_login".equals(keyValue(event, "event")))
                .filter(event -> result.equals(keyValue(event, "result")))
                .findFirst()
                .orElseThrow();
    }

    private Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }
}
