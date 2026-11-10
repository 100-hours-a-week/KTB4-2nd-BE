package com.yeodam.yeodambe.common.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestRateLimitInterceptorTest {

    private final RedisTokenBucketRateLimiter rateLimiter = mock(RedisTokenBucketRateLimiter.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 여행_목록은_인증된_JWT_사용자와_조회_그룹으로_제한한다() throws Exception {
        RequestRateLimitInterceptor interceptor = interceptor(true);
        authenticateAs("42");

        assertThat(interceptor.preHandle(request("GET", "/trips"), new MockHttpServletResponse(), new Object()))
                .isTrue();
        verify(rateLimiter).tryAcquire(RequestRateLimitGroup.TRIP_READ, 42L);
    }

    @Test
    void 여행_생성은_생성_그룹으로_제한한다() throws Exception {
        RequestRateLimitInterceptor interceptor = interceptor(true);
        authenticateAs("42");

        assertThat(interceptor.preHandle(request("POST", "/trips"), new MockHttpServletResponse(), new Object()))
                .isTrue();
        verify(rateLimiter).tryAcquire(RequestRateLimitGroup.TRIP_CREATE, 42L);
    }

    @Test
    void 제한이_꺼졌거나_대상_경로가_아니면_Redis를_호출하지_않는다() throws Exception {
        RequestRateLimitInterceptor disabled = interceptor(false);
        RequestRateLimitInterceptor enabled = interceptor(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(disabled.preHandle(request("GET", "/trips"), response, new Object())).isTrue();
        assertThat(enabled.preHandle(request("GET", "/trips/map"), response, new Object())).isTrue();
        assertThat(enabled.preHandle(request("PATCH", "/trips"), response, new Object())).isTrue();

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void 토큰이_부족하면_재시도_시간을_담은_예외를_던진다() throws Exception {
        RequestRateLimitInterceptor interceptor = interceptor(true);
        authenticateAs("42");
        when(rateLimiter.tryAcquire(RequestRateLimitGroup.TRIP_READ, 42L)).thenReturn(3L);

        assertThatThrownBy(() -> interceptor.preHandle(
                request("GET", "/trips"), new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(RequestRateLimitExceededException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(3L));
    }

    private RequestRateLimitInterceptor interceptor(boolean enabled) {
        RequestRateLimitProperties properties = new RequestRateLimitProperties(
                enabled,
                "yeodam:test",
                Duration.ofSeconds(60),
                new RequestRateLimitProperties.Bucket(5, 30),
                new RequestRateLimitProperties.Bucket(1, 5)
        );
        return new RequestRateLimitInterceptor(properties, rateLimiter);
    }

    private void authenticateAs(String userId) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(userId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, "", List.of())
        );
    }

    private MockHttpServletRequest request(String method, String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api" + servletPath);
        request.setContextPath("/api");
        request.setServletPath(servletPath);
        return request;
    }
}
