package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.user.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AiStatusUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handleAiStatusUnavailable(AiStatusUnavailableException e) {
        log.warn("AI 사진 분석 상태를 조회할 수 없습니다.", e);
        return new ApiResponse<>("AI_STATUS_UNAVAILABLE", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> handleUnsupportedContentType(
            HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        if (request.getServletPath().matches("^/trips/[^/]+/initial-attachments$")) {
            return ResponseEntity.badRequest().body(new ApiResponse<>("INVALID_ATTACHMENT_UPLOAD", null));
        }
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiResponse<>("UNSUPPORTED_MEDIA_TYPE", null));
    }

    @ExceptionHandler(MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidMultipart(MultipartException e) {
        return new ApiResponse<>("INVALID_ATTACHMENT_UPLOAD", null);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handleAuthenticationStoreUnavailable(
            DataAccessResourceFailureException e
    ) {
        log.warn("인증 저장소에 연결할 수 없습니다.", e);
        return new ApiResponse<>("AUTH_STORE_UNAVAILABLE", null);
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleAuthenticationMissing(AuthenticationCredentialsNotFoundException e) {
        return new ApiResponse<>("UNAUTHORIZED", null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleUnreadableRequest(HttpMessageNotReadableException e) {
        return new ApiResponse<>("INVALID_REQUEST", null);
    }

    @ExceptionHandler(InvalidTripRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidTripRequest(InvalidTripRequestException e) {
        return new ApiResponse<>("INVALID_TRIP_REQUEST", null);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleBindException(BindException e) {
        return new ApiResponse<>("INVALID_REQUEST", null);
    }

    @ExceptionHandler(TripNameDuplicatedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleTripNameDuplicatedException(TripNameDuplicatedException e) {
        return new ApiResponse<>("TRIP_NAME_DUPLICATED", null);
    }

    @ExceptionHandler(PlaceQueryProviderUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handlePlaceQueryProviderUnavailableException(PlaceQueryProviderUnavailableException e) {
        log.warn("지역 검색 제공자 호출에 실패했습니다.", e);
        return new ApiResponse<>("MAP_PROVIDER_UNAVAILABLE", null);
    }

    @ExceptionHandler(DuplicateEmailException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleDuplicateEmailException(
            DuplicateEmailException e
    ) {
        return new ApiResponse<>("EMAIL_ALREADY_IN_USE", null);
    }

    @ExceptionHandler(InvalidNicknameException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidNicknameException(
            InvalidNicknameException e
    ) {
        return new ApiResponse<>("INVALID_NICKNAME", null);
    }

    @ExceptionHandler(InvalidEmailException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidEmailException(InvalidEmailException e) {
        return new ApiResponse<>("INVALID_EMAIL_FORMAT", null);
    }

    @ExceptionHandler(DuplicateOAuthAccountException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleDuplicateOAuthAccountException(
            DuplicateOAuthAccountException e
    ) {
        return new ApiResponse<>("ACCOUNT_ALREADY_REGISTERED", null);
    }

    @ExceptionHandler(OAuthStateInvalidOrExpiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleOAuthStateInvalidOrExpiredException(
            OAuthStateInvalidOrExpiredException e
    ) {
        return new ApiResponse<>(
                "OAUTH_STATE_INVALID_OR_EXPIRED",
                null
        );
    }

    @ExceptionHandler(OAuthStateCreateFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleOAuthStateCreateFailed(
            OAuthStateCreateFailedException e
    ) {
        log.error("OAuth state 생성에 실패했습니다.", e);
        return new ApiResponse<>("OAUTH_STATE_CREATE_FAILED", null);
    }

    @ExceptionHandler(KakaoAuthenticationFailedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleKakaoAuthenticationFailedException(
            KakaoAuthenticationFailedException e
    ) {
        return new ApiResponse<>(
                "KAKAO_AUTHENTICATION_FAILED",
                null
        );
    }

    @ExceptionHandler(OAuthProviderUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handleOAuthProviderUnavailableException(
            OAuthProviderUnavailableException e
    ) {
        log.warn("OAuth 제공자 호출에 실패했습니다.", e);

        return new ApiResponse<>(
                "OAUTH_PROVIDER_UNAVAILABLE",
                null
        );
    }

    @ExceptionHandler(LoginTicketInvalidOrExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleLoginTicketInvalidOrExpiredException(
            LoginTicketInvalidOrExpiredException e
    ) {

        return new ApiResponse<>(
                "LOGIN_TICKET_INVALID_OR_EXPIRED",
                null
        );
    }

    @ExceptionHandler(LoginTicketIssueFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleLoginTicketIssueFailed(
            LoginTicketIssueFailedException e
    ) {
        log.error("로그인 티켓 발급에 실패했습니다.", e);
        return new ApiResponse<>("LOGIN_TICKET_ISSUE_FAILED", null);
    }

    @ExceptionHandler(OnboardingTokenInvalidOrExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleOnboardingTokenInvalidOrExpired(
            OnboardingTokenInvalidOrExpiredException e
    ) {
        return new ApiResponse<>("ONBOARDING_TOKEN_INVALID_OR_EXPIRED", null);
    }

    @ExceptionHandler(OnboardingTokenRequiredException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ApiResponse<Void> handleOnboardingTokenRequired(
            OnboardingTokenRequiredException e
    ) {
        return new ApiResponse<>("ONBOARDING_TOKEN_REQUIRED", null);
    }

    @ExceptionHandler(RefreshTokenInvalidOrExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    ApiResponse<Void> handleRefreshTokenInvalidOrExpired(
            RefreshTokenInvalidOrExpiredException e
    ) {
        return new ApiResponse<>("REFRESH_TOKEN_INVALID_OR_EXPIRED", null);
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleUserNotFound(
            UserNotFoundException e
    ) {
        return new ApiResponse<>("USER_NOT_FOUND", null);
    }

    @ExceptionHandler(WithdrawalFailedException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleWithdrawalFailed(
            WithdrawalFailedException exception
    ) {
        log.error("회원 탈퇴 처리에 실패했습니다.", exception);
        return new ApiResponse<>("WITHDRAWAL_FAILED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("잘못된 내부 인자가 전달됐습니다.", e);
        return new ApiResponse<>("INTERNAL_SERVER_ERROR", null);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleIllegalStateException(IllegalStateException e) {
        log.error("잘못된 내부 상태가 발생했습니다.", e);
        return new ApiResponse<>("INTERNAL_SERVER_ERROR", null);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleUnexpectedException(Exception e) {
        log.error("예상하지 못한 서버 오류가 발생했습니다.", e);
        return new ApiResponse<>("INTERNAL_SERVER_ERROR", null);
    }

    @ExceptionHandler(TripNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleTripNotFound(TripNotFoundException e) {
        return new ApiResponse<>("TRIP_NOT_FOUND", null);
    }

    @ExceptionHandler(AttachmentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handleAttachmentNotFound(
            AttachmentNotFoundException exception
    ) {
        return new ApiResponse<>("ATTACHMENT_NOT_FOUND", null);
    }

    @ExceptionHandler(PlaceFolderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiResponse<Void> handlePlaceFolderNotFound(
            PlaceFolderNotFoundException exception
    ) {
        return new ApiResponse<>("PLACE_FOLDER_NOT_FOUND", null);
    }

    @ExceptionHandler(InvalidCursorException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidCursor(
            InvalidCursorException exception
    ) {
        return new ApiResponse<>("INVALID_CURSOR", null);
    }

    @ExceptionHandler(TripInitialAttachmentUploadNotAllowedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleInitialAttachmentUploadNotAllowed(
            TripInitialAttachmentUploadNotAllowedException e
    ) {
        return new ApiResponse<>("TRIP_INITIAL_ATTACHMENT_UPLOAD_NOT_ALLOWED", null);
    }

    @ExceptionHandler(TripProcessingCannotBeCanceledException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiResponse<Void> handleProcessingCannotBeCanceled(
            TripProcessingCannotBeCanceledException e
    ) {
        return new ApiResponse<>("TRIP_PROCESSING_CANNOT_BE_CANCELED", null);
    }

    @ExceptionHandler(InvalidAttachmentUploadException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleInvalidAttachmentUpload(InvalidAttachmentUploadException e) {
        return new ApiResponse<>("INVALID_ATTACHMENT_UPLOAD", null);
    }

    @ExceptionHandler({
            AttachmentUploadLimitExceededException.class,
            MaxUploadSizeExceededException.class
    })
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    ApiResponse<Void> handleAttachmentUploadLimitExceeded(Exception e) {
        return new ApiResponse<>("ATTACHMENT_UPLOAD_LIMIT_EXCEEDED", null);
    }

    @ExceptionHandler(UnsupportedAttachmentFormatException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    ApiResponse<Void> handleUnsupportedAttachmentFormat(UnsupportedAttachmentFormatException e) {
        return new ApiResponse<>("UNSUPPORTED_ATTACHMENT_FORMAT", null);
    }
}
