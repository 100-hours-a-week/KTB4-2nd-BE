package com.yeodam.yeodambe.user.service.response;

public record ProfileRegistrationResponse(
        Long userId,
        String nickname,
        int expiresIn
) {
}