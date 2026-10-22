package com.yeodam.yeodambe.story.exception;

public enum StoryGenerationInternalErrorMessage {
    AI_STATUS_LOOKUP_FAILED("AI 스토리 생성 상태 조회에 실패했습니다."),
    AI_CANCEL_REQUEST_FAILED("AI 스토리 생성 취소 요청에 실패했습니다."),
    AI_RESPONSE_INVALID("AI 스토리 생성 응답이 올바르지 않습니다."),
    JOB_PROGRESS_INVALID("스토리 생성 진행률은 0부터 100 사이여야 합니다."),
    JOB_STORY_ID_REQUIRED("완료된 스토리의 ID가 필요합니다."),
    JOB_NOT_FOUND("스토리 생성 작업을 찾을 수 없습니다."),
    AI_EXECUTION_MISMATCH("AI 응답의 여행 또는 실행 식별자가 일치하지 않습니다."),
    OUTBOX_NOT_FOUND("스토리 생성 요청 기록을 찾을 수 없습니다."),
    STORY_REFERENCE_REQUIRED("스토리 여행과 분위기가 필요합니다."),
    STORY_TEXT_INVALID("스토리 문구는 필수이며 최대 40자입니다."),
    BLOCK_REFERENCE_REQUIRED("블록 참조와 날짜가 필요합니다."),
    BLOCK_MEMO_TOO_LONG("메모는 최대 125자입니다."),
    SAVED_STORY_MISMATCH("저장된 같은 스토리에만 연결할 수 있습니다."),
    SELECTED_PLACE_REFERENCE_REQUIRED("선택 장소 참조가 필요합니다."),
    BLOCK_COUNT_EXCEEDED("블록 개수: 최대 30개를 초과했습니다."),
    BLOCK_ATTACHMENT_MISSING("블록 관계: 첨부가 없습니다."),
    BLOCK_ATTACHMENT_TRIP_MISMATCH("블록 관계: 다른 여행의 첨부입니다."),
    BLOCK_FILE_MISSING("블록 관계: 파일이 없습니다."),
    DISPLAY_PLACE_MISSING("표시 조건: 장소가 없습니다."),
    DISPLAY_PLACE_TRIP_MISMATCH("표시 조건: 다른 여행의 장소입니다."),
    DISPLAY_DATE_MISSING("표시 조건: 스토리 날짜가 없습니다."),
    DISPLAY_PREVIEW_KEY_MISSING("표시 조건: 미리보기 키가 없습니다."),
    RESULT_SOURCE_INVALID("스토리 생성 장소 또는 날짜가 올바르지 않습니다."),
    RESULT_MESSAGE_UNREADABLE("스토리 결과 메시지를 읽을 수 없습니다."),
    GENERATION_FAILED("스토리 생성에 실패했습니다."),
    ACTIVE_JOB_REQUEST_REJECTED("진행 중인 스토리 작업이 있어 생성 요청을 거부했습니다."),
    ACTIVE_JOB_UNIQUE_CONFLICT("활성 작업 UNIQUE 충돌로 생성 요청을 거부했습니다."),
    STATUS_APPLICATION_FAILED("스토리 생성 상태 반영에 실패했습니다."),
    GENERATION_TIMED_OUT("스토리 요청 Kafka 발행 후 1시간이 지나 작업을 실패 처리했습니다."),
    READ_DATA_INTEGRITY_FAILED("스토리 조회 데이터의 정합성 검증에 실패했습니다.");
    private final String message;

    StoryGenerationInternalErrorMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}