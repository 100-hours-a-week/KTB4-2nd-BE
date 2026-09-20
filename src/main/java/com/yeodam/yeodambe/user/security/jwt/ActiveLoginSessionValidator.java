package com.yeodam.yeodambe.user.security.jwt;

import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@Component
@RequiredArgsConstructor
public class ActiveLoginSessionValidator implements OAuth2TokenValidator<Jwt> {

    private final LoginSessionStore loginSessionStore;
    private final UserRepository userRepository;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        if (sid == null || sid.isBlank()) {
            return invalidToken();
        }

        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            return invalidToken();
        }

        boolean sessionMatches;

        try {
            sessionMatches = loginSessionStore.findBySid(sid)
                    .map(session -> userId.equals(session.userId()))
                    .orElse(false);
        } catch (RedisConnectionFailureException e) {
            OAuth2Error error = new OAuth2Error(
                    "auth_store_unavailable",
                    "인증 저장소에 연결할 수 없습니다.",
                    null
            );

            throw new OAuth2AuthenticationException(
                    error,
                    e
            );
        }
        if (!sessionMatches) {
            return invalidToken();
        }

        boolean activeUser = userRepository.findById(userId)
                .filter(user -> user.getDeletedAt() == null)
                .isPresent();

        return activeUser
                ? OAuth2TokenValidatorResult.success()
                : invalidToken();
    }

    private OAuth2TokenValidatorResult invalidToken() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "로그인 세션이 유효하지 않습니다.", null)
        );
    }
}
