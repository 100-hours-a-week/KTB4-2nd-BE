package com.yeodam.yeodambe.common.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeodam.yeodambe.common.exception.DuplicateEmailException;
import com.yeodam.yeodambe.common.exception.InvalidEmailException;
import com.yeodam.yeodambe.common.exception.InvalidNicknameException;
import com.yeodam.yeodambe.common.exception.DuplicateOAuthAccountException;
import com.yeodam.yeodambe.common.exception.KakaoAuthenticationFailedException;
import com.yeodam.yeodambe.common.exception.LoginTicketInvalidOrExpiredException;
import com.yeodam.yeodambe.common.exception.LoginTicketIssueFailedException;
import com.yeodam.yeodambe.common.exception.OAuthProviderUnavailableException;
import com.yeodam.yeodambe.common.exception.OAuthStateCreateFailedException;
import com.yeodam.yeodambe.common.exception.OAuthStateInvalidOrExpiredException;
import com.yeodam.yeodambe.common.exception.UserNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.LoggerFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @ParameterizedTest
    @CsvSource({
            "invalid-story-request, 400, INVALID_STORY_REQUEST",
            "story-generation-forbidden, 403, STORY_GENERATION_FORBIDDEN",
            "invalid-attachment, 400, INVALID_ATTACHMENT_UPLOAD",
            "trip-not-found, 404, TRIP_NOT_FOUND",
            "trip-deletion-not-allowed, 409, TRIP_DELETION_NOT_ALLOWED",
            "processing-cannot-be-canceled, 409, TRIP_PROCESSING_CANNOT_BE_CANCELED",
            "attachment-not-allowed, 409, TRIP_INITIAL_ATTACHMENT_UPLOAD_NOT_ALLOWED",
            "attachment-limit, 413, ATTACHMENT_UPLOAD_LIMIT_EXCEEDED",
            "unsupported-attachment, 415, UNSUPPORTED_ATTACHMENT_FORMAT",
            "place-folder-not-found, 404, PLACE_FOLDER_NOT_FOUND",
            "attachment-not-found, 404, ATTACHMENT_NOT_FOUND",
            "invalid-cursor, 400, INVALID_CURSOR",
            "invalid-place-folder-cursor, 400, INVALID_PLACE_FOLDER_CURSOR",
            "invalid-attachment-ids, 400, INVALID_ATTACHMENT_IDS",
            "write-permission-required, 403, WRITE_PERMISSION_REQUIRED",
            "trip-detail-not-available, 409, TRIP_DETAIL_NOT_AVAILABLE",
            "trip-update-not-allowed, 409, TRIP_UPDATE_NOT_ALLOWED"
    })
    void 초기_첨부_예외를_공개_API_오류로_변환한다(String path, int statusCode, String message) throws Exception {
        mockMvc.perform(get("/test/" + path))
                .andExpect(status().is(statusCode))
                .andExpect(content().json("{\"message\":\"" + message + "\",\"data\":null}"));
    }

    @Test
    void 이미_사용_중인_이메일이면_409를_반환한다() throws Exception {
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
    void 닉네임이_유효하지_않으면_400을_반환한다() throws Exception {
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
    void 잘못된_입력과_내부_런타임_오류를_구분한다(String path, int statusCode, String message)
            throws Exception {
        mockMvc.perform(get("/test/" + path))
                .andExpect(status().is(statusCode))
                .andExpect(content().json("{\"message\":\"" + message + "\",\"data\":null}"));
    }

    @Test
    void 이미_등록된_OAuth_계정이면_409를_반환한다() throws Exception {
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
    void OAuth_상태가_유효하지_않거나_만료되면_400을_반환한다() throws Exception {
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
    void 카카오_인증이_실패하면_401을_반환한다() throws Exception {
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
    void 로그인_티켓이_유효하지_않거나_만료되면_401을_반환한다() throws Exception {
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
    void OAuth_제공자가_실패하면_503을_반환한다() throws Exception {
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
    void 인증_DB가_실패하면_503을_반환한다() throws Exception {
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
    void 활성_회원이_없으면_404를_반환한다() throws Exception {
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
    void OAuth_상태_생성이_실패하면_500을_반환한다() throws Exception {
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
    void 로그인_티켓_발급이_실패하면_500을_반환한다() throws Exception {
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
    void 내부_오류는_구조화된_오류_코드와_stack_trace를_로그에_남긴다() throws Exception {
        mockMvc.perform(get("/test/illegal-state"))
                .andExpect(status().isInternalServerError());

        ILoggingEvent event = logAppender.list.stream()
                .filter(logEvent -> "api_exception".equals(keyValue(logEvent, "event")))
                .filter(logEvent -> "INTERNAL_SERVER_ERROR".equals(keyValue(logEvent, "error_code")))
                .findFirst()
                .orElseThrow();

        org.assertj.core.api.Assertions.assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy()).isNotNull();
    }

    @Test
    void 인증이_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/test/authentication-missing"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"UNAUTHORIZED\",\"data\":null}"));
    }

    @ParameterizedTest
    @CsvSource({
            "PATCH, /trips/7, INVALID_TRIP_REQUEST",
            "POST, /trips/7, INVALID_REQUEST",
            "PATCH, /test/unreadable, INVALID_REQUEST"
    })
    void 깨진_JSON은_여행_수정_요청에서만_여행_입력_오류로_반환한다(
            String method, String path, String message
    ) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(org.springframework.http.HttpMethod.valueOf(method), "/api" + path)
                        .contextPath("/api")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"" + message + "\",\"data\":null}"));
    }

    @RestController
    static class TestController {
        @GetMapping("/test/story-generation-forbidden")
        void storyGenerationForbidden() {
            throw new com.yeodam.yeodambe.common.exception.StoryGenerationForbiddenException();
        }

        @GetMapping("/test/invalid-story-request")
        void invalidStoryRequest() {
            throw new com.yeodam.yeodambe.common.exception.InvalidStoryRequestException();
        }

        @org.springframework.web.bind.annotation.RequestMapping(
                path = {"/trips/{tripId}", "/test/unreadable"},
                method = {org.springframework.web.bind.annotation.RequestMethod.PATCH,
                        org.springframework.web.bind.annotation.RequestMethod.POST})
        void unreadableRequest(
                @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body
        ) {
        }


        @GetMapping("/test/trip-update-not-allowed")
        void tripUpdateNotAllowed() {
            throw new TripUpdateNotAllowedException();
        }

        @GetMapping("/test/invalid-attachment")
        void invalidAttachment() {
            throw new InvalidAttachmentUploadException();
        }

        @GetMapping("/test/trip-not-found")
        void tripNotFound() {
            throw new TripNotFoundException();
        }

        @GetMapping("/test/trip-deletion-not-allowed")
        void tripDeletionNotAllowed() {
            throw new TripDeletionNotAllowedException();
        }

        @GetMapping("/test/trip-detail-not-available")
        void tripDetailNotAvailable() {
            throw new TripDetailNotAvailableException();
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

        @GetMapping("/test/invalid-place-folder-cursor")
        void invalidPlaceFolderCursor() {
            throw new InvalidPlaceFolderCursorException();
        }

        @GetMapping("/test/invalid-attachment-ids")
        void invalidAttachmentIds() {
            throw new InvalidAttachmentIdsException();
        }

        @GetMapping("/test/write-permission-required")
        void writePermissionRequired() {
            throw new WritePermissionRequiredException();
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

    private Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }
}
