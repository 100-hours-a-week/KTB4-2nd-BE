package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.client.KakaoOAuthClient;
import com.yeodam.yeodambe.user.client.KakaoTokenResponse;
import com.yeodam.yeodambe.user.client.KakaoUserResponse;
import com.yeodam.yeodambe.user.exception.KakaoAuthenticationFailedException;
import com.yeodam.yeodambe.user.exception.LoginTicketIssueFailedException;
import com.yeodam.yeodambe.user.exception.OAuthStateInvalidOrExpiredException;
import com.yeodam.yeodambe.user.security.oauth.OAuthStateStore;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import com.yeodam.yeodambe.user.security.oauth.LoginTicketGenerator;
import com.yeodam.yeodambe.user.security.oauth.LoginTicketStore;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

@Service
public class KakaoLoginCallbackService {
    private final OAuthStateStore stateStore;
    private final KakaoOAuthClient kakaoOAuthClient;
    private final LoginTicketGenerator loginTicketGenerator;
    private final LoginTicketStore loginTicketStore;

    public KakaoLoginCallbackService(
            OAuthStateStore stateStore,
            KakaoOAuthClient kakaoOAuthClient,
            LoginTicketGenerator loginTicketGenerator,
            LoginTicketStore loginTicketStore
    ) {
        this.stateStore = stateStore;
        this.kakaoOAuthClient = kakaoOAuthClient;
        this.loginTicketGenerator = loginTicketGenerator;
        this.loginTicketStore = loginTicketStore;
    }

    public String issueLoginTicket(
            String code,
            String state,
            String browserContext
    ) {
        if (state == null
                || state.isBlank()
                || browserContext == null
                || browserContext.isBlank()
                || !stateStore.consume(state, browserContext)) {
            throw new OAuthStateInvalidOrExpiredException();
        }

        if (code == null || code.isBlank()) {
            throw new KakaoAuthenticationFailedException();
        }

        KakaoTokenResponse token = kakaoOAuthClient.exchangeToken(code);

        if (token == null
                || token.accessToken() == null
                || token.accessToken().isBlank()) {
            throw new KakaoAuthenticationFailedException();
        }

        KakaoUserResponse user = kakaoOAuthClient.getUser(token.accessToken());

        if (user == null
                || user.id() == null
                || user.kakaoAccount() == null
                || !Boolean.TRUE.equals(user.kakaoAccount().emailValid())
                || !Boolean.TRUE.equals(user.kakaoAccount().emailVerified())
                || user.kakaoAccount().email() == null
                || user.kakaoAccount().email().isBlank()) {
            throw new KakaoAuthenticationFailedException();
        }

        String profileImageUrl = user.kakaoAccount().profile() == null
                ? null
                : user.kakaoAccount().profile().thumbnailImageUrl();

        KakaoUserIdentity identity = new KakaoUserIdentity(
                user.id().toString(),
                user.kakaoAccount().email(),
                profileImageUrl
        );

        try {
            String loginTicket = loginTicketGenerator.generate();

            loginTicketStore.save(
                    loginTicket,
                    identity,
                    browserContext
            );

            return loginTicket;
        } catch (DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LoginTicketIssueFailedException(e);
        }
    }
}
