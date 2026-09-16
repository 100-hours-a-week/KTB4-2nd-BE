package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.client.KakaoOAuthClient;
import com.yeodam.yeodambe.user.exception.OAuthStateInvalidOrExpiredException;
import com.yeodam.yeodambe.user.security.OAuthStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.yeodam.yeodambe.user.client.KakaoTokenResponse;
import com.yeodam.yeodambe.user.client.KakaoUserResponse;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class KakaoLoginCallbackServiceTest {

    @Mock
    private OAuthStateStore stateStore;

    @Mock
    private KakaoOAuthClient kakaoOAuthClient;

    private KakaoLoginCallbackService service;

    @BeforeEach
    void setUp() {
        service = new KakaoLoginCallbackService(
                stateStore,
                kakaoOAuthClient
        );
    }

    @Test
    void rejectsInvalidStateBeforeCallingKakao() {
        given(stateStore.consume("invalid-state", "browser-1"))
                .willReturn(false);

        assertThatThrownBy(() ->
                service.authenticate(
                        "authorization-code",
                        "invalid-state",
                        "browser-1"
                )
        ).isInstanceOf(OAuthStateInvalidOrExpiredException.class);

        verifyNoInteractions(kakaoOAuthClient);
    }

    @Test
    void returnsIdentityAfterValidKakaoAuthentication() {
        given(stateStore.consume("valid-state", "browser-1"))
                .willReturn(true);

        given(kakaoOAuthClient.exchangeToken("authorization-code"))
                .willReturn(new KakaoTokenResponse(
                        "kakao-access-token",
                        "bearer",
                        3600
                ));

        given(kakaoOAuthClient.getUser("kakao-access-token"))
                .willReturn(new KakaoUserResponse(
                        123456789L,
                        new KakaoUserResponse.KakaoAccount(
                                true,
                                true,
                                "member@example.com"
                        )
                ));

        KakaoUserIdentity identity = service.authenticate(
                "authorization-code",
                "valid-state",
                "browser-1"
        );

        assertThat(identity.providerUserId())
                .isEqualTo("123456789");
        assertThat(identity.email())
                .isEqualTo("member@example.com");

        then(stateStore).should()
                .consume("valid-state", "browser-1");
        then(kakaoOAuthClient).should()
                .exchangeToken("authorization-code");
        then(kakaoOAuthClient).should()
                .getUser("kakao-access-token");
    }
}
