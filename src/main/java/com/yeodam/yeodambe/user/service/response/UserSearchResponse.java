package com.yeodam.yeodambe.user.service.response;

public record UserSearchResponse(
        Long userId,
        String nickname,
        String email
) {
}
