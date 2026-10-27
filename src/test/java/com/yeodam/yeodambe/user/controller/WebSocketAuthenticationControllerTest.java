package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.exception.CsrfStoreUnavailableException;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenGenerator;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.csrf.RedisCsrfTokenRepository;
import com.yeodam.yeodambe.user.security.jwt.*;
import com.yeodam.yeodambe.user.security.session.LoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import com.yeodam.yeodambe.user.security.websocket.WebSocketAuthenticationSecurityConfig;
import com.yeodam.yeodambe.user.service.WebSocketAuthenticationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = WebSocketAuthenticationController.class,
        properties = "auth.websocket.internal-service-token=test-service-token"
)
@ActiveProfiles("test")
@Import({SecurityConfig.class, WebSocketAuthenticationSecurityConfig.class,
        WebSocketAuthenticationService.class, JwtConfig.class, AccessTokenIssuer.class,
        CookieAccessTokenResolver.class, ApiAuthenticationEntryPoint.class,
        ActiveLoginSessionValidator.class, CsrfAccessDeniedHandler.class,
        RedisCsrfTokenRepository.class})
class WebSocketAuthenticationControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AccessTokenIssuer tokens;
    @Autowired
    private JwtDecoder decoder;
    @Autowired
    private JwtEncoder encoder;
    @Autowired
    private JwtProperties jwtProperties;
    @MockitoBean
    private LoginSessionStore sessions;
    @MockitoBean
    private UserRepository users;
    @MockitoBean
    private CsrfTokenStore csrfTokenStore;
    @MockitoBean
    private CsrfTokenGenerator csrfTokenGenerator;

    @BeforeEach
    void setUp() {
        when(sessions.findBySid("sid-42"))
                .thenReturn(Optional.of(new LoginSession(42L, "hash")));
        when(users.findById(42L))
                .thenReturn(Optional.of(new User("websocket@test.com", "테스트")));
        when(csrfTokenStore.find("browser-context"))
                .thenReturn("existing-test-csrf-token");
    }

    @Test
    void 검증된_사용자와_기존_CSRF_값을_네_필드로_반환한다() throws Exception {
        String token = tokens.issue(42L, "sid-42");
        String expiresAt = decoder.decode(token).getExpiresAt().toString();

        mvc.perform(request(token).cookie(new Cookie("CSRF_CONTEXT", "browser-context")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.message").value("WEBSOCKET_AUTHENTICATION_SUCCESS"))
                .andExpect(jsonPath("$.data", aMapWithSize(4)))
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.sessionId").value("sid-42"))
                .andExpect(jsonPath("$.data.accessTokenExpiresAt").value(expiresAt))
                .andExpect(jsonPath("$.data.csrfToken").value("existing-test-csrf-token"));

        verify(csrfTokenStore).find("browser-context");
        verifyNoMoreInteractions(csrfTokenStore);
        verifyNoInteractions(csrfTokenGenerator);
    }

    @Test
    void CSRF_CONTEXT_쿠키가_없으면_400_대신_403을_반환한다() throws Exception {
        mvc.perform(request(tokens.issue(42L, "sid-42")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"))
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(csrfTokenGenerator);
    }

    @Test
    void 기존_CSRF_값이_없으면_새로_발급하지_않고_403을_반환한다() throws Exception {
        when(csrfTokenStore.find("browser-context")).thenReturn(null);
        mvc.perform(request(tokens.issue(42L, "sid-42"))
                        .cookie(new Cookie("CSRF_CONTEXT", "browser-context")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
        verify(csrfTokenStore).find("browser-context");
        verifyNoMoreInteractions(csrfTokenStore);
        verifyNoInteractions(csrfTokenGenerator);
    }

    @Test
    void CSRF_저장소_장애는_503으로_구분한다() throws Exception {
        when(csrfTokenStore.find("browser-context"))
                .thenThrow(new CsrfStoreUnavailableException());
        mvc.perform(request(tokens.issue(42L, "sid-42"))
                        .cookie(new Cookie("CSRF_CONTEXT", "browser-context")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 잘못된_서비스키로는_CSRF_정보를_조회할_수_없다() throws Exception {
        mvc.perform(get("/api/internal/websocket/authentication")
                        .contextPath("/api")
                        .servletPath("/internal/websocket/authentication")
                        .header("X-Internal-Service-Token", "wrong-key")
                        .cookie(new Cookie("accessToken", tokens.issue(42L, "sid-42")))
                        .cookie(new Cookie("CSRF_CONTEXT", "browser-context")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("FORBIDDEN"));
        verifyNoInteractions(csrfTokenStore);
    }

    @Test
    void 사용자_정보가_없으면_CSRF_조회_전에_401로_거절한다() throws Exception {
        when(users.findById(42L)).thenReturn(Optional.empty());
        mvc.perform(request(tokens.issue(42L, "sid-42"))
                        .cookie(new Cookie("CSRF_CONTEXT", "browser-context")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        verifyNoInteractions(csrfTokenStore);
    }

    @Test
    void 만료된_JWT는_CSRF_조회_전에_401로_거절한다() throws Exception {
        mvc.perform(request(signedToken(Instant.now().minusSeconds(600)))
                        .cookie(new Cookie("CSRF_CONTEXT", "browser-context")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        verifyNoInteractions(csrfTokenStore);
    }

    @Test
    void 만료_시각이_없는_JWT도_401로_거절한다() throws Exception {
        mvc.perform(request(signedToken(null))
                        .cookie(new Cookie("CSRF_CONTEXT", "browser-context")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        verifyNoInteractions(csrfTokenStore);
    }

    private MockHttpServletRequestBuilder request(String token) {
        return get("/api/internal/websocket/authentication")
                .contextPath("/api")
                .servletPath("/internal/websocket/authentication")
                .header("X-Internal-Service-Token", "test-service-token")
                .cookie(new Cookie("accessToken", token));
    }

    private String signedToken(Instant expiresAt) {
        var claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(Instant.now().minusSeconds(1200))
                .subject("42")
                .claim("sid", "sid-42");
        if (expiresAt != null) {
            claims.expiresAt(expiresAt);
        }
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
}
