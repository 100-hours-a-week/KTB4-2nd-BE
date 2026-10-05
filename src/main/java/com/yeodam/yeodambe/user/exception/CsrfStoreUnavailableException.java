package com.yeodam.yeodambe.user.exception;

import org.springframework.dao.DataAccessResourceFailureException;

public class CsrfStoreUnavailableException extends DataAccessResourceFailureException {
    public CsrfStoreUnavailableException() {
        super("CSRF 저장소를 사용할 수 없습니다.");
    }
}
