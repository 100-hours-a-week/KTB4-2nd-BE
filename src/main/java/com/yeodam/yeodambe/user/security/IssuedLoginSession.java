package com.yeodam.yeodambe.user.security;

public record IssuedLoginSession(
        String sid,
        String refreshToken
) {
}