package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.user.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RedisConnectionFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handleRedisConnectionFailure(RedisConnectionFailureException e) {
        log.warn("인증 저장소에 연결할 수 없습니다.", e);
        return new ApiResponse<>("AUTH_STORE_UNAVAILABLE", null);
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

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleUnexpectedException(Exception e) {
        log.error("예상하지 못한 서버 오류가 발생했습니다.", e);
        return new ApiResponse<>("INTERNAL_SERVER_ERROR", null);
    }
}
