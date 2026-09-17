package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.user.entity.OAuthAccount;
import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.OAuthAccountRepository;
import com.yeodam.yeodambe.user.security.LoginTicketStore;
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

    private LoginTicketExchangeService service;

    @BeforeEach
    void setUp() {
        service = new LoginTicketExchangeService(
                loginTicketStore,
                oauthAccountRepository
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
        verifyNoInteractions(oauthAccountRepository);
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

        LoginExchangeDecision result =
                service.exchange("valid-ticket", "browser-1");

        assertThat(result).isEqualTo(
                new LoginExchangeDecision.Onboarding(identity)
        );
        then(loginTicketStore).should()
                .consume("valid-ticket", "browser-1");
    }

    @Test
    void returnsExistingMemberDecisionWhenKakaoAccountIsLinked() {
        KakaoUserIdentity identity =
                new KakaoUserIdentity("kakao-user-2", "kakao@example.com");
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

        LoginExchangeDecision result =
                service.exchange("valid-ticket", "browser-2");

        assertThat(result).isEqualTo(
                new LoginExchangeDecision.ExistingMember(
                        42L,
                        "member@example.com",
                        "여행자"
                )
        );
    }
}
