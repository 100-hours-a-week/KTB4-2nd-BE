package com.yeodam.yeodambe.user.exception;

public class LoginTicketIssueFailedException extends RuntimeException {

    public LoginTicketIssueFailedException(Throwable cause) {
        super("로그인 티켓 발급에 실패했습니다.", cause);
    }
}
