package com.yeodam.yeodambe.common.exception;

public class WithdrawalFailedException extends RuntimeException {

    public WithdrawalFailedException() {
        super(CommonExceptionMessage.WITHDRAWAL_FAILED.message());
    }

    public WithdrawalFailedException(Throwable cause) {
        super(CommonExceptionMessage.WITHDRAWAL_FAILED.message(), cause);
    }
}