package com.yeodam.yeodambe.user.service;

import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;

public sealed interface LoginExchangeDecision
        permits LoginExchangeDecision.ExistingMember,
        LoginExchangeDecision.Onboarding {

    record ExistingMember(
            Long userId,
            String email,
            String nickname,
            String accessToken,
            String refreshToken
    ) implements LoginExchangeDecision {
    }

    record Onboarding(
            String profileToken
    ) implements LoginExchangeDecision {
    }
}