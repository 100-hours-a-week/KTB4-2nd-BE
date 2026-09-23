package com.yeodam.yeodambe.user.service.response;

public record KakaoUserIdentity(
        String providerUserId,
        String email,
        String profileImageUrl
) {
    public KakaoUserIdentity(String providerUserId, String email) {
        this(providerUserId, email, null);
    }
}