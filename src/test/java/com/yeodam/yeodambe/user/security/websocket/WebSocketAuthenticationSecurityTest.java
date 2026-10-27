package com.yeodam.yeodambe.user.security.websocket;

import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenGenerator;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.csrf.RedisCsrfTokenRepository;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.jwt.ActiveLoginSessionValidator;
import com.yeodam.yeodambe.user.security.jwt.ApiAuthenticationEntryPoint;
import com.yeodam.yeodambe.user.security.jwt.CookieAccessTokenResolver;
import com.yeodam.yeodambe.user.security.jwt.JwtConfig;
import com.yeodam.yeodambe.user.security.session.LoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = WebSocketAuthenticationSecurityTest.ProbeController.class,
        properties = "auth.websocket.internal-service-token=test-service-token"
)
@ActiveProfiles("test")
@Import({SecurityConfig.class, WebSocketAuthenticationSecurityConfig.class,
        JwtConfig.class, AccessTokenIssuer.class, CookieAccessTokenResolver.class,
        ApiAuthenticationEntryPoint.class, ActiveLoginSessionValidator.class,
        CsrfAccessDeniedHandler.class, RedisCsrfTokenRepository.class,
        WebSocketAuthenticationSecurityTest.ProbeController.class})
class WebSocketAuthenticationSecurityTest {

    private static final String INTERNAL_PATH = "/internal/websocket/authentication";
    private static final String SERVICE_HEADER = "X-Internal-Service-Token";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AccessTokenIssuer tokens;
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
        when(csrfTokenGenerator.generate()).thenReturn("generated-test-csrf-token");
        when(sessions.findBySid("sid-42"))
                .thenReturn(Optional.of(new LoginSession(42L, "hash")));
        when(users.findById(42L))
                .thenReturn(Optional.of(new User("websocket@test.com", "테스트")));
    }

    @Test
    void 서비스키와_사용자_인증이_유효하면_테스트_컨트롤러에_도달한다() throws Exception {
        mvc.perform(internalGet().header(SERVICE_HEADER, "test-service-token")
                        .cookie(cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string("42"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(sessions).findBySid("sid-42");
        verify(users).findById(42L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "wrong-service-token")
    void 서비스키가_없거나_틀리면_사용자_저장소_조회_전에_거절한다(String key) throws Exception {
        var request = internalGet().cookie(cookie());
        if (key != null) {
            request.header(SERVICE_HEADER, key);
        }
        mvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("FORBIDDEN"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(sessions, users);
    }

    @Test
    void 서비스키만_있고_사용자_쿠키가_없으면_401이다() throws Exception {
        mvc.perform(internalGet().header(SERVICE_HEADER, "test-service-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
    }

    @Test
    void 잘못된_JWT는_401이다() throws Exception {
        mvc.perform(internalGet().header(SERVICE_HEADER, "test-service-token")
                        .cookie(new Cookie("accessToken", "invalid-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        verifyNoInteractions(sessions, users);
    }

    @Test
    void 폐기된_로그인_세션은_401이다() throws Exception {
        when(sessions.findBySid("sid-42")).thenReturn(Optional.empty());
        mvc.perform(internalGet().header(SERVICE_HEADER, "test-service-token")
                        .cookie(cookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
    }

    @Test
    void 인증_저장소_장애는_503이다() throws Exception {
        when(sessions.findBySid("sid-42"))
                .thenThrow(new DataAccessResourceFailureException("test store unavailable"));
        mvc.perform(internalGet().header(SERVICE_HEADER, "test-service-token")
                        .cookie(cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"));
    }

    @Test
    void 다른_내부_경로는_인증되어도_거절한다() throws Exception {
        mvc.perform(get("/api/internal/websocket/unknown").contextPath("/api")
                        .servletPath("/internal/websocket/unknown")
                        .header(SERVICE_HEADER, "test-service-token").cookie(cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("FORBIDDEN"));
    }

    @Test
    void 내부_인증_POST는_CSRF와_인증을_갖추어도_거절한다() throws Exception {
        mvc.perform(post("/api" + INTERNAL_PATH).contextPath("/api")
                        .servletPath(INTERNAL_PATH).with(csrf())
                        .header(SERVICE_HEADER, "test-service-token").cookie(cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("FORBIDDEN"));
    }

    @Test
    void 일반_API는_서비스키_없이_기존_사용자_인증을_사용한다() throws Exception {
        mvc.perform(get("/test/websocket-general-probe").cookie(cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string("42"));
    }

    @Test
    void 일반_POST의_CSRF_보호는_유지한다() throws Exception {
        mvc.perform(post("/test/websocket-general-probe").cookie(cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
    }

    private MockHttpServletRequestBuilder internalGet() {
        return get("/api" + INTERNAL_PATH).contextPath("/api").servletPath(INTERNAL_PATH);
    }

    private Cookie cookie() {
        return new Cookie("accessToken", tokens.issue(42L, "sid-42"));
    }

    @RestController
    static class ProbeController {
        @GetMapping({INTERNAL_PATH, "/test/websocket-general-probe"})
        String getProbe(@AuthenticationPrincipal Jwt jwt) {
            return jwt.getSubject();
        }

        @PostMapping({INTERNAL_PATH, "/test/websocket-general-probe"})
        String postProbe() {
            return "unexpected";
        }
    }
}
