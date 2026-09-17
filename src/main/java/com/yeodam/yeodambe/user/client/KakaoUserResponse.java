package com.yeodam.yeodambe.user.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoUserResponse(
        Long id,

        @JsonProperty("kakao_account")
        KakaoAccount kakaoAccount
) {

    public record KakaoAccount(
            @JsonProperty("is_email_valid")
            Boolean emailValid,

            @JsonProperty("is_email_verified")
            Boolean emailVerified,

            String email
    ) {
    }
}