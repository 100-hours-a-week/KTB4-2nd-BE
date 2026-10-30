package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripEditCommandMismatchException;
import com.yeodam.yeodambe.common.exception.TripEditCommandResultExpiredException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord;
import com.yeodam.yeodambe.trip.entity.TripEditCommandRecord.CommandType;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
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
        Optional<TripEditCommandRecord> existingRecord = records.findByUserIdAndRequestId(userId, requestId);
        if (existingRecord.isEmpty()) {
            return Optional.empty();
        }

        TripEditCommandRecord record = existingRecord.orElseThrow();
        if (!record.getRequestId().equals(requestId) || !record.getTripId().equals(tripId)
                || record.getCommandType() != commandType || !record.getPayloadHash().equals(payloadHash)) {
            throw new TripEditCommandMismatchException();
        }
        if (!now.isBefore(record.getResultExpiresAt())) {
            throw new TripEditCommandResultExpiredException();
        }
        return Optional.of(readResult(record));
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
        Assert.isTrue(isValidResult(result, requestId, tripId),
                TripInternalErrorMessage.EDIT_COMMAND_RESULT_INVALID.message());
        String resultJson = serializeResult(result);
        records.saveAndFlush(new TripEditCommandRecord(
                userId, tripId, requestId, commandType, payloadHash, resultJson, completedAt));
    }

    private String serializeResult(ApiResponse<TripEditCommandResultResponse> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JacksonException exception) {
            log.error("공유 편집 결과 직렬화 실패: failure={}", exception.getClass().getSimpleName());
            throw new IllegalStateException(TripInternalErrorMessage.EDIT_COMMAND_RESULT_WRITE_FAILED.message());
        }
    }

    private ApiResponse<TripEditCommandResultResponse> readResult(TripEditCommandRecord record) {
        if (record.getResultJson() == null) {
            throw new IllegalStateException(TripInternalErrorMessage.EDIT_COMMAND_RESULT_BODY_MISSING.message());
        }
        ApiResponse<TripEditCommandResultResponse> result;
        try {
            result = objectMapper.readValue(record.getResultJson(), new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            log.error("공유 편집 결과 역직렬화 실패: recordId={}, failure={}",
                    record.getId(), exception.getClass().getSimpleName());
            throw new IllegalStateException(TripInternalErrorMessage.EDIT_COMMAND_RESULT_READ_FAILED.message());
        }
        if (!isValidResult(result, record.getRequestId(), record.getTripId())) {
            throw new IllegalStateException(TripInternalErrorMessage.EDIT_COMMAND_RESULT_MISMATCH.message());
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
        return switch (data.status()) {
            case "SUCCEEDED" -> data.sequence() != null && data.sequence() >= 0;
            case "REJECTED" -> data.sequence() == null;
            case null, default -> false;
        };
    }

    private void validateCommand(
            Long userId,
            Long tripId,
            String requestId,
            CommandType commandType,
            String payloadHash,
            LocalDateTime now
    ) {
        Assert.isTrue(userId != null && userId > 0 && tripId != null && tripId > 0,
                TripInternalErrorMessage.EDIT_COMMAND_IDENTITY_MISSING.message());
        Assert.isTrue(StringUtils.hasText(requestId) && requestId.length() <= 128,
                TripInternalErrorMessage.EDIT_COMMAND_REQUEST_ID_INVALID.message());
        Assert.notNull(commandType, TripInternalErrorMessage.EDIT_COMMAND_TYPE_MISSING.message());
        Assert.isTrue(payloadHash != null && payloadHash.matches("[0-9a-f]{64}"),
                TripInternalErrorMessage.EDIT_COMMAND_HASH_INVALID.message());
        Assert.notNull(now, TripInternalErrorMessage.EDIT_COMMAND_TIME_MISSING.message());
    }
}
