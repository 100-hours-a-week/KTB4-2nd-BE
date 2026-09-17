package com.yeodam.yeodambe.user.service.response;

public record CsrfTokenResponse(
        String headerName,
        String token
) {
}