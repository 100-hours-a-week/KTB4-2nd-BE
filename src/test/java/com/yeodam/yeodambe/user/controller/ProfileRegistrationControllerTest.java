package com.yeodam.yeodambe.user.controller;

import com.yeodam.yeodambe.common.exception.GlobalExceptionHandler;
import com.yeodam.yeodambe.user.exception.OnboardingTokenInvalidOrExpiredException;
import com.yeodam.yeodambe.user.service.ProfileRegistrationService;
import com.yeodam.yeodambe.user.security.CookiePathResolver;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ProfileRegistrationControllerTest {

    @Mock
    private ProfileRegistrationService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new ProfileRegistrationController(
                service, false, new CookiePathResolver("/api")
        ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void successfulRegistrationReturnsCreatedAndReplacesProfileCookie() throws Exception {
        given(service.register("profile-1", "여행자"))
                .willReturn(new ProfileRegistrationService.Result(
                        42L, "여행자", "access-1", "refresh-1"
                ));

        var response = mockMvc.perform(post("/users/me/profile")
                        .cookie(new Cookie("profileToken", "profile-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"여행자\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value("ONBOARDING_SUCCESS"))
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.nickname").value("여행자"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andReturn().getResponse();

        assertThat(response.getHeaders("Set-Cookie"))
                .hasSize(3)
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("accessToken=access-1", "Path=/", "Max-Age=1800", "HttpOnly", "SameSite=Lax"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("refreshToken=refresh-1", "Path=/api/auth", "Max-Age=604800", "HttpOnly", "SameSite=Lax"))
                .anySatisfy(cookie -> assertThat(cookie)
                        .contains("profileToken=", "Path=/api/users/me/profile", "Max-Age=0", "HttpOnly", "SameSite=Lax"));
    }

    @Test
    void expiredProfileTokenReturnsUnauthorized() throws Exception {
        given(service.register("expired", "여행자"))
                .willThrow(new OnboardingTokenInvalidOrExpiredException());

        mockMvc.perform(post("/users/me/profile")
                        .cookie(new Cookie("profileToken", "expired"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"여행자\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message")
                        .value("ONBOARDING_TOKEN_INVALID_OR_EXPIRED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void accessTokenWithoutProfileTokenReturnsForbidden() throws Exception {
        mockMvc.perform(post("/users/me/profile")
                        .cookie(new Cookie("accessToken", "access-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"여행자\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("ONBOARDING_TOKEN_REQUIRED"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(service);
    }
}
