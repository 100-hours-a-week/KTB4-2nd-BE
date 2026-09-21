package com.yeodam.yeodambe.user.security.session;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.yeodam.yeodambe.user.security.TokenHasher;

@Component
@RequiredArgsConstructor
public class LoginSessionIssuer {

    private final SessionIdGenerator sessionIdGenerator;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final LoginSessionStore loginSessionStore;
    private final TokenHasher tokenHasher;

    public IssuedLoginSession issue(Long userId) {
        String sid = sessionIdGenerator.generate();
        String refreshToken = refreshTokenGenerator.generate();
        String refreshTokenHash =
                tokenHasher.hash(refreshToken);

        loginSessionStore.save(
                sid,
                userId,
                refreshTokenHash
        );

        return new IssuedLoginSession(
                sid,
                refreshToken
        );
    }
}