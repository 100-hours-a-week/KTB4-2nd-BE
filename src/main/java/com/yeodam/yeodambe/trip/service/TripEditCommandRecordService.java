package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripEditCommandMismatchException;
import com.yeodam.yeodambe.common.exception.TripEditCommandResultExpiredException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord.CommandType;
import com.yeodam.yeodambe.trip.repository.TripEditCommandRecordRepository;
import com.yeodam.yeodambe.trip.service.response.TripEditCommandResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripEditCommandRecordService {
    private final TripEditCommandRecordRepository records;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<ApiResponse<TripEditCommandResultResponse>> findResult(
            Long userId,
            String requestId,
            Long tripId,
            CommandType commandType,
            String payloadHash,
            LocalDateTime now
    ) {
        validateCommand(userId, tripId, requestId, commandType, payloadHash, now);
        return records.findByUserIdAndRequestId(userId, requestId).map(record -> {
            if (!record.getRequestId().equals(requestId) || !record.getTripId().equals(tripId)
                    || record.getCommandType() != commandType || !record.getPayloadHash().equals(payloadHash)) {
                throw new TripEditCommandMismatchException();
            }
            if (!now.isBefore(record.getResultExpiresAt())) {
                throw new TripEditCommandResultExpiredException();
            }
            return readResult(record);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveResult(
            Long userId,
            Long tripId,
            String requestId,
            CommandType commandType,
            String payloadHash,
            ApiResponse<TripEditCommandResultResponse> result,
            LocalDateTime completedAt
    ) {
        validateCommand(userId, tripId, requestId, commandType, payloadHash, completedAt);
        Assert.isTrue(isValidResult(result, requestId, tripId), "명령과 일치하는 확정 결과가 필요합니다.");
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            log.error("공유 편집 결과 직렬화 실패: failure={}", exception.getClass().getSimpleName());
            throw new IllegalStateException("공유 편집 결과를 기록할 수 없습니다.");
        }
        records.saveAndFlush(new TripEditCommandRecord(
                userId, tripId, requestId, commandType, payloadHash, resultJson, completedAt));
    }

    private ApiResponse<TripEditCommandResultResponse> readResult(TripEditCommandRecord record) {
        if (record.getResultJson() == null) {
            throw new IllegalStateException("공유 편집 결과 본문이 없습니다.");
        }
        ApiResponse<TripEditCommandResultResponse> result;
        try {
            result = objectMapper.readValue(record.getResultJson(), new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            log.error("공유 편집 결과 역직렬화 실패: recordId={}, failure={}",
                    record.getId(), exception.getClass().getSimpleName());
            throw new IllegalStateException("공유 편집 결과를 읽을 수 없습니다.");
        }
        if (!isValidResult(result, record.getRequestId(), record.getTripId())) {
            throw new IllegalStateException("저장된 공유 편집 결과가 명령과 일치하지 않습니다.");
        }
        return result;
    }

    private boolean isValidResult(ApiResponse<TripEditCommandResultResponse> result, String requestId, Long tripId) {
        if (result == null || !StringUtils.hasText(result.message()) || result.data() == null) {
            return false;
        }
        TripEditCommandResultResponse data = result.data();
        if (!requestId.equals(data.requestId()) || !tripId.equals(data.tripId())) {
            return false;
        }
        return ("SUCCEEDED".equals(data.status()) && data.sequence() != null && data.sequence() >= 0)
                || ("REJECTED".equals(data.status()) && data.sequence() == null);
    }

    private void validateCommand(
            Long userId,
            Long tripId,
            String requestId,
            CommandType commandType,
            String payloadHash,
            LocalDateTime now
    ) {
        Assert.isTrue(userId != null && userId > 0 && tripId != null && tripId > 0, "사용자와 여행 ID가 필요합니다.");
        Assert.isTrue(StringUtils.hasText(requestId) && requestId.length() <= 128, "요청번호는 1~128자여야 합니다.");
        Assert.notNull(commandType, "명령 종류가 필요합니다.");
        Assert.isTrue(payloadHash != null && payloadHash.matches("[0-9a-f]{64}"), "SHA-256 본문 식별값이 필요합니다.");
        Assert.notNull(now, "명령 결과의 기준 시각이 필요합니다.");
    }
}
