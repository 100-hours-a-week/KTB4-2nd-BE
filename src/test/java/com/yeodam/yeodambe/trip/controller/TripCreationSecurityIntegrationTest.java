package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripProcessingStatusService;
import com.yeodam.yeodambe.trip.service.TripProcessingCancellationService;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TripController.class)
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
class TripCreationSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private TripProcessingStatusService processingStatusService;

    @MockitoBean
    private TripProcessingCancellationService processingCancellationService;

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
    void accessTokenCookie의_Jwt_subject를_회원_아이디로_전달한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("trip-create-browser")).willReturn("csrf-token");
        given(tripService.createTrip(eq(42L), any(TripCreateRequest.class)))
                .willReturn(new TripCreateResponse(7L, ProcessingStatus.PROCESSING));

        mockMvc.perform(post("/trips")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "trip-create-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tripName": "제주 여행",
                                  "startDate": "2026-09-01",
                                  "endDate": "2026-09-02",
                                  "regionCodes": ["50110"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("TRIP_CREATED"))
                .andExpect(jsonPath("$.data.tripId").value(7))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        then(tripService).should().createTrip(eq(42L), argThat(request ->
                request.tripName().equals("제주 여행")
                        && request.regionCodes().equals(java.util.List.of("50110"))));
    }

    @Test
    void accessToken이_없거나_유효하지_않으면_공통_401을_반환한다() throws Exception {
        given(csrfTokenStore.find("unauthorized-browser")).willReturn("csrf-token");

        mockMvc.perform(post("/trips")
                        .cookie(new Cookie("CSRF_CONTEXT", "unauthorized-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(post("/trips")
                        .cookie(
                                new Cookie("accessToken", "invalid-jwt"),
                                new Cookie("CSRF_CONTEXT", "unauthorized-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").doesNotExist());

        then(tripService).should(never()).createTrip(any(), any());
    }

    @Test
    void 취소_API는_쿠키_Jwt와_CSRF를_검증한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("cancel-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/trips/7/processing")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "cancel-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNoContent());

        then(processingCancellationService).should().cancel(7L, 42L);
    }

    @Test
    void 취소_API는_액세스_토큰이_없으면_401을_반환한다() throws Exception {
        given(csrfTokenStore.find("unauthorized-cancel-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/trips/7/processing")
                        .cookie(new Cookie("CSRF_CONTEXT", "unauthorized-cancel-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
    }

    @Test
    void 취소_API는_CSRF가_일치하지_않으면_403을_반환한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("invalid-csrf-cancel-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/trips/7/processing")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "invalid-csrf-cancel-browser")
                        )
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));
    }

    private String validRequest() {
        return """
                {
                  "tripName": "제주 여행",
                  "startDate": "2026-09-01",
                  "endDate": "2026-09-02",
                  "regionCodes": ["50110"]
                }
                """;
    }
}
