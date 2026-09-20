package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.user.exception.DuplicateEmailException;
import com.yeodam.yeodambe.user.exception.InvalidNicknameException;
import com.yeodam.yeodambe.user.exception.DuplicateOAuthAccountException;
import com.yeodam.yeodambe.user.exception.KakaoAuthenticationFailedException;
import com.yeodam.yeodambe.user.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.user.exception.LoginTicketIssueFailedException;
import com.yeodam.yeodambe.user.exception.OAuthProviderUnavailableException;
import com.yeodam.yeodambe.user.exception.OAuthStateCreateFailedException;
import com.yeodam.yeodambe.user.exception.OAuthStateInvalidOrExpiredException;
import com.yeodam.yeodambe.user.exception.UserNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
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
    void returnsUnauthorizedWhenLoginTicketIsInvalidOrExpired() throws Exception {
        mockMvc.perform(get("/test/login-ticket-invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {
                          "message": "LOGIN_TICKET_INVALID_OR_EXPIRED",
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

    @Test
    void returnsServiceUnavailableWhenRedisConnectionFails() throws Exception {
        mockMvc.perform(get("/test/redis-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("""
                    {
                      "message": "AUTH_STORE_UNAVAILABLE",
                      "data": null
                    }
                    """));
    }

    @Test
    void returnsNotFoundWhenActiveUserDoesNotExist() throws Exception {
        mockMvc.perform(get("/test/user-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                    {
                      "message": "USER_NOT_FOUND",
                      "data": null
                    }
                        """));
    }

    @Test
    void returnsInternalServerErrorWhenOAuthStateCreationFails() throws Exception {
        mockMvc.perform(get("/test/oauth-state-create-failed"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                    {
                      "message": "OAUTH_STATE_CREATE_FAILED",
                      "data": null
                    }
                    """));
    }

    @Test
    void returnsInternalServerErrorWhenLoginTicketIssueFails() throws Exception {
        mockMvc.perform(get("/test/login-ticket-issue-failed"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                    {
                      "message": "LOGIN_TICKET_ISSUE_FAILED",
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

        @GetMapping("/test/login-ticket-invalid")
        void loginTicketInvalid() {
            throw new LoginTicketInvalidOrExpiredException();
        }

        @GetMapping("/test/oauth-provider-unavailable")
        void oauthProviderUnavailable() {
            throw new OAuthProviderUnavailableException();
        }

        @GetMapping("/test/redis-unavailable")
        void redisUnavailable() {
            throw new RedisConnectionFailureException("Redis unavailable");
        }

        @GetMapping("/test/user-not-found")
        void userNotFound() {
            throw new UserNotFoundException();
        }

        @GetMapping("/test/oauth-state-create-failed")
        void oauthStateCreateFailed() {
            throw new OAuthStateCreateFailedException(
                    new IllegalStateException("state generation failed")
            );
        }

        @GetMapping("/test/login-ticket-issue-failed")
        void loginTicketIssueFailed() {
            throw new LoginTicketIssueFailedException(
                    new IllegalStateException("ticket issue failed")
            );
        }
    }
}
