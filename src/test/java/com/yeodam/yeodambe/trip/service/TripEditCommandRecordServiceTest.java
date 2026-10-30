package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripEditCommandMismatchException;
import com.yeodam.yeodambe.common.exception.TripEditCommandResultExpiredException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord.CommandType;
import com.yeodam.yeodambe.trip.repository.TripEditCommandRecordRepository;
import com.yeodam.yeodambe.trip.service.response.TripEditCommandResultResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripEditCommandRecordServiceTest {
    private static final LocalDateTime COMPLETED = LocalDateTime.of(2026, 10, 29, 12, 0);
    private static final String HASH = "a".repeat(64);
    private final TripEditCommandRecordRepository records = mock(TripEditCommandRecordRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final TripEditCommandRecordService service = new TripEditCommandRecordService(records, json);

    @Test
    void 사용자가_다르면_다른_처리결과를_반환하지_않는다() {
        when(records.findByUserIdAndRequestId(2L, "req-A")).thenReturn(Optional.empty());

        assertThat(service.findResult(2L, "req-A", 3L, CommandType.UPDATE_TRIP, HASH, COMPLETED)).isEmpty();
        verify(records).findByUserIdAndRequestId(2L, "req-A");
    }

    @ParameterizedTest
    @CsvSource({"4,UPDATE_TRIP,a", "3,DELETE_ATTACHMENTS,a", "3,UPDATE_TRIP,b"})
    void 동일번호의_다른_명령을_거절한다(Long tripId, CommandType type, String hash) {
        stored(result("SUCCEEDED", 7L));

        assertThatThrownBy(() -> service.findResult(1L, "req-A", tripId, type, hash.repeat(64), COMPLETED))
                .isInstanceOf(TripEditCommandMismatchException.class);
    }

    @ParameterizedTest
    @CsvSource({"SUCCEEDED,7", "REJECTED,"})
    void 성공과_검증거절의_기존_결과를_그대로_반환한다(String status, Long sequence) {
        ApiResponse<TripEditCommandResultResponse> original = result(status, sequence);
        stored(original);

        assertThat(service.findResult(1L, "req-A", 3L, CommandType.UPDATE_TRIP, HASH, COMPLETED.plusHours(1)))
                .contains(original);
    }

    @Test
    void 기록없음과_결과만료를_구분하고_재확인으로_기한을_연장하지_않는다() {
        when(records.findByUserIdAndRequestId(1L, "req-A")).thenReturn(Optional.empty());
        assertThat(service.findResult(1L, "req-A", 3L, CommandType.UPDATE_TRIP, HASH, COMPLETED)).isEmpty();
        stored(result("SUCCEEDED", 7L));
        LocalDateTime expiresAt = COMPLETED.plusHours(24);

        assertThat(service.findResult(1L, "req-A", 3L, CommandType.UPDATE_TRIP, HASH,
                expiresAt.minusNanos(1_000))).isPresent();
        assertThatThrownBy(() -> service.findResult(1L, "req-A", 3L, CommandType.UPDATE_TRIP, HASH, expiresAt))
                .isInstanceOf(TripEditCommandResultExpiredException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"{", "null", "{}"})
    void 손상된_저장결과를_미처리로_바꾸지_않는다(String body) {
        when(records.findByUserIdAndRequestId(1L, "req-A")).thenReturn(Optional.of(
                new TripEditCommandRecord(1L, 3L, "req-A", CommandType.UPDATE_TRIP, HASH, body, COMPLETED)));

        assertThatThrownBy(() -> service.findResult(1L, "req-A", 3L, CommandType.UPDATE_TRIP, HASH, COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-sha256"})
    void 잘못된_본문_식별값은_조회하거나_저장하지_않는다(String hash) {
        assertThatThrownBy(() -> service.findResult(1L, "req-A", 3L, CommandType.UPDATE_TRIP, hash, COMPLETED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"SUCCEEDED,", "SUCCEEDED,-1", "REJECTED,7", "PROCESSING,7", ",7"})
    void 확정되지_않았거나_잘못된_결과를_기록하지_않는다(String status, Long sequence) {
        assertThatThrownBy(() -> service.saveResult(1L, 3L, "req-A", CommandType.UPDATE_TRIP,
                HASH, result(status, sequence), COMPLETED)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 다른_요청이나_여행의_결과를_기록하지_않는다() {
        ApiResponse<TripEditCommandResultResponse> wrongRequest = new ApiResponse<>("TRIP_UPDATED",
                new TripEditCommandResultResponse("other", 3L, "SUCCEEDED", 1L));
        ApiResponse<TripEditCommandResultResponse> wrongTrip = new ApiResponse<>("TRIP_UPDATED",
                new TripEditCommandResultResponse("req-A", 4L, "SUCCEEDED", 1L));
        assertThatThrownBy(() -> service.saveResult(1L, 3L, "req-A", CommandType.UPDATE_TRIP,
                HASH, wrongRequest, COMPLETED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveResult(1L, 3L, "req-A", CommandType.UPDATE_TRIP,
                HASH, wrongTrip, COMPLETED)).isInstanceOf(IllegalArgumentException.class);
    }

    private void stored(ApiResponse<TripEditCommandResultResponse> result) {
        when(records.findByUserIdAndRequestId(1L, "req-A")).thenReturn(Optional.of(
                new TripEditCommandRecord(1L, 3L, "req-A", CommandType.UPDATE_TRIP, HASH,
                        json.writeValueAsString(result), COMPLETED)));
    }

    private ApiResponse<TripEditCommandResultResponse> result(String status, Long sequence) {
        return new ApiResponse<>("SUCCEEDED".equals(status) ? "TRIP_UPDATED" : "TRIP_UPDATE_NOT_ALLOWED",
                new TripEditCommandResultResponse("req-A", 3L, status, sequence));
    }
}
