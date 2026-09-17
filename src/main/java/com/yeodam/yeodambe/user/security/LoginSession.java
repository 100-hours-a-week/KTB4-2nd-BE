package com.yeodam.yeodambe.user.security;

public record LoginSession(
        Long userId,
        String refreshTokenHash
) {
}