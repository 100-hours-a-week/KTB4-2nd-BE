package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;

public record OAuthTokenClaim(KakaoUserIdentity identity, String owner) {
}
