package com.yeodam.yeodambe.migrationprobe.exception;

public class MigrationProbeStoreUnavailableException extends RuntimeException {

    private final String failureStage;
    private final String failureType;

    public MigrationProbeStoreUnavailableException(String failureStage, RuntimeException cause) {
        // TransactionTemplate도 callback 예외를 출력하므로 원본 DB cause를 연결하지 않는다.
        super(MigrationProbeInternalErrorMessage.STORE_UNAVAILABLE.message());
        this.failureStage = failureStage;
        this.failureType = cause.getClass().getSimpleName();
    }

    public String getFailureType() {
        return failureType;
    }

    public String getFailureStage() {
        return failureStage;
    }
}
