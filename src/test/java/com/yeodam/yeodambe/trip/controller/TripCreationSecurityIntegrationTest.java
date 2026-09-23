package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.service.TripProcessingStatusService;
import com.yeodam.yeodambe.trip.service.TripProcessingCancellationService;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.request.TripListRequest;
import com.yeodam.yeodambe.trip.service.request.TripSort;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripFavoriteResponse;
import com.yeodam.yeodambe.trip.service.response.TripListItemResponse;
import com.yeodam.yeodambe.trip.service.response.TripListResponse;
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

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void 목록_API는_Jwt_subject와_검증된_필터를_서비스에_전달한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(tripService.findTrips(eq(42L), any(TripListRequest.class)))
                .willReturn(new TripListResponse(List.of(new TripListItemResponse(
                        7L,
                        "제주 여행",
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 2),
                        "제주",
                        3L,
                        true,
                        "https://cdn.test/7"
                )), false, null));

        mockMvc.perform(get("/trips")
                        .queryParam("sort", "OLDEST")
                        .queryParam("favorite", "true")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_LIST_FOUND"))
                .andExpect(jsonPath("$.data.items[0].tripId").value(7))
                .andExpect(jsonPath("$.data.items[0].isFavorite").value(true))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());

        then(tripService).should().findTrips(eq(42L), argThat(request ->
                request.sort() == TripSort.OLDEST && request.favorite()));
    }

    @Test
    void 목록_API의_필터가_잘못되면_400을_반환한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");

        mockMvc.perform(get("/trips")
                        .queryParam("favorite", "TRUE")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_TRIP_LIST_FILTER"));

        then(tripService).should(never()).findTrips(any(), any());
    }

    @Test
    void 목록_API는_액세스_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/trips"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(tripService).should(never()).findTrips(any(), any());
    }

    @Test
    void 즐겨찾기_등록_API는_Jwt_subject와_tripId를_서비스에_전달한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("favorite-browser")).willReturn("csrf-token");
        given(tripService.registerFavorite(7L, 42L))
                .willReturn(new TripFavoriteResponse(7L, true));

        mockMvc.perform(post("/trips/7/favorite")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "favorite-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("FAVORITE_REGISTERED"))
                .andExpect(jsonPath("$.data.tripId").value(7))
                .andExpect(jsonPath("$.data.isFavorite").value(true));

        then(tripService).should().registerFavorite(7L, 42L);
    }

    @Test
    void 즐겨찾기_등록_API는_액세스_토큰이_없으면_401을_반환한다() throws Exception {
        given(csrfTokenStore.find("unauthorized-favorite-browser")).willReturn("csrf-token");

        mockMvc.perform(post("/trips/7/favorite")
                        .cookie(new Cookie("CSRF_CONTEXT", "unauthorized-favorite-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(tripService).should(never()).registerFavorite(any(), any());
    }

    @Test
    void 즐겨찾기_등록_API는_CSRF가_일치하지_않으면_403을_반환한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("invalid-favorite-browser")).willReturn("csrf-token");

        mockMvc.perform(post("/trips/7/favorite")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "invalid-favorite-browser")
                        )
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));

        then(tripService).should(never()).registerFavorite(any(), any());
    }

    @Test
    void 즐겨찾기_삭제_API는_Jwt_subject와_tripId를_서비스에_전달하고_204를_반환한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("remove-favorite-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/trips/7/favorite")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "remove-favorite-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNoContent());

        then(tripService).should().removeFavorite(7L, 42L);
    }

    @Test
    void 즐겨찾기_삭제_API는_액세스_토큰이_없으면_401을_반환한다() throws Exception {
        given(csrfTokenStore.find("unauthorized-remove-favorite-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/trips/7/favorite")
                        .cookie(new Cookie("CSRF_CONTEXT", "unauthorized-remove-favorite-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(tripService).should(never()).removeFavorite(any(), any());
    }

    @Test
    void 즐겨찾기_삭제_API는_CSRF가_일치하지_않으면_403을_반환한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("invalid-remove-favorite-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/trips/7/favorite")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "invalid-remove-favorite-browser")
                        )
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));

        then(tripService).should(never()).removeFavorite(any(), any());
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
