package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
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

    @Test
    void 처리중_상태_응답을_검증해_변환한다() {
        var response = json.readTree("""
                {"trip_id":7,"status":"PROCESSING","progress":{"done":2,"total":5},
                 "current_step":"EMBEDDING","result":null,"error":null}
                """);

        TripPhotoAnalysisStatusResponse status =
                TripPhotoAnalysisService.validateStatusResponse(7L, response);

        assertEquals(TripPhotoAnalysisStatusResponse.Status.PROCESSING, status.status());
        assertEquals(2, status.progress().done());
        assertEquals(5, status.progress().total());
        assertEquals("EMBEDDING", status.currentStep());
    }

    @Test
    void 진행_수치나_상태별_필드_조합이_잘못되면_거부한다() {
        var invalidTripId = json.readTree("""
                {"trip_id":"7","status":"PROCESSING","progress":{"done":2,"total":5},
                 "current_step":"EMBEDDING","result":null,"error":null}
                """);
        var invalidProgress = json.readTree("""
                {"trip_id":7,"status":"PROCESSING","progress":{"done":6,"total":5},
                 "current_step":"EMBEDDING","result":null,"error":null}
                """);
        var invalidCompleted = json.readTree("""
                {"trip_id":7,"status":"COMPLETED","progress":{"done":5,"total":5},
                 "current_step":null,"result":null,"error":null}
                """);

        assertThrows(IllegalStateException.class,
                () -> TripPhotoAnalysisService.validateStatusResponse(7L, invalidTripId));
        assertThrows(IllegalStateException.class,
                () -> TripPhotoAnalysisService.validateStatusResponse(7L, invalidProgress));
        assertThrows(IllegalStateException.class,
                () -> TripPhotoAnalysisService.validateStatusResponse(7L, invalidCompleted));
    }
}
