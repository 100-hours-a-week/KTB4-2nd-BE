package com.yeodam.yeodambe.trip.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocalTripMapMockInterceptorTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증된_사용자의_지도_요청_전에_목업_생성을_요청한다() {
        LocalTripMapMockService service = mock(LocalTripMapMockService.class);
        LocalTripMapMockInterceptor interceptor = new LocalTripMapMockInterceptor(service);
        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "none")
                .subject("42")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        interceptor.preHandle(mock(), mock(), new Object());

        verify(service).createIfMissing(42L);
    }
}
