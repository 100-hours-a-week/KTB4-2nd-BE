package com.yeodam.yeodambe.user.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedisCsrfTokenRepository implements CsrfTokenRepository {

    private final CsrfTokenStore csrfTokenStore;

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if ("CSRF_CONTEXT".equals(cookie.getName())) {
                String token = csrfTokenStore.find(cookie.getValue());
                if (token == null) {
                    return null;
                }

                return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", token);
            }
        }

        return null;
    }

    private final CsrfTokenGenerator csrfTokenGenerator;

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return new DefaultCsrfToken(
                "X-CSRF-TOKEN",
                "_csrf",
                csrfTokenGenerator.generate()
        );
    }

    @Override
    public void saveToken(
            CsrfToken token,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return;
        }

        for (Cookie cookie : cookies) {
            if ("CSRF_CONTEXT".equals(cookie.getName())) {
                if (token == null) {
                    csrfTokenStore.delete(cookie.getValue());
                } else {
                    csrfTokenStore.save(cookie.getValue(), token.getToken());
                }
                return;
            }
        }
    }
}