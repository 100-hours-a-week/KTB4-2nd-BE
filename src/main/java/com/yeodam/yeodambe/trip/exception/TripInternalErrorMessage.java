package com.yeodam.yeodambe.trip.exception;

public enum TripInternalErrorMessage {
    TRIP_LIST_CURSOR_ENCODE_FAILED("여행 목록 커서를 생성할 수 없습니다."),
    ATTACHMENT_CURSOR_ENCODE_FAILED("첨부 목록 커서를 생성할 수 없습니다."),
    PLACE_FOLDER_CURSOR_ENCODE_FAILED("장소 폴더 목록 커서를 생성할 수 없습니다."),
    UNSUPPORTED_DERIVATIVE_FILE_FORMAT("지원하지 않는 파생 파일 형식입니다."),
    CURRENT_EXECUTION_AI_RESULT_MISMATCH("현재 실행과 AI 결과가 일치하지 않습니다."),
    AI_RESULT_FORMAT_INVALID("AI 결과 형식이 올바르지 않습니다."),
    AI_UNCLASSIFIED_PLACE_REFERENCE_INVALID("미분류 사진의 장소 참조가 올바르지 않습니다."),
    AI_PLACE_RESULT_INVALID("AI 장소 결과가 올바르지 않습니다."),
    AI_RESULT_DUPLICATE_ATTACHMENT("AI 결과에 중복된 사진이 있습니다."),
    AI_RESULT_ATTACHMENT_LIST_MISMATCH("AI 결과의 사진 목록이 다릅니다."),
    REPRESENTATIVE_ATTACHMENT_MISSING("대표 사진이 없습니다."),
    REPRESENTATIVE_ATTACHMENT_NOT_IN_PLACE("대표 사진이 장소에 없습니다."),
    AI_ATTACHMENT_ISSUE_INVALID("AI 사진 이슈가 올바르지 않습니다."),
    UNCLASSIFIED_ATTACHMENT_ISSUE_MISSING("미분류 사진의 이슈가 없습니다."),
    AI_PLACE_NAME_RESULT_INVALID("AI 장소명 결과가 올바르지 않습니다."),
    AI_REGION_SOURCE_INVALID("AI 지역 출처가 올바르지 않습니다."),
    AI_CAPTURED_AT_INVALID("AI 촬영 시각이 올바르지 않습니다."),
    AI_PLACE_COORDINATE_MISSING("AI 장소 좌표가 없습니다."),
    AI_COORDINATE_INVALID("AI 좌표가 올바르지 않습니다."),
    AI_SCORE_INVALID("AI 평가값이 올바르지 않습니다."),
    S3_OBJECT_KEY_MISSING("S3 객체 키가 없습니다."),
    DERIVED_ATTACHMENT_COUNT_MISMATCH("파생 사진 수가 다릅니다."),
    DERIVED_ATTACHMENT_RESULT_INVALID("파생 사진 결과가 올바르지 않습니다."),
    TRIP_REGION_MISSING("여행 지역이 없습니다."),
    SOURCE_AND_DERIVED_ATTACHMENT_COUNT_MISMATCH("원본과 파생 사진 수가 다릅니다."),
    SOURCE_AND_DERIVED_ATTACHMENT_ORDER_MISMATCH("원본과 파생 사진의 순서가 다릅니다."),
    TRIP_NOT_ELIGIBLE_FOR_FAILURE("실패 처리할 여행 상태가 아닙니다."),
    REGION_CATALOG_EMPTY("regions.json의 regions가 비어 있습니다."),
    REGION_CATALOG_ENTRY_INVALID("regions.json의 지역 정보가 잘못되었습니다: %s"),
    REGION_CATALOG_DUPLICATE_CODE("regions.json의 지역 코드가 중복됩니다: %s"),
    ATTACHMENT_OBJECT_CLEANUP_REQUIRES_DELETION("삭제되지 않은 첨부는 객체 정리를 완료할 수 없습니다."),
    SOURCE_KEY_MIME_TYPE_COUNT_MISMATCH("원본 키와 MIME 타입 수가 다릅니다."),
    PHOTO_WORK_DIRECTORY_CREATE_FAILED("사진 작업 디렉터리를 만들 수 없습니다."),
    DERIVED_ATTACHMENT_CREATE_FAILED("파생 사진 생성에 실패했습니다."),
    EXIF_EXTRACTION_FAILED("EXIF 추출에 실패했습니다."),
    EXIF_RESULT_MISSING("EXIF 결과가 없습니다."),
    EXIF_TOOL_EXECUTION_FAILED("EXIF 추출 도구를 실행할 수 없습니다."),
    EXIF_EXTRACTION_INTERRUPTED("EXIF 추출이 중단됐습니다."),
    COMMAND_TIMEOUT("%s 실행 시간 초과"),
    COMMAND_FAILED("%s 실행 실패"),
    COMMAND_UNAVAILABLE("%s 실행 불가"),
    COMMAND_INTERRUPTED("%s 실행 중단"),
    ATTACHMENT_ZIP_CREATE_FAILED("첨부 ZIP 생성에 실패했습니다."),
    KAKAO_PLACE_CONCURRENCY_INVALID("Kakao 동시 호출 수가 올바르지 않습니다."),
    KAKAO_PLACE_LOOKUP_INTERRUPTED("Kakao 장소명 조회가 중단되었습니다."),
    KAKAO_PLACE_LOOKUP_FAILED("Kakao 장소명 조회에 실패했습니다."),
    AI_PLACE_COORDINATE_INVALID("AI 장소 좌표가 올바르지 않습니다.");

    private final String message;

    TripInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
