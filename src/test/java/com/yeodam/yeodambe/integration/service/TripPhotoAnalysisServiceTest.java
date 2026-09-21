package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.common.exception.AiProcessingFailedException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
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
    void 현재_실행의_명시적_실패_응답을_공개_응답_정보로_변환한다() {
        var response = json.readTree("""
                {"trip_id":7,"execution_id":"run","status":"FAILED",
                 "progress":{"done":12,"total":128},"current_step":null,"result":null,
                 "error":{"code":"AI_PROCESSING_FAILED","message":"첨부 처리에 실패했습니다."}}
                """);

        AiProcessingFailedException failure = assertThrows(
                AiProcessingFailedException.class,
                () -> TripPhotoAnalysisService.validateResponse(7L, "run", response));

        assertEquals(7L, failure.getTripId());
        assertEquals(12, failure.getDone());
        assertEquals(128, failure.getTotal());
        assertNull(failure.getCurrentStep());
        assertEquals("AI_PROCESSING_FAILED", failure.getCode());
        assertEquals("첨부 처리에 실패했습니다.", failure.getPublicMessage());
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

    @Test
    void 취소_응답은_같은_여행의_CANCELED만_허용한다() {
        var valid = json.readTree("{\"trip_id\":7,\"status\":\"CANCELED\"}");
        var wrongTrip = json.readTree("{\"trip_id\":8,\"status\":\"CANCELED\"}");
        var wrongStatus = json.readTree("{\"trip_id\":7,\"status\":\"COMPLETED\"}");

        assertDoesNotThrow(() -> TripPhotoAnalysisService.validateCancelResponse(7L, valid));
        assertThrows(IllegalStateException.class,
                () -> TripPhotoAnalysisService.validateCancelResponse(7L, wrongTrip));
        assertThrows(IllegalStateException.class,
                () -> TripPhotoAnalysisService.validateCancelResponse(7L, wrongStatus));
    }

    @Test
    void 서버_준비_후_취소된_실행이면_AI_POST를_시작하지_않는다() {
        assertDoesNotThrow(() -> TripPhotoAnalysisService.requireAnalysisStart(() -> true));
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> TripPhotoAnalysisService.requireAnalysisStart(() -> false));
    }
}
