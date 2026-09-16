package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.user.exception.DuplicateEmailException;
import com.yeodam.yeodambe.user.exception.InvalidNicknameException;
import com.yeodam.yeodambe.user.exception.DuplicateOAuthAccountException;
import com.yeodam.yeodambe.user.exception.KakaoAuthenticationFailedException;
import com.yeodam.yeodambe.user.exception.OAuthProviderUnavailableException;
import com.yeodam.yeodambe.user.exception.OAuthStateInvalidOrExpiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsConflictWhenEmailAlreadyInUse() throws Exception {
        mockMvc.perform(get("/test/duplicate-email"))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {
                          "message": "EMAIL_ALREADY_IN_USE",
                          "data": null
                        }
                        """));
    }

    @Test
    void returnsBadRequestWhenNicknameIsInvalid() throws Exception {
        mockMvc.perform(get("/test/invalid-nickname"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                    {
                      "message": "INVALID_NICKNAME",
                      "data": null
                    }
                    """));


    }

    @Test
    void returnsConflictWhenOAuthAccountAlreadyRegistered() throws Exception {
        mockMvc.perform(get("/test/duplicate-oauth-account"))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                    {
                      "message": "ACCOUNT_ALREADY_REGISTERED",
                      "data": null
                    }
                    """));
    }

    @Test
    void returnsBadRequestWhenOAuthStateIsInvalidOrExpired() throws Exception {
        mockMvc.perform(get("/test/oauth-state-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                    {
                      "message": "OAUTH_STATE_INVALID_OR_EXPIRED",
                      "data": null
                    }
                    """));
    }

    @Test
    void returnsUnauthorizedWhenKakaoAuthenticationFails() throws Exception {
        mockMvc.perform(get("/test/kakao-authentication-failed"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                    {
                      "message": "KAKAO_AUTHENTICATION_FAILED",
                      "data": null
                    }
                    """));
    }

    @Test
    void returnsServiceUnavailableWhenOAuthProviderFails() throws Exception {
        mockMvc.perform(get("/test/oauth-provider-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("""
                    {
                      "message": "OAUTH_PROVIDER_UNAVAILABLE",
                      "data": null
                    }
                    """));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/duplicate-email")
        void duplicateEmail() {
            throw new DuplicateEmailException();
        }

        @GetMapping("/test/invalid-nickname")
        void invalidNickname() {
            throw new InvalidNicknameException();
        }

        @GetMapping("/test/duplicate-oauth-account")
        void duplicateOAuthAccount() {
            throw new DuplicateOAuthAccountException();
        }

        @GetMapping("/test/oauth-state-invalid")
        void oauthStateInvalid() {
            throw new OAuthStateInvalidOrExpiredException();
        }

        @GetMapping("/test/kakao-authentication-failed")
        void kakaoAuthenticationFailed() {
            throw new KakaoAuthenticationFailedException();
        }

        @GetMapping("/test/oauth-provider-unavailable")
        void oauthProviderUnavailable() {
            throw new OAuthProviderUnavailableException();
        }
    }
}
