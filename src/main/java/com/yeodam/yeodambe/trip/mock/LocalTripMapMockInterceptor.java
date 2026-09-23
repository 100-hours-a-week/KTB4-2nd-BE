package com.yeodam.yeodambe.trip.mock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Profile("local")
@Component
@RequiredArgsConstructor
public class LocalTripMapMockInterceptor implements HandlerInterceptor {

    private final LocalTripMapMockService localTripMapMockService;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            localTripMapMockService.createIfMissing(Long.valueOf(jwt.getSubject()));
        }

        return true;
    }
}
