package com.yeodam.yeodambe.common.exception;

public class PlaceFolderNotFoundException extends RuntimeException {

    public PlaceFolderNotFoundException() {
        super(CommonExceptionMessage.PLACE_FOLDER_NOT_FOUND.message());
    }
}