package com.yeodam.yeodambe.trip.exception;

public enum TripInternalErrorMessage {
    ATTACHMENT_CURSOR_ENCODE_FAILED("첨부 목록 커서를 생성할 수 없습니다."),
    PLACE_FOLDER_CURSOR_ENCODE_FAILED("장소 폴더 목록 커서를 생성할 수 없습니다.");

    private final String message;

    TripInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}