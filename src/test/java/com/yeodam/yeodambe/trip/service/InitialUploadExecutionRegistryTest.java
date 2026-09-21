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
}
