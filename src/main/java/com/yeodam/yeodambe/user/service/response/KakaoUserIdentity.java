package com.yeodam.yeodambe.user.service.response;

public record KakaoUserIdentity(
        String providerUserId,
        String email
) {
}