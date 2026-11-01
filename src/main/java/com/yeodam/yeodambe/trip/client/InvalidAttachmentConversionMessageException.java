package com.yeodam.yeodambe.trip.client;

public class InvalidAttachmentConversionMessageException extends RuntimeException {
    public InvalidAttachmentConversionMessageException() {
        super("이미지 변환 메시지의 작업 또는 경로가 유효하지 않습니다.");
    }
}
