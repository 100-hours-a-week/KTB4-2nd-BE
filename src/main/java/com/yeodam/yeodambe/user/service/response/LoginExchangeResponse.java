package com.yeodam.yeodambe.user.service.response;

public sealed interface LoginExchangeResponse
        permits LoginExchangeResponse.ExistingMember, LoginExchangeResponse.Onboarding {

    record ExistingMember(
            int expiresIn,
            boolean requiresNickname,
            User user
    ) implements LoginExchangeResponse {
    }

    record Onboarding(
            int expiresIn,
            boolean requiresNickname
    ) implements LoginExchangeResponse {
    }

    record User(Long userId, String email, String nickname) {
    }
}