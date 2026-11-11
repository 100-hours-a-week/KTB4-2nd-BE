package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripUpdateNotAllowedException;
import com.yeodam.yeodambe.common.response.ApiResponse;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.common.response.SuccessMessage;
import com.yeodam.yeodambe.trip.config.TripEditLockProperties;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.service.request.TripEditPhotoDeleteCommandRequest;
import com.yeodam.yeodambe.trip.service.request.TripEditPhotoRestoreCommandRequest;
import com.yeodam.yeodambe.trip.service.request.TripEditUpdateCommandRequest;
import com.yeodam.yeodambe.trip.service.response.TripEditCommandResultResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripEditCommandLockTest {
    private final TripEditCommandTransactionService transactions = mock(TripEditCommandTransactionService.class);
    private final TripEditAccessService access = mock(TripEditAccessService.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock redisLock = mock(RLock.class);
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void authorize() {
        doAnswer(invocation -> {
            events.add("authorized");
            return null;
        }).when(access).requireWritePermission(7L, 1L);
    }

    @Test
    void 락_경합이면_업무와_영속_거절을_실행하지_않는다() throws Exception {
        when(redisLock.tryLock(500, java.util.concurrent.TimeUnit.MILLISECONDS)).thenReturn(false);
        when(transactions.update(any(), any(), any(), any(), any())).thenThrow(new AssertionError(TripEditLockTestErrorMessage.BUSINESS_ENTERED.message()));
        when(transactions.reject(any(), any(), any(), any(), any(), any())).thenThrow(new AssertionError(TripEditLockTestErrorMessage.REJECTION_PERSISTED.message()));

        assertThatThrownBy(() -> service().update(7L, 1L,
                new TripEditUpdateCommandRequest("same-id", json.readTree("{\"tripName\":\"변경\"}"))))
                .isInstanceOf(TripEditLockRejectedException.class);
        assertThat(events).containsExactly("authorized");
    }

    @Test
    void 업무_거절도_같은_락_안에서_저장한다() throws Exception {
        acquired();
        when(transactions.update(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            events.add("rollback");
            throw new TripUpdateNotAllowedException();
        });
        when(transactions.reject(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            events.add("rejection-commit");
            return new ApiResponse<>(ErrorMessage.TRIP_UPDATE_NOT_ALLOWED,
                    new TripEditCommandResultResponse("denied", 7L, "REJECTED", null));
        });

        ApiResponse<TripEditCommandResultResponse> result = service().update(7L, 1L,
                new TripEditUpdateCommandRequest("denied", json.readTree("{\"tripName\":\"변경\"}")));

        assertThat(result.data().status()).isEqualTo("REJECTED");
        assertThat(events).containsExactly("authorized", "acquired", "rollback", "rejection-commit", "released");
    }

    @Test
    void 잘못된_입력의_기존_영속_거절을_유지한다() throws Exception {
        acquired();
        when(transactions.reject(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            events.add("invalid-rejection");
            return new ApiResponse<>(ErrorMessage.INVALID_TRIP_REQUEST,
                    new TripEditCommandResultResponse("invalid", 7L, "REJECTED", null));
        });

        ApiResponse<TripEditCommandResultResponse> result = service().update(7L, 1L,
                new TripEditUpdateCommandRequest("invalid", json.readTree("{\"unknown\":true}")));

        assertThat(result.message()).isEqualTo("INVALID_TRIP_REQUEST");
        assertThat(events).containsExactly("authorized", "acquired", "invalid-rejection", "released");
    }

    @Test
    void 사진_삭제는_같은_여행_락을_사용하고_커밋_결과를_반환한다() throws Exception {
        acquired();
        when(transactions.deleteAttachments(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            events.add("delete-commit");
            return new ApiResponse<>(SuccessMessage.TRIP_PHOTOS_DELETE_SUCCESS,
                    new TripEditCommandResultResponse("delete", 7L, "SUCCEEDED", 3L));
        });

        ApiResponse<TripEditCommandResultResponse> result = service().deleteAttachments(7L, 1L,
                new TripEditPhotoDeleteCommandRequest("delete", json.readTree("[11,12]")));

        assertThat(result.data().sequence()).isEqualTo(3);
        assertThat(events).containsExactly("authorized", "acquired", "delete-commit", "released");
    }

    @Test
    void 사진_복구는_WRITE_승인_뒤_여행_락에서_처리한다() throws Exception {
        acquired();
        when(transactions.restoreAttachments(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            events.add("restore-commit");
            return new ApiResponse<>(SuccessMessage.ATTACHMENT_RESTORE_SUCCESS,
                    new TripEditCommandResultResponse("restore", 7L, "SUCCEEDED", 3L));
        });

        ApiResponse<TripEditCommandResultResponse> result = service().restoreAttachments(7L, 1L,
                new TripEditPhotoRestoreCommandRequest("restore",
                        json.readTree("[{\"tripAttachmentId\":11,\"tripPlaceId\":12}]")));

        assertThat(result.data().sequence()).isEqualTo(3);
        assertThat(events).containsExactly("authorized", "acquired", "restore-commit", "released");
    }

    @Test
    void 확정된_결과가_없는_무결성_오류는_원래_예외를_전파한다() throws Exception {
        acquired();
        DataIntegrityViolationException original = new DataIntegrityViolationException(
                TripEditLockTestErrorMessage.BUSINESS_ROLLBACK.message());
        when(transactions.update(any(), any(), any(), any(), any())).thenThrow(original);
        when(transactions.replayExistingResult(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            events.add("result-query");
            return Optional.empty();
        });

        assertThatThrownBy(() -> service().update(7L, 1L,
                new TripEditUpdateCommandRequest("constraint-error", json.readTree("{\"tripName\":\"변경\"}"))))
                .isSameAs(original);
        assertThat(events).containsExactly("authorized", "acquired", "released", "acquired", "result-query", "released");
    }

    private TripEditCommandService service() {
        when(redisson.getLock("test:lock:trip:mutation:7")).thenReturn(redisLock);
        TripEditLock lock = new TripEditLock(redisson,
                new TripEditLockProperties(true, "test", Duration.ofMillis(500), Duration.ofSeconds(30)),
                new SimpleMeterRegistry());
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return new TripEditCommandService(transactions, access, json, factory.getValidator(), lock);
        }
    }

    private void acquired() throws Exception {
        when(redisLock.tryLock(500, java.util.concurrent.TimeUnit.MILLISECONDS)).thenAnswer(invocation -> {
            events.add("acquired");
            return true;
        });
        doAnswer(invocation -> {
            events.add("released");
            return null;
        }).when(redisLock).unlock();
    }
}
