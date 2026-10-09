package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.service.StorySourceFolderListService;
import com.yeodam.yeodambe.story.service.response.StorySourceFolderListResponse;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StorySourceFolderController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtConfig.class, AccessTokenIssuer.class,
        CookieAccessTokenResolver.class, ApiAuthenticationEntryPoint.class,
        CsrfAccessDeniedHandler.class, RedisCsrfTokenRepository.class})
class StorySourceFolderControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AccessTokenIssuer accessTokenIssuer;
    @MockitoBean
    private StorySourceFolderListService storySourceFolderListService;
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
    void 인증된_회원의_커서와_폴더응답을_전달한다() throws Exception {
        given(storySourceFolderListService.findFolders(42L, 7L, "cursor"))
                .willReturn(new StorySourceFolderListResponse(
                        List.of(new StorySourceFolderListResponse.Item(
                                101L, "성산일출봉", 42L, "https://storage.test/thumbnail", false)),
                        true, "next-cursor"));

        mockMvc.perform(authenticatedRequest().param("cursor", "cursor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("STORY_SOURCE_FOLDER_LIST_FOUND"))
                .andExpect(jsonPath("$.data.items[0].tripPlaceId").value(101))
                .andExpect(jsonPath("$.data.items[0].placeName").value("성산일출봉"))
                .andExpect(jsonPath("$.data.items[0].attachmentCount").value(42))
                .andExpect(jsonPath("$.data.items[0].thumbnailUrl").value("https://storage.test/thumbnail"))
                .andExpect(jsonPath("$.data.items[0].selected").value(false))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"));

        then(storySourceFolderListService).should().findFolders(42L, 7L, "cursor");
    }

    @Test
    void 커서없이_빈폴더를_조회하면_200을_반환한다() throws Exception {
        given(storySourceFolderListService.findFolders(42L, 7L, null))
                .willReturn(new StorySourceFolderListResponse(List.of(), false, null));

        mockMvc.perform(authenticatedRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());

        then(storySourceFolderListService).should().findFolders(42L, 7L, null);
    }

    @Test
    void 인증쿠키가_없으면_서비스호출없이_401을_반환한다() throws Exception {
        mockMvc.perform(request())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        then(storySourceFolderListService).shouldHaveNoInteractions();
    }

    @Test
    void 잘못된_토큰이면_서비스호출없이_401을_반환한다() throws Exception {
        mockMvc.perform(request().cookie(new Cookie("accessToken", "invalid-token")))
                .andExpect(status().isUnauthorized());
        then(storySourceFolderListService).shouldHaveNoInteractions();
    }

    @Test
    void 여행접근실패는_404를_반환한다() throws Exception {
        given(storySourceFolderListService.findFolders(42L, 7L, null))
                .willThrow(new TripNotFoundException());
        mockMvc.perform(authenticatedRequest())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("TRIP_NOT_FOUND"));
    }

    @Test
    void 잘못된_커서는_400을_반환한다() throws Exception {
        given(storySourceFolderListService.findFolders(42L, 7L, "bad"))
                .willThrow(new InvalidCursorException());
        mockMvc.perform(authenticatedRequest().param("cursor", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("INVALID_CURSOR"));
    }

    private MockHttpServletRequestBuilder authenticatedRequest() {
        return request().cookie(new Cookie("accessToken", accessTokenIssuer.issue(42L, "sid-42")));
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/trips/7/story/source-folders")
                .contextPath("/api")
                .servletPath("/trips/7/story/source-folders");
    }
}
