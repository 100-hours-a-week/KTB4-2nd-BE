package com.yeodam.yeodambe.common.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.convert.support.DefaultConversionService;
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
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void 일괄_다운로드와_말투_미리보기는_각각_별도_요청_그룹으로_제한한다() {
        RequestRateLimitInterceptor interceptor = interceptor(true);
        authenticateAs("42");
        ArgumentCaptor<RequestRateLimitGroup> groups =
                ArgumentCaptor.forClass(RequestRateLimitGroup.class);

        interceptor.preHandle(request("POST", "/attachments/bulk-download"),
                new MockHttpServletResponse(), new Object());
        interceptor.preHandle(request("POST", "/stories/9/tone-preview"),
                new MockHttpServletResponse(), new Object());

        verify(rateLimiter, times(2)).tryAcquire(groups.capture(), eq(42L));
        assertThat(groups.getAllValues()).extracting(RequestRateLimitGroup::key)
                .containsExactly("attachment-bulk-download", "story-tone-preview");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9", "09", "+9"})
    void 같은_스토리_ID의_다른_표기로_미리보기_요청_제한을_우회할_수_없다(String storyId) {
        RequestRateLimitInterceptor interceptor = interceptor(true);
        authenticateAs("42");
        when(rateLimiter.tryAcquire(RequestRateLimitGroup.STORY_TONE_PREVIEW, 42L)).thenReturn(1L);

        assertThat(DefaultConversionService.getSharedInstance()
                .convert(storyId, Long.class)).isEqualTo(9L);
        assertThatThrownBy(() -> interceptor.preHandle(
                request("POST", "/stories/" + storyId + "/tone-preview"),
                new MockHttpServletResponse(), new Object()))
                .isInstanceOf(RequestRateLimitExceededException.class);
    }

    private RequestRateLimitInterceptor interceptor(boolean enabled) {
        RequestRateLimitProperties properties = new RequestRateLimitProperties(
                enabled,
                "yeodam:test",
                Duration.ofSeconds(60),
                new RequestRateLimitProperties.Bucket(5, 30),
                new RequestRateLimitProperties.Bucket(1, 5),
                null,
                null
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
