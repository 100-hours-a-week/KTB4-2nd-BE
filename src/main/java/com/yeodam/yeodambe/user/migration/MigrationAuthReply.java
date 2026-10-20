package com.yeodam.yeodambe.user.migration;

public record MigrationAuthReply(String requestId, String operation, int status,
        String accessToken, String refreshToken, String csrfToken) {}
