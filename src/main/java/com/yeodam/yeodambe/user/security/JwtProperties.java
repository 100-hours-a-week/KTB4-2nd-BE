package com.yeodam.yeodambe.user.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        String audience,
        Duration accessTokenTtl
) {
}