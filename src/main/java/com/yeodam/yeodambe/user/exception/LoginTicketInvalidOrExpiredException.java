package com.yeodam.yeodambe.user.exception;

public class LoginTicketInvalidOrExpiredException extends RuntimeException {
    public LoginTicketInvalidOrExpiredException() {
        super("로그인 티켓이 잘못되었습니다.");
    }
}
