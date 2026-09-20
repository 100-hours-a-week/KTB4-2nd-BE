package com.yeodam.yeodambe.user.service.response;

public record CurrentUserResponse(
        Long userId,
        String email,
        String nickname,
        String oauthProvider,
        boolean oauthConnected
) {
}