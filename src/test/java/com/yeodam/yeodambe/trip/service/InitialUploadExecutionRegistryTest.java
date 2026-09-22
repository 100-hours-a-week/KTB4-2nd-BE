package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.trip.service.InitialUploadExecutionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InitialUploadExecutionRegistryTest {
    private final InitialUploadExecutionRegistry registry = new InitialUploadExecutionRegistry();

    @Test
    void 여행별로_하나의_실행만_예약한다() {
        String executionId = registry.reserve(7L);

        assertTrue(registry.isCurrent(7L, executionId));
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> registry.reserve(7L));
    }

    @Test
    void 현재_실행만_예약을_해제할_수_있다() {
        String executionId = registry.reserve(7L);

        registry.release(7L, "other");
        assertTrue(registry.isCurrent(7L, executionId));

        registry.release(7L, executionId);
        assertFalse(registry.isCurrent(7L, executionId));
        assertDoesNotThrow(() -> registry.reserve(7L));
    }

    @Test
    void 현재_실행의_AI_분석_시작만_기록한다() {
        String executionId = registry.reserve(7L);

        assertFalse(registry.isAnalysisStarted(7L));
        assertFalse(registry.markAnalysisStarted(7L, "other"));
        assertFalse(registry.isAnalysisStarted(7L));

        assertTrue(registry.markAnalysisStarted(7L, executionId));
        assertTrue(registry.isAnalysisStarted(7L));
    }

    @Test
    void 취소가_먼저_기록되면_AI_분석을_시작하지_않는다() {
        String executionId = registry.reserve(7L);

        assertFalse(registry.cancel(7L));

        assertFalse(registry.markAnalysisStarted(7L, executionId));
        assertFalse(registry.isAnalysisStarted(7L));
    }

    @Test
    void AI_분석이_먼저_시작되면_취소_호출이_필요함을_반환한다() {
        String executionId = registry.reserve(7L);
        assertTrue(registry.markAnalysisStarted(7L, executionId));

        assertTrue(registry.cancel(7L));
    }
}
