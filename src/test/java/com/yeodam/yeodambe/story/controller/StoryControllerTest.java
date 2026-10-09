package com.yeodam.yeodambe.story.controller;

import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.story.service.StoryReadService;
import com.yeodam.yeodambe.story.service.response.StoryDetailResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.security.SecurityConfig;
import com.yeodam.yeodambe.user.security.csrf.*;
import com.yeodam.yeodambe.user.security.jwt.*;
import com.yeodam.yeodambe.user.security.session.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StoryController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtConfig.class, AccessTokenIssuer.class, ActiveLoginSessionValidator.class, CookieAccessTokenResolver.class, ApiAuthenticationEntryPoint.class, CsrfAccessDeniedHandler.class, RedisCsrfTokenRepository.class})
class StoryControllerTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokenIssuer issuer;

    @Autowired
    JwtEncoder encoder;

    @Autowired
    JwtProperties properties;

    @MockitoBean
    StoryReadService service;

    @MockitoBean
    LoginSessionStore sessions;

    @MockitoBean
    UserRepository users;

    @MockitoBean
    CsrfTokenStore csrfTokens;

    @MockitoBean
    CsrfTokenGenerator csrfGenerator;

    private Cookie authenticated() {
        when(sessions.findBySid("sid-42")).thenReturn(Optional.of(new LoginSession(42L, "refresh-hash")));
        when(users.findById(42L)).thenReturn(Optional.of(new User("story@test.com", "작성자")));
        return new Cookie("accessToken", issuer.issue(42L, "sid-42"));
    }

    @Test
    void 성공_응답의_전체필드와_날짜를_직렬화한다() throws Exception {
        var block = new StoryDetailResponse.Block(3L, 1, 4L, 5L, "https://image.test/5", "사진 문구", "");
        var day = new StoryDetailResponse.Day(LocalDate.of(2026, 10, 7), "첫째 날", List.of(block));
        when(service.findStory(42L, 7L)).thenReturn(new StoryDetailResponse(1L, 7L, true, Story.Mood.CALM, "요약", List.of(day)));
        mvc.perform(get("/trips/7/story").cookie(authenticated()))
                .andExpect(status().isOk()).andExpect(content().json("""
                {"message":"STORY_FOUND","data":{"storyId":1,"tripId":7,"userByMe":true,"mood":"CALM","storySummary":"요약",
                "days":[{"date":"2026-10-07","dayLabel":"첫째 날","blocks":[{"storyBlockId":3,"orderNumber":1,"tripPlaceId":4,
                "tripAttachmentId":5,"thumbnailUrl":"https://image.test/5","detailSummary":"사진 문구","memo":""}]}]}}
                """));
        verify(service).findStory(42L, 7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "9223372036854775808", "0", "-1"})
    void 잘못된_경로는_400이며_서비스를_호출하지_않는다(String id) throws Exception {
        var result = mvc.perform(get("/trips/"+id+"/story").cookie(authenticated()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").isEmpty()).andReturn();
        if (id.equals("0") || id.equals("-1")) {
            assertThat(result.getResolvedException()).isInstanceOf(HandlerMethodValidationException.class);
        }
        verifyNoInteractions(service);
    }

    @Test
    void 조회불가는_404다() throws Exception {
        when(service.findStory(42L, 7L)).thenThrow(new StoryNotFoundException());
        mvc.perform(get("/trips/7/story").cookie(authenticated())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("STORY_NOT_FOUND")).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 정합성과_URL발급_실패는_500이다() throws Exception {
        Cookie cookie = authenticated();
        for (RuntimeException failure : List.of(new StoryDataIntegrityException("표시 조건: 장소 없음"), new IllegalStateException("URL 발급 실패"))) {
            doThrow(failure).when(service).findStory(42L, 7L);
            mvc.perform(get("/trips/7/story").cookie(cookie)).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("INTERNAL_SERVER_ERROR")).andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Test
    void 무인증은_입력검증보다_먼저_401이다() throws Exception {
        mvc.perform(get("/trips/0/story")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void 만료토큰은_401이다() throws Exception {
        Cookie cookie = authenticated();
        Instant past = Instant.now().minusSeconds(3600);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience()))
                .subject("42").claim("sid", "sid-42").issuedAt(past.minusSeconds(3600)).expiresAt(past).build();
        cookie.setValue(encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
        mvc.perform(get("/trips/7/story").cookie(cookie)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 무효세션과_탈퇴계정은_401이다() throws Exception {
        Cookie cookie = authenticated();
        when(sessions.findBySid("sid-42")).thenReturn(Optional.empty());
        mvc.perform(get("/trips/7/story").cookie(cookie)).andExpect(status().isUnauthorized());
        when(sessions.findBySid("sid-42")).thenReturn(Optional.of(new LoginSession(42L, "hash")));
        User deleted = new User("deleted@test.com", "탈퇴회원");
        deleted.withdraw(LocalDateTime.now());
        when(users.findById(42L)).thenReturn(Optional.of(deleted));
        mvc.perform(get("/trips/7/story").cookie(cookie)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 기존_DB장애의_503_정책을_유지한다() throws Exception {
        when(service.findStory(42L, 7L)).thenThrow(new DataAccessResourceFailureException("DB 오류"));
        mvc.perform(get("/trips/7/story").cookie(authenticated())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"));
    }

    @Test
    void 인증저장소_장애도_503이다() throws Exception {
        Cookie cookie = authenticated();
        when(sessions.findBySid("sid-42")).thenThrow(new DataAccessResourceFailureException("인증 저장소 오류"));
        mvc.perform(get("/trips/7/story").cookie(cookie)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("AUTH_STORE_UNAVAILABLE"));
        verifyNoInteractions(service);
    }
}
