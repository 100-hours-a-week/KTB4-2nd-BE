package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.user.exception.DuplicateEmailException;
import com.yeodam.yeodambe.user.exception.InvalidEmailException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
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

    @ParameterizedTest
    @CsvSource({
            "invalid-attachment, 400, INVALID_ATTACHMENT_UPLOAD",
            "trip-not-found, 404, TRIP_NOT_FOUND",
            "processing-cannot-be-canceled, 409, TRIP_PROCESSING_CANNOT_BE_CANCELED",
            "attachment-not-allowed, 409, TRIP_INITIAL_ATTACHMENT_UPLOAD_NOT_ALLOWED",
            "attachment-limit, 413, ATTACHMENT_UPLOAD_LIMIT_EXCEEDED",
            "unsupported-attachment, 415, UNSUPPORTED_ATTACHMENT_FORMAT",
            "place-folder-not-found, 404, PLACE_FOLDER_NOT_FOUND",
            "attachment-not-found, 404, ATTACHMENT_NOT_FOUND",
            "invalid-cursor, 400, INVALID_CURSOR"
    })
    void 초기_첨부_예외를_공개_API_오류로_변환한다(String path, int statusCode, String message) throws Exception {
        mockMvc.perform(get("/test/" + path))
                .andExpect(status().is(statusCode))
                .andExpect(content().json("{\"message\":\"" + message + "\",\"data\":null}"));
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

    @ParameterizedTest
    @CsvSource({
            "invalid-email, 400, INVALID_EMAIL_FORMAT",
            "illegal-argument, 500, INTERNAL_SERVER_ERROR",
            "illegal-state, 500, INTERNAL_SERVER_ERROR"
    })
    void distinguishesInvalidInputFromInternalRuntimeErrors(String path, int statusCode, String message)
            throws Exception {
        mockMvc.perform(get("/test/" + path))
                .andExpect(status().is(statusCode))
                .andExpect(content().json("{\"message\":\"" + message + "\",\"data\":null}"));
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
    void returnsServiceUnavailableWhenAuthenticationDatabaseFails() throws Exception {
        mockMvc.perform(get("/test/auth-database-unavailable"))
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
    void AI_상태_조회_연결_실패는_서비스_사용_불가로_응답한다() throws Exception {
        mockMvc.perform(get("/test/ai-status-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("""
                        {
                          "message": "AI_STATUS_UNAVAILABLE",
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

    @Test
    void returnsUnauthorizedWhenAuthenticationIsMissing() throws Exception {
        mockMvc.perform(get("/test/authentication-missing"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"UNAUTHORIZED\",\"data\":null}"));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/invalid-attachment")
        void invalidAttachment() {
            throw new InvalidAttachmentUploadException();
        }

        @GetMapping("/test/trip-not-found")
        void tripNotFound() {
            throw new TripNotFoundException();
        }

        @GetMapping("/test/processing-cannot-be-canceled")
        void processingCannotBeCanceled() {
            throw new TripProcessingCannotBeCanceledException();
        }

        @GetMapping("/test/attachment-not-allowed")
        void attachmentNotAllowed() {
            throw new TripInitialAttachmentUploadNotAllowedException();
        }

        @GetMapping("/test/attachment-limit")
        void attachmentLimit() {
            throw new AttachmentUploadLimitExceededException();
        }

        @GetMapping("/test/unsupported-attachment")
        void unsupportedAttachment() {
            throw new UnsupportedAttachmentFormatException();
        }

        @GetMapping("/test/place-folder-not-found")
        void placeFolderNotFound() {
            throw new PlaceFolderNotFoundException();
        }

        @GetMapping("/test/attachment-not-found")
        void attachmentNotFound() {
            throw new AttachmentNotFoundException();
        }

        @GetMapping("/test/invalid-cursor")
        void invalidCursor() {
            throw new InvalidCursorException();
        }

        @GetMapping("/test/duplicate-email")
        void duplicateEmail() {
            throw new DuplicateEmailException();
        }

        @GetMapping("/test/invalid-nickname")
        void invalidNickname() {
            throw new InvalidNicknameException();
        }

        @GetMapping("/test/invalid-email")
        void invalidEmail() {
            throw new InvalidEmailException();
        }

        @GetMapping("/test/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("내부 객체가 잘못되었습니다.");
        }

        @GetMapping("/test/illegal-state")
        void illegalState() {
            throw new IllegalStateException("내부 상태가 잘못되었습니다.");
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

        @GetMapping("/test/auth-database-unavailable")
        void authenticationDatabaseUnavailable() {
            throw new DataAccessResourceFailureException(
                    "Authentication database unavailable"
            );
        }

        @GetMapping("/test/ai-status-unavailable")
        void aiStatusUnavailable() {
            throw new AiStatusUnavailableException(new IllegalStateException("timeout"));
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

        @GetMapping("/test/authentication-missing")
        void authenticationMissing() {
            throw new AuthenticationCredentialsNotFoundException("인증된 사용자가 없습니다.");
        }
    }
}
