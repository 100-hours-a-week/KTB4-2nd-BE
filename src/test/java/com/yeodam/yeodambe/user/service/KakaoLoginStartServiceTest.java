package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.security.OAuthStateGenerator;
import com.yeodam.yeodambe.user.security.OAuthStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class KakaoLoginStartServiceTest {

    @Mock
    private OAuthStateGenerator stateGenerator;

    @Mock
    private OAuthStateStore stateStore;

    private KakaoLoginStartService service;

    @BeforeEach
    void setUp() {
        service = new KakaoLoginStartService(
                stateGenerator,
                stateStore,
                "test-client-id",
                "https://api.yeodam.test/auth/kakao/callback"
        );
    }

    @Test
    void createsAuthorizationUrlAndStoresState() {
        given(stateGenerator.generate()).willReturn("fixed-state");

        String authorizationUrl = service.start("browser-1");

        assertThat(authorizationUrl).isEqualTo(
                "https://kauth.kakao.com/oauth/authorize"
                        + "?response_type=code"
                        + "&client_id=test-client-id"
                        + "&redirect_uri=https%3A%2F%2Fapi.yeodam.test%2Fauth%2Fkakao%2Fcallback"
                        + "&state=fixed-state"
        );

        then(stateStore).should()
                .save("fixed-state", "browser-1");
    }
}