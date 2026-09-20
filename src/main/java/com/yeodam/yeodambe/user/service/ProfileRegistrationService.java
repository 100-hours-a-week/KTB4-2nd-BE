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
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProfileRegistrationService {

    private final ProfileTokenStore profileTokenStore;
    private final UserRegistrationService userRegistrationService;
    private final LoginSessionIssuer loginSessionIssuer;
    private final AccessTokenIssuer accessTokenIssuer;

    public Result register(String profileToken, String nickname) {
        KakaoUserIdentity identity = profileTokenStore.find(profileToken)
                .orElseThrow(OnboardingTokenInvalidOrExpiredException::new);

        User user = userRegistrationService.register(
                identity.email(),
                nickname,
                OAuthProvider.KAKAO,
                identity.providerUserId()
        );

        IssuedLoginSession session = loginSessionIssuer.issue(user.getUserId());
        String accessToken = accessTokenIssuer.issue(user.getUserId(), session.sid());

        profileTokenStore.delete(profileToken);

        return new Result(
                user.getUserId(),
                user.getNickname(),
                accessToken,
                session.refreshToken()
        );
    }

    public record Result(
            Long userId,
            String nickname,
            String accessToken,
            String refreshToken
    ) {
    }
}