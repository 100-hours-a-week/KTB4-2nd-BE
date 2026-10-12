package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.common.exception.OnboardingTokenInvalidOrExpiredException;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.oauth.OAuthTokenClaim;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;
import com.yeodam.yeodambe.user.security.session.IssuedLoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;


@Service
@Slf4j
@RequiredArgsConstructor
public class ProfileRegistrationService {

    private final ProfileTokenStore profileTokenStore;
    private final UserRegistrationService userRegistrationService;
    private final LoginSessionIssuer loginSessionIssuer;
    private final AccessTokenIssuer accessTokenIssuer;

    @Transactional
    public Result register(String profileToken, String nickname) {
        long startedAt = System.nanoTime();
        String claimOwner = UUID.randomUUID().toString();
        registerTokenCompletion(profileToken, claimOwner);
        KakaoUserIdentity identity = profileTokenStore.claim(profileToken, claimOwner)
                .map(OAuthTokenClaim::identity)
                .orElseThrow(OnboardingTokenInvalidOrExpiredException::new);

        User user = userRegistrationService.register(
                identity.email(),
                nickname,
                OAuthProvider.KAKAO,
                identity.providerUserId(),
                identity.profileImageUrl()
        );

        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());

        log.atInfo()
                .addKeyValue("event", "auth_login")
                .addKeyValue("result", "success")
                .addKeyValue("duration_ms", elapsedMillis(startedAt))
                .log("신규 회원 가입과 로그인을 완료했습니다.");

        return new Result(
                user.getUserId(),
                user.getNickname(),
                accessToken,
                session.refreshToken()
        );
    }

    private void registerTokenCompletion(String token, String owner) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    switch (status) {
                        case STATUS_COMMITTED -> profileTokenStore.complete(token, owner);
                        case STATUS_ROLLED_BACK -> profileTokenStore.release(token, owner);
                        default -> logCompletionFailure();
                    }
                } catch (RuntimeException exception) {
                    logCompletionFailure();
                }
            }
        });
    }

    private void logCompletionFailure() {
        log.atError().addKeyValue("event", "auth_oauth_completion")
                .addKeyValue("result", "failure").addKeyValue("failure_stage", "profile_completion")
                .addKeyValue("error_code", "AUTH_STORE_UNAVAILABLE")
                .log("가입 토큰의 Redis 완료 처리에 실패했습니다.");
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record Result(
            Long userId,
            String nickname,
            String accessToken,
            String refreshToken
    ) {
    }
}
