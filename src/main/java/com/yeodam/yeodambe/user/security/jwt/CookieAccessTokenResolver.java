package com.yeodam.yeodambe.user.security.jwt;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CookieAccessTokenResolver implements BearerTokenResolver {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/actuator/health",
            "/auth/csrf",
            "/auth/token/exchange",
            "/auth/kakao/authorize",
            "/auth/kakao/callback",
            "/users/me/profile",
            "/auth/token/refresh"
    );

    @Override
    public String resolve(HttpServletRequest request) {
        if (PUBLIC_PATHS.contains(request.getServletPath())) {
            return null;
        }

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if ("accessToken".equals(cookie.getName())) {
                String token = cookie.getValue();
                return token == null || token.isBlank() ? null : token;
            }
        }

        return null;
    }
}