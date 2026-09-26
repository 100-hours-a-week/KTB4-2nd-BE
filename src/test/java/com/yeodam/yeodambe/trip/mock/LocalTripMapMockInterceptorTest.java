package com.yeodam.yeodambe.trip.mock;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalTripMapMockInterceptorTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_사용자의_지도_요청_전에_목업_생성을_요청한다() {
        LocalTripMapMockService service = mock(LocalTripMapMockService.class);
        LocalTripMapMockInterceptor interceptor = new LocalTripMapMockInterceptor(service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "none")
                .subject("42")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        interceptor.preHandle(request, mock(), new Object());

        verify(service).createIfMissing(42L);
    }

    @Test
    void POST_요청에는_목업을_생성하지_않는다() {
        LocalTripMapMockService service = mock(LocalTripMapMockService.class);
        LocalTripMapMockInterceptor interceptor = new LocalTripMapMockInterceptor(service);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "none")
                .subject("42")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        interceptor.preHandle(request, mock(), new Object());

        verify(service, never()).createIfMissing(42L);
    }
}
