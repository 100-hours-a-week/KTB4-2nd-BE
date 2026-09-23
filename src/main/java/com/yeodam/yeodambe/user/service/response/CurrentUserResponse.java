package com.yeodam.yeodambe.user.service.response;

public record CurrentUserResponse(
        Long userId,
        String email,
        String nickname,
        String profileImageUrl,
        String oauthProvider,
        boolean oauthConnected
) {
    public CurrentUserResponse(
            Long userId,
            String email,
            String nickname,
            String oauthProvider,
            boolean oauthConnected
    ) {
        this(
                userId,
                email,
                nickname,
                null,
                oauthProvider,
                oauthConnected
        );
    }
}