package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.security.oauth.OAuthStateGenerator;
import com.yeodam.yeodambe.user.security.oauth.OAuthStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class KakaoLoginStartService {

    private static final String KAKAO_AUTHORIZATION_URI =
            "https://kauth.kakao.com/oauth/authorize";

    private final OAuthStateGenerator stateGenerator;
    private final OAuthStateStore stateStore;
    private final String clientId;
    private final String redirectUri;

    public KakaoLoginStartService(
            OAuthStateGenerator stateGenerator,
            OAuthStateStore stateStore,
            @Value("${oauth.kakao.client-id}") String clientId,
            @Value("${oauth.kakao.redirect-uri}") String redirectUri
    ) {
        this.stateGenerator = stateGenerator;
        this.stateStore = stateStore;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
    }

    public String start(String browserContext) {
        String state = stateGenerator.generate();

        stateStore.save(state, browserContext);

        return UriComponentsBuilder
                .fromUriString(KAKAO_AUTHORIZATION_URI)
                .queryParam("response_type", "code")
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("state", "{state}")
                .encode()
                .buildAndExpand(clientId, redirectUri, state)
                .toUriString();
    }
}