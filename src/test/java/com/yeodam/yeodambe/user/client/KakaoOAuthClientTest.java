package com.yeodam.yeodambe.user.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.HttpHeaders;
import com.yeodam.yeodambe.user.exception.KakaoAuthenticationFailedException;
import com.yeodam.yeodambe.user.exception.OAuthProviderUnavailableException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoOAuthClientTest {

    private MockRestServiceServer server;
    private KakaoOAuthClient kakaoOAuthClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();

        server = MockRestServiceServer
                .bindTo(builder)
                .build();

        kakaoOAuthClient = new KakaoOAuthClient(
                builder,
                "test-client-id",
                "test-client-secret",
                "https://api.yeodam.test/auth/kakao/callback"
        );
    }

    @Test
    void exchangesAuthorizationCodeForToken() {
        MultiValueMap<String, String> expectedForm =
                new LinkedMultiValueMap<>();

        expectedForm.add("grant_type", "authorization_code");
        expectedForm.add("client_id", "test-client-id");
        expectedForm.add("client_secret", "test-client-secret");
        expectedForm.add(
                "redirect_uri",
                "https://api.yeodam.test/auth/kakao/callback"
        );
        expectedForm.add("code", "test-code");

        server.expect(
                        once(),
                        requestTo("https://kauth.kakao.com/oauth/token")
                )
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_FORM_URLENCODED
                ))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess(
                        """
                        {
                          "access_token": "kakao-access-token",
                          "token_type": "bearer",
                          "expires_in": 21599
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        KakaoTokenResponse response =
                kakaoOAuthClient.exchangeToken("test-code");

        assertThat(response.accessToken())
                .isEqualTo("kakao-access-token");
        assertThat(response.tokenType())
                .isEqualTo("bearer");
        assertThat(response.expiresIn())
                .isEqualTo(21599);

        server.verify();
    }

    @Test
    void getsKakaoUserWithAccessToken() {
        server.expect(
                        once(),
                        requestTo("https://kapi.kakao.com/v2/user/me")
                )
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer kakao-access-token"
                ))
                .andRespond(withSuccess(
                        """
                        {
                          "id": 123456789,
                          "kakao_account": {
                            "is_email_valid": true,
                            "is_email_verified": true,
                            "email": "member@example.com",
                            "profile": {
                              "thumbnail_image_url": "https://k.kakaocdn.net/profile-thumb.jpg"
                            }
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        KakaoUserResponse response =
                kakaoOAuthClient.getUser("kakao-access-token");

        assertThat(response.id())
                .isEqualTo(123456789L);
        assertThat(response.kakaoAccount().emailValid())
                .isTrue();
        assertThat(response.kakaoAccount().emailVerified())
                .isTrue();
        assertThat(response.kakaoAccount().email())
                .isEqualTo("member@example.com");
        assertThat(response.kakaoAccount().profile().thumbnailImageUrl())
                .isEqualTo("https://k.kakaocdn.net/profile-thumb.jpg");

        server.verify();
    }

    @Test
    void throwsAuthenticationFailedWhenTokenRequestIsRejected() {
        server.expect(
                        once(),
                        requestTo("https://kauth.kakao.com/oauth/token")
                )
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(
                () -> kakaoOAuthClient.exchangeToken("invalid-code")
        ).isInstanceOf(KakaoAuthenticationFailedException.class);

        server.verify();
    }

    @Test
    void throwsProviderUnavailableWhenUserInfoServiceFails() {
        server.expect(
                        once(),
                        requestTo("https://kapi.kakao.com/v2/user/me")
                )
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(
                () -> kakaoOAuthClient.getUser("kakao-access-token")
        ).isInstanceOf(OAuthProviderUnavailableException.class);

        server.verify();
    }
}
