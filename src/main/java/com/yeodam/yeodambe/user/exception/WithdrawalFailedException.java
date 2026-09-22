package com.yeodam.yeodambe.user.exception;

public class WithdrawalFailedException extends RuntimeException {

    public WithdrawalFailedException() {
        super("회원 탈퇴 처리에 실패했습니다.");
    }

    public WithdrawalFailedException(Throwable cause) {
        super("회원 탈퇴 처리에 실패했습니다.", cause);
    }
}