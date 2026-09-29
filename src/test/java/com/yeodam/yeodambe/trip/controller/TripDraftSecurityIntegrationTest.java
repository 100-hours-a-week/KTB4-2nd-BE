package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.exception.TripDraftNotFoundException;
import com.yeodam.yeodambe.trip.service.TripDraftService;
import com.yeodam.yeodambe.trip.service.response.TripDraftResponse;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.*;
import com.yeodam.yeodambe.user.security.jwt.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TripDraftController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtConfig.class, AccessTokenIssuer.class, CookieAccessTokenResolver.class,
        ApiAuthenticationEntryPoint.class, CsrfAccessDeniedHandler.class, RdbCsrfTokenRepository.class})
class TripDraftSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenIssuer tokens;
    @MockitoBean TripDraftService drafts;
    @MockitoBean ActiveLoginSessionValidator sessions;
    @MockitoBean CsrfTokenStore csrf;
    @MockitoBean CsrfTokenGenerator csrfGenerator;

    @BeforeEach
    void session() {
        given(sessions.validate(any(Jwt.class))).willReturn(OAuth2TokenValidatorResult.success());
    }

    @Test
    void 저장과_조회는_계약_메시지와_사용자_초안을_반환한다() throws Exception {
        var response = new TripDraftResponse(7L, "제주", List.of(), null, null, null,
                LocalDateTime.of(2026, 9, 29, 10, 0));
        given(csrf.find("browser")).willReturn("csrf-token");
        given(drafts.save(org.mockito.ArgumentMatchers.eq(42L), any())).willReturn(response);
        given(drafts.find(42L)).willReturn(response);
        Cookie access = new Cookie("accessToken", tokens.issue(42L, "sid"));

        mvc.perform(put("/trip-drafts/me").cookie(access, new Cookie("CSRF_CONTEXT", "browser"))
                        .header("X-CSRF-TOKEN", "csrf-token").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tripName":"제주","regionCodes":[],"startDate":null,"endDate":null}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("TRIP_DRAFT_SAVED"))
                .andExpect(jsonPath("$.data.draftId").value(7));
        mvc.perform(get("/trip-drafts/me").cookie(access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("TRIP_DRAFT_FOUND"));
    }

    @Test
    void 인증과_CSRF를_검사한다() throws Exception {
        given(csrf.find("browser")).willReturn("csrf-token");
        mvc.perform(get("/trip-drafts/me")).andExpect(status().isUnauthorized());
        mvc.perform(put("/trip-drafts/me").cookie(new Cookie("accessToken", tokens.issue(42L, "sid")),
                        new Cookie("CSRF_CONTEXT", "browser"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(drafts);
    }

    @Test
    void 누락_필드와_없는_초안을_거부한다() throws Exception {
        Cookie access = new Cookie("accessToken", tokens.issue(42L, "sid"));
        given(csrf.find("browser")).willReturn("csrf-token");
        mvc.perform(put("/trip-drafts/me").cookie(access, new Cookie("CSRF_CONTEXT", "browser"))
                        .header("X-CSRF-TOKEN", "csrf-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tripName\":\"제주\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_TRIP_DRAFT_REQUEST"));
        given(drafts.find(42L)).willThrow(new TripDraftNotFoundException());
        mvc.perform(get("/trip-drafts/me").cookie(access)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_DRAFT_NOT_FOUND"));
    }
}
