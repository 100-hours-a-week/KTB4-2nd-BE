package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.entity.OAuthProvider;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.exception.OnboardingTokenInvalidOrExpiredException;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.oauth.ProfileTokenStore;
import com.yeodam.yeodambe.user.security.session.IssuedLoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionIssuer;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        KakaoUserIdentity identity = profileTokenStore.find(profileToken)
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

        profileTokenStore.delete(profileToken);
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
