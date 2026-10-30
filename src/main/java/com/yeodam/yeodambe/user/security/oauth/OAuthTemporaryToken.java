package com.yeodam.yeodambe.user.security.oauth;

import com.yeodam.yeodambe.user.exception.UserInternalErrorMessage;
import com.yeodam.yeodambe.user.service.response.KakaoUserIdentity;

record OAuthTemporaryToken(KakaoUserIdentity identity, String browserContextHash,
                           long expiresAt, String claimOwner, String issuanceOwner) {
    OAuthTemporaryToken {
        if (identity == null || identity.providerUserId() == null || identity.providerUserId().isBlank()
                || identity.email() == null || identity.email().isBlank() || expiresAt <= 0
                || issuanceOwner == null || issuanceOwner.isBlank()
                || (claimOwner != null && claimOwner.isBlank())
                || (browserContextHash != null && !browserContextHash.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(UserInternalErrorMessage.OAUTH_TEMPORARY_DATA_INVALID.message());
        }
    }

    OAuthTemporaryToken withClaimOwner(String owner) {
        return new OAuthTemporaryToken(identity, browserContextHash, expiresAt, owner, issuanceOwner);
    }
}
