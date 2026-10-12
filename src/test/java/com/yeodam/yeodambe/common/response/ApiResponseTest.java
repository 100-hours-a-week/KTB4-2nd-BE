package com.yeodam.yeodambe.common.response;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 성공_메시지를_기존_문자열_계약으로_직렬화한다() {
        ApiResponse<String> response = new ApiResponse<>(SuccessMessage.TRIP_CREATED, "data");

        assertThat(objectMapper.writeValueAsString(response))
                .isEqualTo("{\"message\":\"TRIP_CREATED\",\"data\":\"data\"}");
    }

    @Test
    void 오류_메시지를_기존_문자열_계약으로_직렬화한다() {
        ApiResponse<Void> response = new ApiResponse<>(ErrorMessage.TRIP_NOT_FOUND, null);

        assertThat(objectMapper.writeValueAsString(response))
                .isEqualTo("{\"message\":\"TRIP_NOT_FOUND\",\"data\":null}");
    }
}
