package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.user.exception.DuplicateEmailException;
import com.yeodam.yeodambe.user.exception.InvalidNicknameException;
import com.yeodam.yeodambe.user.exception.DuplicateOAuthAccountException;
import com.yeodam.yeodambe.user.exception.KakaoAuthenticationFailedException;
import com.yeodam.yeodambe.user.exception.OAuthProviderUnavailableException;
import com.yeodam.yeodambe.user.exception.OAuthStateInvalidOrExpiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;


@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> handleBindException(BindException e) {
        return new ApiResponse<>("INVALID_REQUEST", null);
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

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleUnexpectedException(Exception e) {
        log.error("예상하지 못한 서버 오류가 발생했습니다.", e);
        return new ApiResponse<>("INTERNAL_SERVER_ERROR", null);
    }
}
