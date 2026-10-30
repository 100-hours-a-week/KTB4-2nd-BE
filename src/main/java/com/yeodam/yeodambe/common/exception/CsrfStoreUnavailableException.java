package com.yeodam.yeodambe.common.exception;

import org.springframework.dao.DataAccessResourceFailureException;

public class CsrfStoreUnavailableException extends DataAccessResourceFailureException {
    public CsrfStoreUnavailableException() {
        super(CommonExceptionMessage.CSRF_STORE_UNAVAILABLE.message());
    }
}
