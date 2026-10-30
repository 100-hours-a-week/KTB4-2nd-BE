package com.yeodam.yeodambe.common.exception;

public class LoginTicketInvalidOrExpiredException extends RuntimeException {
    public LoginTicketInvalidOrExpiredException() {
        super(CommonExceptionMessage.LOGIN_TICKET_INVALID.message());
    }
}
