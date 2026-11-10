package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.TripAnalysisExecution;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException;
import com.yeodam.yeodambe.trip.exception.TripEditLockRejectedException.Reason;
import com.yeodam.yeodambe.trip.exception.TripInternalErrorMessage;
import com.yeodam.yeodambe.trip.fixture.TripMutationTestFixture;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripAnalysisExecutionRepository;
import com.yeodam.yeodambe.trip.service.response.TripAnalysisResultClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripResultWorkerContentionTest {
    private final TripAnalysisResultService initial = mock(TripAnalysisResultService.class);
    private final AdditionalAttachmentKafkaResultService legacy = mock(AdditionalAttachmentKafkaResultService.class);
    private final TripAnalysisResultApplicationTransactionService transactions =
            mock(TripAnalysisResultApplicationTransactionService.class);
    private final TripPlaceNameService names = mock(TripPlaceNameService.class);
    private final TripMutationCoordinator mutations = TripMutationTestFixture.direct();
    private final TripEditLock editLock = mock(TripEditLock.class);
    private final TripAnalysisResultClaim claim = new TripAnalysisResultClaim("second", 12L, 1L, "token", "{}", 1);
    private final JsonNode result = new ObjectMapper().readTree("{}");
    private final TripAnalysisResultWorker worker = new TripAnalysisResultWorker(initial, names, mutations);
    private final TripAnalysisResultApplicationService application = new TripAnalysisResultApplicationService(
            legacy, transactions, mock(TripAnalysisExecutionRepository.class), editLock,
            mutations, mock(AdditionalAttachmentExecutionRepository.class)
    );

    @BeforeEach
    void prepareTwoCandidates() {
        when(initial.findResultClaimCandidates()).thenReturn(List.of(
                new TripCreationExecution("first", 11L, 1L, 1),
                new TripCreationExecution("second", 12L, 1L, 1)
        ));
        when(initial.claimResult("second")).thenReturn(Optional.of(claim));
        when(initial.startResultAttempt(claim)).thenReturn(true);
        when(initial.validateForApplication(claim)).thenReturn(result);
        when(names.resolve(eq(12L), eq("second"), eq(result), any())).thenReturn(Map.of());
        when(legacy.findClaimCandidates()).thenReturn(List.of(addition("first", 11L), addition("second", 12L)));
        when(legacy.claim("second")).thenReturn(Optional.of(claim));
        doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        }).when(editLock).execute(anyLong(), any());
    }

    @Test
    void 경합한_최초_후보를_건너뛰고_다른_여행의_결과를_반영한다() {
        blockFirst(Reason.CONTENDED);

        worker.applyPendingResults();

        verify(initial, never()).claimResult("first");
        verify(initial).saveKafkaCompleted(claim, result, Map.of());
        verify(initial, never()).failResult(any(), anyBoolean(), any());
    }

    @Test
    void 경합한_LEGACY_후보를_건너뛰고_다른_여행을_선점한다() {
        blockFirst(Reason.CONTENDED);

        assertThat(application.claim()).contains(claim);

        verify(legacy, never()).claim("first");
        verify(legacy).claim("second");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 만료_후보의_경합이_다른_여행의_만료_처리를_막지_않는다(boolean v3) {
        blockFirst(Reason.CONTENDED);
        prepareExpiredCandidates(v3);

        application.expire();

        if (v3) {
            verify(transactions, never()).expire("first");
            verify(transactions).expire("second");
        } else {
            verify(legacy, never()).expire("first");
            verify(legacy).expire("second");
        }
    }

    @Test
    void 최초_선점의_Redis_장애는_전파하고_다른_여행을_처리하지_않는다() {
        TripEditLockRejectedException failure = blockFirst(Reason.STORE_UNAVAILABLE);

        assertThatThrownBy(worker::applyPendingResults).isSameAs(failure);

        verify(initial, never()).claimResult("second");
        verify(initial, never()).failResult(any(), anyBoolean(), any());
    }

    @Test
    void LEGACY_선점의_Redis_장애는_전파하고_다른_여행을_선점하지_않는다() {
        TripEditLockRejectedException failure = blockFirst(Reason.STORE_UNAVAILABLE);

        assertThatThrownBy(application::claim).isSameAs(failure);

        verify(legacy, never()).claim("second");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 만료_처리의_Redis_장애는_전파하고_다른_여행을_처리하지_않는다(boolean v3) {
        TripEditLockRejectedException failure = blockFirst(Reason.STORE_UNAVAILABLE);
        prepareExpiredCandidates(v3);

        assertThatThrownBy(application::expire).isSameAs(failure);

        verify(transactions, never()).expire("second");
        verify(legacy, never()).expire("second");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 최초_결과_저장의_락_거절과_리스_취소는_업무_실패로_확정하지_않는다(boolean cancelled) {
        doThrow(technicalSaveFailure(cancelled)).when(initial).saveKafkaCompleted(eq(claim), eq(result), any());

        worker.applyPendingResults();

        verify(initial).saveKafkaCompleted(claim, result, Map.of());
        verify(initial, never()).failResult(any(), anyBoolean(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 추가_결과_저장의_락_거절과_리스_취소는_업무_실패로_확정하지_않는다(boolean cancelled) {
        TripAnalysisResultApplicationService results = mock(TripAnalysisResultApplicationService.class);
        when(results.claim()).thenReturn(Optional.of(claim));
        when(results.start(claim)).thenReturn(true);
        when(results.validate(claim)).thenReturn(result);
        doThrow(technicalSaveFailure(cancelled)).when(results).complete(eq(claim), eq(result), any());

        new AdditionalAttachmentResultWorker(results, names).applyPendingResults();

        verify(results).complete(claim, result, Map.of());
        verify(results, never()).fail(any(), anyBoolean(), any());
    }

    private RuntimeException technicalSaveFailure(boolean cancelled) {
        return cancelled
                ? new CancellationException(TripInternalErrorMessage.RESULT_APPLICATION_CANCELED.message())
                : new TripEditLockRejectedException(Reason.STORE_UNAVAILABLE);
    }

    private void prepareExpiredCandidates(boolean v3) {
        if (v3) {
            List<TripAnalysisExecution> candidates = List.of(analysis("first", 11L), analysis("second", 12L));
            when(transactions.findExpiredCandidates()).thenReturn(candidates);
        } else {
            when(legacy.findExpiredCandidates()).thenReturn(List.of(addition("first", 11L), addition("second", 12L)));
        }
    }

    private TripEditLockRejectedException blockFirst(Reason reason) {
        TripEditLockRejectedException failure = new TripEditLockRejectedException(reason);
        doThrow(failure).when(mutations).execute(eq(11L), any());
        doThrow(failure).when(mutations).run(eq(11L), any());
        doThrow(failure).when(editLock).execute(eq(11L), any());
        return failure;
    }

    private AdditionalAttachmentExecution addition(String executionId, Long tripId) {
        return new AdditionalAttachmentExecution(executionId, tripId, 1L, 1, LocalDateTime.now());
    }

    private TripAnalysisExecution analysis(String executionId, Long tripId) {
        TripAnalysisExecution candidate = mock(TripAnalysisExecution.class);
        when(candidate.getExecutionId()).thenReturn(executionId);
        when(candidate.getTripId()).thenReturn(tripId);
        return candidate;
    }
}
