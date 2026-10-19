package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import org.springframework.http.HttpStatus;

public class MigrationTripAdmissionException extends RuntimeException {
    private final HttpStatus status;
    private final ErrorMessage error;

    public MigrationTripAdmissionException(HttpStatus status, ErrorMessage error) {
        super(error.name());
        this.status = status;
        this.error = error;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorMessage error() {
        return error;
    }
}
