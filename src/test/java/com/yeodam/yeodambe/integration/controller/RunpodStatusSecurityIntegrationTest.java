package com.yeodam.yeodambe.integration.controller;

import com.yeodam.yeodambe.integration.service.RunpodStatusService;
import com.yeodam.yeodambe.integration.service.response.RunpodStatusResponse;
import com.yeodam.yeodambe.common.exception.RunpodStatusUnavailableException;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.*;
import com.yeodam.yeodambe.user.security.jwt.*;
import com.yeodam.yeodambe.user.security.session.LoginSession;
import com.yeodam.yeodambe.user.security.session.LoginSessionStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.hamcrest.Matchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.*;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RunpodStatusController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtConfig.class, AccessTokenIssuer.class,
        CookieAccessTokenResolver.class, ApiAuthenticationEntryPoint.class,
        CsrfAccessDeniedHandler.class, RedisCsrfTokenRepository.class, ActiveLoginSessionValidator.class})
class RunpodStatusSecurityIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private AccessTokenIssuer tokens;
    @MockitoBean
    private RunpodStatusService service;
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
        when(sessions.findBySid("sid-42")).thenReturn(Optional.of(new LoginSession(42L, "hash")));
        when(users.findById(42L)).thenReturn(Optional.of(new User("runpod@test.com", "조회")));
    }

    @ParameterizedTest
    @CsvSource(value = {"false,false", "true,false", "true,true", "null,false", "null,true"}, nullValues = "null")
    void 인증된_사용자에게_실행과_준비_상태를_공통_응답으로_반환한다(Boolean running, boolean ready) throws Exception {
        when(service.getStatus()).thenReturn(new RunpodStatusResponse(running, ready));

        mvc.perform(get("/ai/runpod/status").cookie(cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("RUNPOD_STATUS_FOUND"))
                .andExpect(jsonPath("$.data.running").value(
                        running == null ? Matchers.nullValue() : Matchers.is(running)))
                .andExpect(jsonPath("$.data").value(Matchers.hasKey("running")))
                .andExpect(jsonPath("$.data.ready").value(ready));
    }

    @Test
    void 미인증_요청은_AI_조회_없이_거부한다() throws Exception {
        mvc.perform(get("/ai/runpod/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void 조회_불가는_꺼짐_대신_503과_전용_오류를_반환한다() throws Exception {
        when(service.getStatus()).thenThrow(new RunpodStatusUnavailableException());

        mvc.perform(get("/ai/runpod/status").cookie(cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("RUNPOD_STATUS_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").value(Matchers.nullValue()));
    }

    private Cookie cookie() {
        return new Cookie("accessToken", tokens.issue(42L, "sid-42"));
    }
}
