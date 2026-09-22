package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.CsrfAccessDeniedHandler;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenGenerator;
import com.yeodam.yeodambe.user.security.csrf.CsrfTokenStore;
import com.yeodam.yeodambe.user.security.csrf.RdbCsrfTokenRepository;
import com.yeodam.yeodambe.user.security.jwt.AccessTokenIssuer;
import com.yeodam.yeodambe.user.security.jwt.ActiveLoginSessionValidator;
import com.yeodam.yeodambe.user.security.jwt.ApiAuthenticationEntryPoint;
import com.yeodam.yeodambe.user.security.jwt.CookieAccessTokenResolver;
import com.yeodam.yeodambe.user.security.jwt.JwtConfig;
import com.yeodam.yeodambe.user.service.LogoutService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LogoutController.class)
@ActiveProfiles("test")
@Import({
        SecurityConfig.class,
        JwtConfig.class,
        AccessTokenIssuer.class,
        CookieAccessTokenResolver.class,
        ApiAuthenticationEntryPoint.class,
        CsrfAccessDeniedHandler.class,
        RdbCsrfTokenRepository.class
})
class LogoutSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @MockitoBean
    private LogoutService logoutService;

    @MockitoBean
    private ActiveLoginSessionValidator activeLoginSessionValidator;

    @MockitoBean
    private CsrfTokenStore csrfTokenStore;

    @MockitoBean
    private CsrfTokenGenerator csrfTokenGenerator;

    @BeforeEach
    void allowSessionValidation() {
        given(activeLoginSessionValidator.validate(any(Jwt.class)))
                .willReturn(OAuth2TokenValidatorResult.success());
    }

    @Test
    void validAccessTokenAndCsrfTokenAllowLogout() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("logout-browser"))
                .willReturn("csrf-token");

        mockMvc.perform(post("/auth/logout")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "logout-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNoContent());

        then(logoutService).should().logout("sid-42");
    }

    @Test
    void missingAccessTokenReturnsUnauthorized() throws Exception {
        given(csrfTokenStore.find("missing-access-browser"))
                .willReturn("csrf-token");

        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie(
                                "CSRF_CONTEXT",
                                "missing-access-browser"
                        ))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(logoutService).should(never()).logout(any());
    }

    @Test
    void invalidCsrfTokenReturnsForbidden() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("invalid-csrf-browser"))
                .willReturn("csrf-token");

        mockMvc.perform(post("/auth/logout")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie(
                                        "CSRF_CONTEXT",
                                        "invalid-csrf-browser"
                                )
                        )
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("CSRF_TOKEN_INVALID"));

        then(logoutService).should(never()).logout(any());
    }
}
