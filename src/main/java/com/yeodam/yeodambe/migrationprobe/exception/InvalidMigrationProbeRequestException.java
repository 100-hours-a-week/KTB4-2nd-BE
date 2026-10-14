package com.yeodam.yeodambe.migrationprobe.exception;

public class InvalidMigrationProbeRequestException extends RuntimeException {

    public InvalidMigrationProbeRequestException() {
        super("DB 전환 시험 요청이 올바르지 않습니다.");
    }
}
