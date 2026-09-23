package com.yeodam.yeodambe.common.exception;

public class PlaceFolderNotFoundException extends RuntimeException {

    public PlaceFolderNotFoundException() {
        super("장소 폴더를 찾을 수 없습니다.");
    }
}