package com.yeodam.yeodambe.common.exception;

public class OAuthProviderUnavailableException extends RuntimeException {

    public OAuthProviderUnavailableException() {
        super(CommonExceptionMessage.OAUTH_PROVIDER_UNAVAILABLE.message());
    }
}