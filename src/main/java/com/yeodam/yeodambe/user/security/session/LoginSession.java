package com.yeodam.yeodambe.user.security.session;

public record LoginSession(
        Long userId,
        String refreshTokenHash
) {
}