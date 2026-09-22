package com.yeodam.yeodambe.trip.controller;

import com.yeodam.yeodambe.trip.client.PlaceClient;
import com.yeodam.yeodambe.trip.service.PlaceService;
import com.yeodam.yeodambe.trip.service.RegionCatalog;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlaceController.class)
@ActiveProfiles("test")
@Import({
        SecurityConfig.class,
        PlaceService.class,
        PlaceClient.class,
        RegionCatalog.class,
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
    private ActiveLoginSessionValidator activeLoginSessionValidator;

    @MockitoBean
    private CsrfTokenStore csrfTokenStore;

    @MockitoBean
    private CsrfTokenGenerator csrfTokenGenerator;

    @Value("${place.provider.base-url:}")
    private String providerBaseUrl;

    @Value("${place.provider.service-key:}")
    private String providerServiceKey;

    @Test
    @WithMockUser
    void 실제_행안부_API에서_여행지_후보를_검색한다() throws Exception {
        Assumptions.assumeTrue(!providerBaseUrl.isBlank() && !providerServiceKey.isBlank(),
                "행안부 API URL과 서비스 키가 설정되어야 합니다.");

        mockMvc.perform(get("/places").param("query", "제주"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("PLACE_CANDIDATES_FOUND"))
                .andExpect(jsonPath("$.data.items[0].regionCode").value("50110"))
                .andExpect(jsonPath("$.data.items[0].regionName").value("제주특별자치도 제주시"));
    }

    @Test
    @WithMockUser
    void 잘못된_검색어는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/places").param("query", "제주1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_REQUEST"));
    }

    @Test
    void 미인증_요청은_401을_반환한다() throws Exception {
        mockMvc.perform(get("/places").param("query", "제주"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
    }
}
