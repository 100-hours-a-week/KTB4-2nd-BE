package com.yeodam.yeodambe.user.security.jwt;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CookieAccessTokenResolverTest {

    private final CookieAccessTokenResolver resolver = new CookieAccessTokenResolver();

    @Test
    void 보호된_경로에서는_액세스_토큰_쿠키를_읽는다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/users/me");
        request.setCookies(
                new Cookie("CSRF_CONTEXT", "csrf-context"),
                new Cookie("accessToken", "jwt-token")
        );

        assertThat(resolver.resolve(request)).isEqualTo("jwt-token");
    }

    @Test
    void 공개_경로에서는_유효하지_않은_액세스_토큰_쿠키를_무시한다() {
        for (String path : List.of(
                "/actuator/health",
                "/auth/csrf",
                "/auth/token/exchange",
                "/auth/token/refresh",
                "/auth/kakao/authorize",
                "/auth/kakao/callback",
                "/users/me/profile"
        )) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setServletPath(path);
            request.setCookies(new Cookie("accessToken", "stale-token"));

            assertThat(resolver.resolve(request)).as(path).isNull();
        }
    }

    @Test
    void 액세스_토큰_쿠키가_없거나_공백이면_null을_반환한다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/users/me");

        assertThat(resolver.resolve(request)).isNull();

        request.setCookies(new Cookie("accessToken", " "));

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void Authorization_헤더를_쿠키로_읽지_않는다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/users/me");
        request.addHeader("Authorization", "Bearer header-token");

        assertThat(resolver.resolve(request)).isNull();
    }
}
