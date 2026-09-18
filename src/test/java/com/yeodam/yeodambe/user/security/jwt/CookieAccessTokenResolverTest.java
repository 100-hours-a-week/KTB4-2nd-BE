package com.yeodam.yeodambe.user.security.jwt;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CookieAccessTokenResolverTest {

    private final CookieAccessTokenResolver resolver = new CookieAccessTokenResolver();

    @Test
    void readsAccessTokenCookieOnProtectedPath() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/users/me");
        request.setCookies(
                new Cookie("CSRF_CONTEXT", "csrf-context"),
                new Cookie("accessToken", "jwt-token")
        );

        assertThat(resolver.resolve(request)).isEqualTo("jwt-token");
    }

    @Test
    void ignoresStaleAccessTokenCookieOnPublicPaths() {
        for (String path : List.of(
                "/actuator/health",
                "/auth/csrf",
                "/auth/token/exchange",
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
    void returnsNullWhenAccessTokenCookieIsMissingOrBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/users/me");

        assertThat(resolver.resolve(request)).isNull();

        request.setCookies(new Cookie("accessToken", " "));

        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void doesNotReadAuthorizationHeaderAsCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/users/me");
        request.addHeader("Authorization", "Bearer header-token");

        assertThat(resolver.resolve(request)).isNull();
    }
}
