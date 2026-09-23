package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.exception.LoginTicketInvalidOrExpiredException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

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

    @BeforeEach
    void setUp() {
        service = new LoginTicketExchangeService(
                loginTicketStore,
                oauthAccountRepository,
                profileTokenGenerator,
                profileTokenStore,
                loginSessionIssuer,
                accessTokenIssuer
        );
    }

    @Test
    void rejectsExpiredOrAlreadyConsumedTicket() {
        given(loginTicketStore.consume("expired-ticket", "browser-1"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.exchange("expired-ticket", "browser-1"))
                .isInstanceOf(LoginTicketInvalidOrExpiredException.class);

        then(loginTicketStore).should()
                .consume("expired-ticket", "browser-1");
        verifyNoInteractions(oauthAccountRepository, loginSessionIssuer, accessTokenIssuer);
    }

    @Test
    void returnsOnboardingDecisionWhenKakaoAccountIsNotLinked() {
        KakaoUserIdentity identity =
                new KakaoUserIdentity("kakao-user-1", "user@example.com");
        given(loginTicketStore.consume("valid-ticket", "browser-1"))
                .willReturn(Optional.of(identity));
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
                .consume("valid-ticket", "browser-1");
        then(profileTokenStore).should()
                .save("new-profile-token", identity);
        verifyNoInteractions(loginSessionIssuer, accessTokenIssuer);
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

        given(loginTicketStore.consume("valid-ticket", "browser-2"))
                .willReturn(Optional.of(identity));
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
    }
}
