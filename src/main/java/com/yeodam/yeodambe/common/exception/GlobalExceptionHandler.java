package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.common.response.ApiResponse;
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
        return new ApiResponse<>("INVALID_PLACE_QUERY", null);
    }

    @ExceptionHandler(MapProviderUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiResponse<Void> handleMapProviderUnavailableException(MapProviderUnavailableException e) {
        log.warn("지도 제공자 호출에 실패했습니다.", e);
        return new ApiResponse<>("MAP_PROVIDER_UNAVAILABLE", null);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> handleUnexpectedException(Exception e) {
        log.error("예상하지 못한 서버 오류가 발생했습니다.", e);
        return new ApiResponse<>("INTERNAL_SERVER_ERROR", null);
    }
}
