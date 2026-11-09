package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.common.ratelimit.RequestRateLimitExceededException;
import com.yeodam.yeodambe.common.ratelimit.RequestRateLimitGroup;
import com.yeodam.yeodambe.common.ratelimit.RequestRateLimitUnavailableException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class RequestRateLimitExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 제한_초과는_429와_Retry_After_및_공통_응답을_반환한다() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleRequestRateLimitExceeded(
                new RequestRateLimitExceededException(RequestRateLimitGroup.TRIP_READ, 2L)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(response.getBody()).isEqualTo(
                new ApiResponse<>(ErrorMessage.REQUEST_RATE_LIMIT_EXCEEDED, null)
        );
    }

    @Test
    void Redis_장애는_503과_공통_응답을_반환한다() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleRequestRateLimitUnavailable(
                new RequestRateLimitUnavailableException(RequestRateLimitGroup.TRIP_CREATE, "RedisConnectionFailureException")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(
                new ApiResponse<>(ErrorMessage.REQUEST_RATE_LIMIT_UNAVAILABLE, null)
        );
    }
}
