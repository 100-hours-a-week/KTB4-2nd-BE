package com.yeodam.yeodambe.integration.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class TripPhotoAnalysisServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 현재_실행_ID와_다른_AI_응답은_거부한다() {
        var response = json.readTree("""
                {"trip_id":7,"execution_id":"old-run","status":"COMPLETED",
                 "result":{"places":[],"unclassified":[],"failed":[]}}
                """);

        assertThrows(IllegalStateException.class,
                () -> TripPhotoAnalysisService.validateResponse(7L, "current-run", response));
    }

    @Test
    void 현재_실행의_완료_응답만_결과를_반환한다() {
        var response = json.readTree("""
                {"trip_id":7,"execution_id":"run","status":"COMPLETED",
                 "result":{"places":[],"unclassified":[],"failed":[]}}
                """);

        assertSame(response.path("result"),
                TripPhotoAnalysisService.validateResponse(7L, "run", response));
    }
}
