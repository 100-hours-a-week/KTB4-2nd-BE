package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.exception.InvalidNicknameException;
import com.yeodam.yeodambe.user.exception.OnboardingTokenInvalidOrExpiredException;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;
import com.yeodam.yeodambe.user.security.session.IssuedLoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ProfileRegistrationServiceTest {

    @Mock
    private ProfileTokenStore profileTokenStore;
    @Mock
    private UserRegistrationService userRegistrationService;
    @Mock
    private LoginSessionIssuer loginSessionIssuer;
    @Mock
    private AccessTokenIssuer accessTokenIssuer;

    private ProfileRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new ProfileRegistrationService(
                profileTokenStore,
                userRegistrationService,
                loginSessionIssuer,
                accessTokenIssuer
        );
    }

    @Test
    void registersMemberAndDeletesProfileTokenAfterIssuingSession() {
        KakaoUserIdentity identity =
                new KakaoUserIdentity("kakao-1", "member@example.com");
        User user = mock(User.class);
        given(profileTokenStore.find("profile-1"))
                .willReturn(Optional.of(identity));
        given(userRegistrationService.register(
                "member@example.com", "여행자", OAuthProvider.KAKAO, "kakao-1"
        )).willReturn(user);
        given(user.getUserId()).willReturn(42L);
        given(user.getNickname()).willReturn("여행자");
        given(loginSessionIssuer.issue(42L))
                .willReturn(new IssuedLoginSession("sid-1", "refresh-1"));
        given(accessTokenIssuer.issue(42L, "sid-1"))
                .willReturn("access-1");

        ProfileRegistrationService.Result result =
                service.register("profile-1", "여행자");

        assertThat(result).isEqualTo(new ProfileRegistrationService.Result(
                42L, "여행자", "access-1", "refresh-1"
        ));
        InOrder order = inOrder(
                profileTokenStore, userRegistrationService,
                loginSessionIssuer, accessTokenIssuer
        );
        order.verify(profileTokenStore).find("profile-1");
        order.verify(userRegistrationService).register(
                "member@example.com", "여행자", OAuthProvider.KAKAO, "kakao-1"
        );
        order.verify(loginSessionIssuer).issue(42L);
        order.verify(accessTokenIssuer).issue(42L, "sid-1");
        order.verify(profileTokenStore).delete("profile-1");
    }

    @Test
    void rejectsExpiredTokenBeforeCreatingMemberOrSession() {
        given(profileTokenStore.find("expired"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.register("expired", "여행자"))
                .isInstanceOf(OnboardingTokenInvalidOrExpiredException.class);

        then(profileTokenStore).should().find("expired");
        verifyNoInteractions(userRegistrationService, loginSessionIssuer, accessTokenIssuer);
        verifyNoMoreInteractions(profileTokenStore);
    }

    @Test
    void keepsProfileTokenWhenRegistrationFails() {
        KakaoUserIdentity identity =
                new KakaoUserIdentity("kakao-1", "member@example.com");
        given(profileTokenStore.find("profile-1"))
                .willReturn(Optional.of(identity));
        given(userRegistrationService.register(
                "member@example.com", "잘못 된닉네임", OAuthProvider.KAKAO, "kakao-1"
        )).willThrow(new InvalidNicknameException());

        assertThatThrownBy(() -> service.register("profile-1", "잘못 된닉네임"))
                .isInstanceOf(InvalidNicknameException.class);

        then(profileTokenStore).should().find("profile-1");
        verifyNoInteractions(loginSessionIssuer, accessTokenIssuer);
        verifyNoMoreInteractions(profileTokenStore);
    }
}
