package com.yeodam.yeodambe.integration.service.response;

public record RunpodStatusResponse(
        Boolean running,
        boolean ready
) {
}
