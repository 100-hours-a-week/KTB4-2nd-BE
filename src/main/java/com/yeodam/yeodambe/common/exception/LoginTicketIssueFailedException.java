package com.yeodam.yeodambe.common.exception;

public class LoginTicketIssueFailedException extends RuntimeException {

    public LoginTicketIssueFailedException(Throwable cause) {
        super(CommonExceptionMessage.LOGIN_TICKET_ISSUE_FAILED.message(), cause);
    }
}
