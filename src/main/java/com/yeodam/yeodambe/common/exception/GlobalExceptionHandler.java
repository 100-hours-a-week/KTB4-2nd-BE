package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;

import com.yeodam.yeodambe.story.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.exception.StoryNotFoundException;
import com.yeodam.yeodambe.story.exception.InvalidStoryRequestException;
import com.yeodam.yeodambe.story.exception.StoryGenerationForbiddenException;
import com.yeodam.yeodambe.user.exception.*;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.slf4j.MDC;
import org.springframework.validation.BindException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.web.servlet.HandlerMapping;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiStatusUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handleAiStatusUnavailable(AiStatusUnavailableException e) {
        logFailure(log.atWarn(), ErrorMessage.AI_STATUS_UNAVAILABLE,
                "AI 사진 분석 상태를 조회할 수 없습니다.", e);
        return new ApiResponse<>(ErrorMessage.AI_STATUS_UNAVAILABLE, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> handleUnsupportedContentType(
            HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        if (request.getServletPath().matches("^/trips/[^/]+/initial-attachments$")) {
            return ResponseEntity.badRequest().body(new ApiResponse<>(ErrorMessage.INVALID_ATTACHMENT_UPLOAD, null));
        }
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiResponse<>(ErrorMessage.UNSUPPORTED_MEDIA_TYPE, null));
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidMultipart(MultipartException e) {
        return new ApiResponse<>(ErrorMessage.INVALID_ATTACHMENT_UPLOAD, null);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handleAuthenticationStoreUnavailable(
            DataAccessResourceFailureException e
    ) {
        logFailure(log.atWarn(), ErrorMessage.AUTH_STORE_UNAVAILABLE,
                "인증 저장소에 연결할 수 없습니다.", e);
        return new ApiResponse<>(ErrorMessage.AUTH_STORE_UNAVAILABLE, null);
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleAuthenticationMissing(AuthenticationCredentialsNotFoundException e) {
        return new ApiResponse<>(ErrorMessage.UNAUTHORIZED, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleUnreadableRequest(
            HttpMessageNotReadableException e,
            HttpServletRequest request
    ) {
        Object matchedPath = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if ("PATCH".equals(request.getMethod())
                && "/trips/{tripId}".equals(matchedPath)) {
            return new ApiResponse<>(ErrorMessage.INVALID_TRIP_REQUEST, null);
        }
        return new ApiResponse<>(ErrorMessage.INVALID_REQUEST, null);
    }

    @ExceptionHandler(InvalidTripRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidTripRequest(InvalidTripRequestException e) {
        return new ApiResponse<>(ErrorMessage.INVALID_TRIP_REQUEST, null);
    }

    @ExceptionHandler(InvalidTripListFilterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidTripListFilter(InvalidTripListFilterException e) {
        return new ApiResponse<>(ErrorMessage.INVALID_TRIP_LIST_FILTER, null);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleBindException(BindException e) {
        return new ApiResponse<>(ErrorMessage.INVALID_REQUEST, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        return new ApiResponse<>(ErrorMessage.INVALID_REQUEST, null);
    }

    @ExceptionHandler(TripNameDuplicatedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleTripNameDuplicatedException(TripNameDuplicatedException e) {
        return new ApiResponse<>(ErrorMessage.TRIP_NAME_DUPLICATED, null);
    }

    @ExceptionHandler(TripUpdateNotAllowedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleTripUpdateNotAllowed(
            TripUpdateNotAllowedException e
    ) {
        return new ApiResponse<>(ErrorMessage.TRIP_UPDATE_NOT_ALLOWED, null);
    }

    @ExceptionHandler(PlaceQueryProviderUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handlePlaceQueryProviderUnavailableException(PlaceQueryProviderUnavailableException e) {
        logFailure(log.atWarn(), ErrorMessage.MAP_PROVIDER_UNAVAILABLE,
                "지역 검색 제공자 호출에 실패했습니다.", e);
        return new ApiResponse<>(ErrorMessage.MAP_PROVIDER_UNAVAILABLE, null);
    }

    @ExceptionHandler(DuplicateEmailException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleDuplicateEmailException(
            DuplicateEmailException e
    ) {
        return new ApiResponse<>(ErrorMessage.EMAIL_ALREADY_IN_USE, null);
    }

    @ExceptionHandler(InvalidNicknameException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidNicknameException(
            InvalidNicknameException e
    ) {
        return new ApiResponse<>(ErrorMessage.INVALID_NICKNAME, null);
    }

    @ExceptionHandler(InvalidEmailException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidEmailException(InvalidEmailException e) {
        return new ApiResponse<>(ErrorMessage.INVALID_EMAIL_FORMAT, null);
    }

    @ExceptionHandler(DuplicateOAuthAccountException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleDuplicateOAuthAccountException(
            DuplicateOAuthAccountException e
    ) {
        return new ApiResponse<>(ErrorMessage.ACCOUNT_ALREADY_REGISTERED, null);
    }

    @ExceptionHandler(OAuthStateInvalidOrExpiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleOAuthStateInvalidOrExpiredException(
            OAuthStateInvalidOrExpiredException e
    ) {
        return new ApiResponse<>(
                ErrorMessage.OAUTH_STATE_INVALID_OR_EXPIRED,
                null
        );
    }

    @ExceptionHandler(OAuthStateCreateFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleOAuthStateCreateFailed(
            OAuthStateCreateFailedException e
    ) {
        logFailure(log.atError(), ErrorMessage.OAUTH_STATE_CREATE_FAILED,
                "OAuth state 생성에 실패했습니다.", e);
        return new ApiResponse<>(ErrorMessage.OAUTH_STATE_CREATE_FAILED, null);
    }

    @ExceptionHandler(KakaoAuthenticationFailedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleKakaoAuthenticationFailedException(
            KakaoAuthenticationFailedException e
    ) {
        return new ApiResponse<>(
                ErrorMessage.KAKAO_AUTHENTICATION_FAILED,
                null
        );
    }

    @ExceptionHandler(OAuthProviderUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handleOAuthProviderUnavailableException(
            OAuthProviderUnavailableException e
    ) {
        logFailure(log.atWarn(), ErrorMessage.OAUTH_PROVIDER_UNAVAILABLE,
                "OAuth 제공자 호출에 실패했습니다.", e);

        return new ApiResponse<>(
                ErrorMessage.OAUTH_PROVIDER_UNAVAILABLE,
                null
        );
    }

    @ExceptionHandler(LoginTicketInvalidOrExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleLoginTicketInvalidOrExpiredException(
            LoginTicketInvalidOrExpiredException e
    ) {

        return new ApiResponse<>(
                ErrorMessage.LOGIN_TICKET_INVALID_OR_EXPIRED,
                null
        );
    }

    @ExceptionHandler(LoginTicketIssueFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleLoginTicketIssueFailed(
            LoginTicketIssueFailedException e
    ) {
        logFailure(log.atError(), ErrorMessage.LOGIN_TICKET_ISSUE_FAILED,
                "로그인 티켓 발급에 실패했습니다.", e);
        return new ApiResponse<>(ErrorMessage.LOGIN_TICKET_ISSUE_FAILED, null);
    }

    @ExceptionHandler(OnboardingTokenInvalidOrExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleOnboardingTokenInvalidOrExpired(
            OnboardingTokenInvalidOrExpiredException e
    ) {
        return new ApiResponse<>(ErrorMessage.ONBOARDING_TOKEN_INVALID_OR_EXPIRED, null);
    }

    @ExceptionHandler(OnboardingTokenRequiredException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiResponse<Void> handleOnboardingTokenRequired(
            OnboardingTokenRequiredException e
    ) {
        return new ApiResponse<>(ErrorMessage.ONBOARDING_TOKEN_REQUIRED, null);
    }

    @ExceptionHandler(RefreshTokenInvalidOrExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleRefreshTokenInvalidOrExpired(
            RefreshTokenInvalidOrExpiredException e
    ) {
        return new ApiResponse<>(ErrorMessage.REFRESH_TOKEN_INVALID_OR_EXPIRED, null);
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleUserNotFound(
            UserNotFoundException e
    ) {
        return new ApiResponse<>(ErrorMessage.USER_NOT_FOUND, null);
    }

    @ExceptionHandler(WithdrawalFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleWithdrawalFailed(
            WithdrawalFailedException exception
    ) {
        logFailure(log.atError(), ErrorMessage.WITHDRAWAL_FAILED,
                "회원 탈퇴 처리에 실패했습니다.", exception);
        return new ApiResponse<>(ErrorMessage.WITHDRAWAL_FAILED, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        logFailure(log.atError(), ErrorMessage.INTERNAL_SERVER_ERROR,
                "잘못된 내부 인자가 전달됐습니다.", e);
        return new ApiResponse<>(ErrorMessage.INTERNAL_SERVER_ERROR, null);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleIllegalStateException(IllegalStateException e) {
        logFailure(log.atError(), ErrorMessage.INTERNAL_SERVER_ERROR,
                "잘못된 내부 상태가 발생했습니다.", e);
        return new ApiResponse<>(ErrorMessage.INTERNAL_SERVER_ERROR, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleResourceNotFound(NoResourceFoundException e) {
        return new ApiResponse<>(ErrorMessage.RESOURCE_NOT_FOUND, null);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleUnexpectedException(Exception e) {
        logFailure(log.atError(), ErrorMessage.INTERNAL_SERVER_ERROR,
                "예상하지 못한 서버 오류가 발생했습니다.", e);
        return new ApiResponse<>(ErrorMessage.INTERNAL_SERVER_ERROR, null);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleMethodValidation(HandlerMethodValidationException e) {
        return new ApiResponse<>(ErrorMessage.INVALID_REQUEST, null);
    }

    @ExceptionHandler(InvalidStoryRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidStoryRequest(InvalidStoryRequestException e) {
        return new ApiResponse<>(ErrorMessage.INVALID_STORY_REQUEST, null);
    }

    @ExceptionHandler(StoryGenerationForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiResponse<Void> handleStoryGenerationForbidden(
            StoryGenerationForbiddenException e
    ) {
        return new ApiResponse<>(ErrorMessage.STORY_GENERATION_FORBIDDEN, null);
    }

    @ExceptionHandler(StoryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleStoryNotFound(StoryNotFoundException e) {
        return new ApiResponse<>(ErrorMessage.STORY_NOT_FOUND, null);
    }

    @ExceptionHandler(StoryDataIntegrityException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleStoryDataIntegrity(StoryDataIntegrityException e) {
        logFailure(log.atError(), ErrorMessage.INTERNAL_SERVER_ERROR,
                "스토리 조회 데이터의 정합성 검증에 실패했습니다.", e);
        return new ApiResponse<>(ErrorMessage.INTERNAL_SERVER_ERROR, null);
    }

    @ExceptionHandler(TripNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleTripNotFound(TripNotFoundException e) {
        return new ApiResponse<>(ErrorMessage.TRIP_NOT_FOUND, null);
    }

    @ExceptionHandler(TripDeletionNotAllowedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleTripDeletionNotAllowed(TripDeletionNotAllowedException e) {
        return new ApiResponse<>(ErrorMessage.TRIP_DELETION_NOT_ALLOWED, null);
    }

    @ExceptionHandler(TripDetailNotAvailableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleTripDetailNotAvailable(TripDetailNotAvailableException e) {
        return new ApiResponse<>(ErrorMessage.TRIP_DETAIL_NOT_AVAILABLE, null);
    }

    @ExceptionHandler(InvalidAttachmentIdsException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidAttachmentIds(
            InvalidAttachmentIdsException exception
    ) {
        return new ApiResponse<>(ErrorMessage.INVALID_ATTACHMENT_IDS, null);
    }

    @ExceptionHandler(WritePermissionRequiredException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiResponse<Void> handleWritePermissionRequired(
            WritePermissionRequiredException exception
    ) {
        return new ApiResponse<>(ErrorMessage.WRITE_PERMISSION_REQUIRED, null);
    }

    @ExceptionHandler(AttachmentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleAttachmentNotFound(
            AttachmentNotFoundException exception
    ) {
        return new ApiResponse<>(ErrorMessage.ATTACHMENT_NOT_FOUND, null);
    }

    @ExceptionHandler(PlaceFolderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handlePlaceFolderNotFound(
            PlaceFolderNotFoundException exception
    ) {
        return new ApiResponse<>(ErrorMessage.PLACE_FOLDER_NOT_FOUND, null);
    }

    @ExceptionHandler(InvalidCursorException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidCursor(
            InvalidCursorException exception
    ) {
        return new ApiResponse<>(ErrorMessage.INVALID_CURSOR, null);
    }

    @ExceptionHandler(InvalidPlaceFolderCursorException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidPlaceFolderCursor(
            InvalidPlaceFolderCursorException exception
    ) {
        return new ApiResponse<>(ErrorMessage.INVALID_PLACE_FOLDER_CURSOR, null);
    }

    @ExceptionHandler(TripInitialAttachmentUploadNotAllowedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleInitialAttachmentUploadNotAllowed(
            TripInitialAttachmentUploadNotAllowedException e
    ) {
        return new ApiResponse<>(ErrorMessage.TRIP_INITIAL_ATTACHMENT_UPLOAD_NOT_ALLOWED, null);
    }

    @ExceptionHandler(TripProcessingCannotBeCanceledException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleProcessingCannotBeCanceled(
            TripProcessingCannotBeCanceledException e
    ) {
        return new ApiResponse<>(ErrorMessage.TRIP_PROCESSING_CANNOT_BE_CANCELED, null);
    }

    @ExceptionHandler(InvalidAttachmentUploadException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidAttachmentUpload(InvalidAttachmentUploadException e) {
        return new ApiResponse<>(ErrorMessage.INVALID_ATTACHMENT_UPLOAD, null);
    }

    @ExceptionHandler({
            AttachmentUploadLimitExceededException.class,
            MaxUploadSizeExceededException.class
    })
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    ApiResponse<Void> handleAttachmentUploadLimitExceeded(Exception e) {
        return new ApiResponse<>(ErrorMessage.ATTACHMENT_UPLOAD_LIMIT_EXCEEDED, null);
    }

    @ExceptionHandler(UnsupportedAttachmentFormatException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    ApiResponse<Void> handleUnsupportedAttachmentFormat(UnsupportedAttachmentFormatException e) {
        return new ApiResponse<>(ErrorMessage.UNSUPPORTED_ATTACHMENT_FORMAT, null);
    }

    private void logFailure(
            LoggingEventBuilder logEvent,
            ErrorMessage errorCode,
            String message,
            Exception exception
    ) {
        Sentry.captureException(exception, scope -> {
            scope.setTag("error_code", errorCode.name());

            String requestId = MDC.get("request_id");
            if (requestId != null) {
                scope.setTag("request_id", requestId);
            }
        });

        logEvent.addKeyValue("event", "api_exception")
                .addKeyValue("result", "failure")
                .addKeyValue("error_code", errorCode.name())
                .setCause(exception)
                .log(message);
    }
}
