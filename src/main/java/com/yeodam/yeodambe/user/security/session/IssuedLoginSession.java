package com.yeodam.yeodambe.user.security.session;

public record IssuedLoginSession(
        String sid,
        String refreshToken
) {
}