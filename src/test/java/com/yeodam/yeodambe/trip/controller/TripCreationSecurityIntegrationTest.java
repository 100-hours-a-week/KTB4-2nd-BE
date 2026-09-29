package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.PlaceFolderAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.service.TripAccessService;
import com.yeodam.yeodambe.trip.service.TripProcessingStatusService;
import com.yeodam.yeodambe.trip.service.TripProcessingCancellationService;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.TripPlaceFolderListService;
import com.yeodam.yeodambe.trip.service.TripDeletionService;
import com.yeodam.yeodambe.trip.service.request.PlaceFolderCursor;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import com.yeodam.yeodambe.trip.service.request.TripListRequest;
import com.yeodam.yeodambe.trip.service.request.TripSort;
import com.yeodam.yeodambe.trip.service.response.TripCreateResponse;
import com.yeodam.yeodambe.trip.service.response.TripDetailResponse;
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
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
        RdbCsrfTokenRepository.class,
        TripPlaceFolderListService.class
})
class TripCreationSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenIssuer accessTokenIssuer;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TripService tripService;

    @MockitoBean
    private TripProcessingStatusService processingStatusService;

    @MockitoBean
    private TripProcessingCancellationService processingCancellationService;

    @MockitoBean
    private TripDeletionService tripDeletionService;

    @MockitoBean
    private TripAccessService tripAccessService;

    @MockitoBean
    private TripDetailPlaceRepository tripDetailPlaceRepository;

    @MockitoBean
    private TripAttachmentRepository tripAttachmentRepository;

    @MockitoBean
    private TripAttachmentStorageClient tripAttachmentStorageClient;

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
    void 인증된_여행_생성_요청이_CSRF_토큰을_삭제하지_않는다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("repeat-browser")).willReturn("csrf-token");
        given(tripService.createTrip(eq(42L), any(TripCreateRequest.class)))
                .willReturn(new TripCreateResponse(7L, ProcessingStatus.PROCESSING));

        mockMvc.perform(post("/trips")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "repeat-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated());

        then(csrfTokenStore).should(never()).delete("repeat-browser");
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
    void 여행_삭제_API는_쿠키_Jwt와_CSRF를_검증한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("delete-trip-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/trips/7")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "delete-trip-browser")
                        )
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isNoContent());

        then(tripDeletionService).should().delete(7L, 42L);
    }

    @Test
    void 여행_삭제_API는_액세스_토큰이_없으면_401을_반환한다() throws Exception {
        given(csrfTokenStore.find("unauthorized-delete-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/trips/7")
                        .cookie(new Cookie("CSRF_CONTEXT", "unauthorized-delete-browser"))
                        .header("X-CSRF-TOKEN", "csrf-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(tripDeletionService).shouldHaveNoInteractions();
    }

    @Test
    void 여행_삭제_API는_CSRF가_없거나_일치하지_않으면_403을_반환한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(csrfTokenStore.find("invalid-delete-browser")).willReturn("csrf-token");

        mockMvc.perform(delete("/trips/7")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "invalid-delete-browser")
                        ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(delete("/trips/7")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("CSRF_CONTEXT", "invalid-delete-browser")
                        )
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CSRF_TOKEN_INVALID"));

        then(tripDeletionService).shouldHaveNoInteractions();
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
    void 상세_API는_CSRF_없이_Jwt_subject를_전달한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(tripService.findTripDetail(7L, 42L)).willReturn(new TripDetailResponse(
                7L, "제주 여행", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
                1L, List.of(), 3L, false, true));

        mockMvc.perform(get("/trips/7")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("TRIP_FOUND"))
                .andExpect(jsonPath("$.data.tripId").value(7))
                .andExpect(jsonPath("$.data.nightCount").value(1))
                .andExpect(jsonPath("$.data.attachmentCount").value(3))
                .andExpect(jsonPath("$.data.hasStory").value(false));

        then(tripService).should().findTripDetail(7L, 42L);
        verifyNoInteractions(csrfTokenStore);
    }

    @Test
    void 상세_API는_액세스_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/trips/7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(tripService).should(never()).findTripDetail(any(), any());
    }

    @Test
    void 장소_폴더_목록_API는_Jwt_subject와_커서를_전달하고_응답을_직렬화한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        String cursor = new PlaceFolderCursor(7L, "제주", 11L).encode(objectMapper);
        List<TripDetailPlace> fetched = List.of(
                place(1L, "장소 1", "thumb/1.webp"),
                place(2L, "장소 2", null),
                place(3L, "장소 3", null),
                place(4L, "장소 4", null),
                place(5L, "장소 5", null),
                place(6L, "장소 6", null),
                place(7L, "장소 7", null)
        );
        given(tripDetailPlaceRepository.findPlaceFoldersWithCursor(
                eq(7L), eq("제주"), eq(11L), any(Pageable.class))).willReturn(fetched);
        given(tripAttachmentRepository.countActiveByTripPlaceIds(
                List.of(1L, 2L, 3L, 4L, 5L, 6L)))
                .willReturn(List.of(new PlaceFolderAttachmentCount(1L, 2L)));
        given(tripAttachmentStorageClient.createReadUrl("thumb/1.webp"))
                .willReturn("https://cdn.test/1");
        String nextCursor = new PlaceFolderCursor(7L, "장소 6", 6L).encode(objectMapper);

        mockMvc.perform(get("/trips/7/place-folders")
                        .queryParam("cursor", cursor)
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("PLACE_FOLDER_LIST_FOUND"))
                .andExpect(jsonPath("$.data.items.length()").value(6))
                .andExpect(jsonPath("$.data.items[0].tripPlaceId").value(1))
                .andExpect(jsonPath("$.data.items[0].placeName").value("장소 1"))
                .andExpect(jsonPath("$.data.items[0].attachmentCount").value(2))
                .andExpect(jsonPath("$.data.items[0].thumbnailUrl").value("https://cdn.test/1"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value(nextCursor));

        then(tripAccessService).should().requireReadableTrip(7L, 42L);
    }

    @Test
    void 장소_폴더_목록_API의_커서가_손상되면_400을_반환한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");

        mockMvc.perform(get("/trips/7/place-folders")
                        .queryParam("cursor", "bad-cursor")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_PLACE_FOLDER_CURSOR"));

        verifyNoInteractions(tripDetailPlaceRepository, tripAttachmentRepository,
                tripAttachmentStorageClient);
    }

    @Test
    void 장소_폴더_목록_API의_여행에_접근할수없으면_404를_반환한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");
        given(tripAccessService.requireReadableTrip(7L, 42L))
                .willThrow(new TripNotFoundException());

        mockMvc.perform(get("/trips/7/place-folders")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"));

        verifyNoInteractions(tripDetailPlaceRepository, tripAttachmentRepository,
                tripAttachmentStorageClient);
    }

    @Test
    void 장소_폴더_목록_API는_액세스_토큰이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/trips/7/place-folders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(tripAccessService).should(never()).requireReadableTrip(any(), any());
    }

    @Test
    void 장소_폴더_목록_API의_tripId가_숫자가_아니면_400을_반환한다() throws Exception {
        String accessToken = accessTokenIssuer.issue(42L, "sid-42");

        mockMvc.perform(get("/trips/not-number/place-folders")
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isBadRequest());

        then(tripAccessService).should(never()).requireReadableTrip(any(), any());
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

    private TripDetailPlace place(Long id, String name, String thumbnailKey) {
        TripDetailPlace place = mock(TripDetailPlace.class);
        when(place.getId()).thenReturn(id);
        when(place.getPlaceName()).thenReturn(name);
        when(place.getThumbnailKey()).thenReturn(thumbnailKey);
        return place;
    }
}
