package com.yeodam.yeodambe.user.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoginSessionIssuer {

    private final SessionIdGenerator sessionIdGenerator;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final RefreshTokenHasher refreshTokenHasher;
    private final LoginSessionStore loginSessionStore;

    public IssuedLoginSession issue(Long userId) {
        String sid = sessionIdGenerator.generate();
        String refreshToken = refreshTokenGenerator.generate();
        String refreshTokenHash =
                refreshTokenHasher.hash(refreshToken);

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