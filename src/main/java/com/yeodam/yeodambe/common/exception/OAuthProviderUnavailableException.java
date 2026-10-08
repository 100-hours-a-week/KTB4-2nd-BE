package com.yeodam.yeodambe.common.exception;

public class OAuthProviderUnavailableException extends RuntimeException {

    public OAuthProviderUnavailableException() {
        super("OAuth 제공자 서비스를 사용할 수 없습니다.");
    }
}