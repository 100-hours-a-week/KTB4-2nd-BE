package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;

class TripUploadTimeoutServiceTest {
    private final TripCreationExecutionRepository executions = mock(TripCreationExecutionRepository.class);
    private final InitialAttachmentUploadTransactionService transactions = mock(InitialAttachmentUploadTransactionService.class);
    private final TripPhotoAnalysisService analysis = mock(TripPhotoAnalysisService.class);
    private final TripUploadTimeoutService service = new TripUploadTimeoutService(executions, transactions, analysis);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 16, 12, 0);
    private final PageRequest page = PageRequest.of(0, 100);

    @Test
    void 비동기_업로드_10분과_AI_결과_한시간_만료를_계속_처리한다() {
        when(transactions.databaseTime()).thenReturn(now);
        var upload = execution("upload", 7L);
        var result = execution("result", 8L);
        when(executions.findUploadExpired(now.minusMinutes(10), page)).thenReturn(List.of(upload));
        when(executions.findResultExpired(now.minusHours(1), page)).thenReturn(List.of(result));

        service.failExpiredUploads();

        verify(transactions).failExpiredExecution("upload", false);
        verify(transactions).failExpiredExecution("result", true);
    }

    @Test
    void 취소_전달에_성공한_실행만_완료로_기록하고_실패_이후에도_다음을_처리한다() {
        when(transactions.databaseTime()).thenReturn(now);
        var failed = execution("failed", 7L);
        var success = execution("success", 8L);
        when(executions.findByCancelPendingTrue(page)).thenReturn(List.of(failed, success));
        doThrow(new IllegalStateException("AI unavailable")).when(analysis).cancel(7L);

        service.failExpiredUploads();

        verify(analysis).cancel(7L);
        verify(analysis).cancel(8L);
        verify(transactions, never()).recordCancellationDelivered("failed");
        verify(transactions).recordCancellationDelivered("success");
    }

    @Test
    void 처리할_실행이_없으면_만료_변경과_AI_취소를_호출하지_않는다() {
        when(transactions.databaseTime()).thenReturn(now);

        service.failExpiredUploads();

        verify(executions).findUploadExpired(now.minusMinutes(10), page);
        verify(executions).findResultExpired(now.minusHours(1), page);
        verify(executions).findByCancelPendingTrue(page);
        verify(transactions).databaseTime();
        verifyNoMoreInteractions(transactions);
        verifyNoInteractions(analysis);
    }

    private TripCreationExecution execution(String id, Long tripId) {
        var execution = mock(TripCreationExecution.class);
        when(execution.getExecutionId()).thenReturn(id);
        when(execution.getTripId()).thenReturn(tripId);
        return execution;
    }
}
