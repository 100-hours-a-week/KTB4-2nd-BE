package com.yeodam.yeodambe.user.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import com.yeodam.yeodambe.user.exception.KakaoAuthenticationFailedException;
import com.yeodam.yeodambe.user.exception.OAuthProviderUnavailableException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;


@Component
public class KakaoOAuthClient {

    private static final String TOKEN_URI =
            "https://kauth.kakao.com/oauth/token";

    private static final String USER_INFO_URI =
            "https://kapi.kakao.com/v2/user/me";
    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public KakaoOAuthClient(
            RestClient.Builder restClientBuilder,
            @Value("${oauth.kakao.client-id}")
            String clientId,
            @Value("${oauth.kakao.client-secret}")
            String clientSecret,
            @Value("${oauth.kakao.redirect-uri}")
            String redirectUri
    ) {
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    public KakaoTokenResponse exchangeToken(String code) {
        MultiValueMap<String, String> formData =
                new LinkedMultiValueMap<>();

        formData.add("grant_type", "authorization_code");
        formData.add("client_id", clientId);
        formData.add("client_secret", clientSecret);
        formData.add("redirect_uri", redirectUri);
        formData.add("code", code);

        try {
            return restClient
                    .post()
                    .uri(TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .body(KakaoTokenResponse.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new KakaoAuthenticationFailedException();
            }

            throw new OAuthProviderUnavailableException();
        } catch (ResourceAccessException e) {
            throw new OAuthProviderUnavailableException();
        }
    }

    public KakaoUserResponse getUser(String accessToken) {
        try {
            return restClient
                    .get()
                    .uri(USER_INFO_URI)
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + accessToken
                    )
                    .retrieve()
                    .body(KakaoUserResponse.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new KakaoAuthenticationFailedException();
            }

            throw new OAuthProviderUnavailableException();
        } catch (ResourceAccessException e) {
            throw new OAuthProviderUnavailableException();
        }
    }
}
