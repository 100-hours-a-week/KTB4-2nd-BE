package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.common.exception.PlaceQueryProviderUnavailableException;
import com.yeodam.yeodambe.trip.service.PlaceService;
import com.yeodam.yeodambe.trip.service.request.PlaceSearchRequest;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidateResponse;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidatesResponse;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlaceController.class)
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
class PlaceApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlaceService placeService;

    @MockitoBean
    private ActiveLoginSessionValidator activeLoginSessionValidator;

    @MockitoBean
    private CsrfTokenStore csrfTokenStore;

    @MockitoBean
    private CsrfTokenGenerator csrfTokenGenerator;

    @Test
    @WithMockUser
    void 인증된_사용자는_여행지_후보를_검색한다() throws Exception {
        given(placeService.search(new PlaceSearchRequest("제주", null)))
                .willReturn(new PlaceCandidatesResponse(List.of(
                        new PlaceCandidateResponse("50110", "제주특별자치도 제주시")
                )));

        mockMvc.perform(get("/places").param("query", "제주"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("PLACE_CANDIDATES_FOUND"))
                .andExpect(jsonPath("$.data.items[0].regionCode").value("50110"))
                .andExpect(jsonPath("$.data.items[0].regionName").value("제주특별자치도 제주시"));

        then(placeService).should().search(new PlaceSearchRequest("제주", null));
    }

    @Test
    @WithMockUser
    void 잘못된_검색어는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/places").param("query", "제주1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));

        then(placeService).should(never()).search(any());
    }

    @Test
    @WithMockUser
    void 행안부_API_장애는_503을_반환한다() throws Exception {
        given(placeService.search(new PlaceSearchRequest("제주", null)))
                .willThrow(new PlaceQueryProviderUnavailableException("행안부 API 장애"));

        mockMvc.perform(get("/places").param("query", "제주"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("MAP_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void 미인증_요청은_401을_반환한다() throws Exception {
        mockMvc.perform(get("/places").param("query", "제주"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));

        then(placeService).should(never()).search(any());
    }
}
