package com.yeodam.yeodambe.integration.service;

import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class QueuePhotoAnalysisResponseMapperTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void preservesCurrentExecutionAndExtractsCompletedResultForExistingPersistence() {
        var response = QueuePhotoAnalysisResponseMapper.parse(77L, "attempt-one", json.readTree(body("attempt-one")));
        assertThat(response.executionId()).isEqualTo("attempt-one");
        assertThat(response.status()).isEqualTo(TripPhotoAnalysisStatusResponse.Status.COMPLETED);
        assertThat(response.progress().done()).isEqualTo(2);
        var result = QueuePhotoAnalysisResponseMapper.completedResult(response, json);
        assertThat(result.path("trip_id").asLong()).isEqualTo(77L);
        assertThat(result.path("execution_id").asString()).isEqualTo("attempt-one");
        assertThat(result.path("places").isArray()).isTrue();
        assertThat(result.path("unclassified").isArray()).isTrue();
        assertThat(result.has("result")).isFalse();
    }

    @Test
    void rejectsPreviousOrMissingExecutionAndOtherTrip() {
        assertThatThrownBy(() -> QueuePhotoAnalysisResponseMapper.parse(77L, "current", json.readTree(body("previous"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> QueuePhotoAnalysisResponseMapper.parse(77L, "attempt-one",
                json.readTree(body("attempt-one").replace("\"execution_id\":\"attempt-one\",", ""))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> QueuePhotoAnalysisResponseMapper.parse(99L, "attempt-one", json.readTree(body("attempt-one"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void queuedStatusHasNoCompletedResult() {
        var response = QueuePhotoAnalysisResponseMapper.parse(77L, "attempt-one", json.readTree("""
                {"trip_id":77,"execution_id":"attempt-one","status":"QUEUED",
                 "progress":null,"current_step":null,"result":null,"error":null}
                """));
        assertThat(response.progress()).isNull();
        assertThatThrownBy(() -> QueuePhotoAnalysisResponseMapper.completedResult(response, json))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retainsExistingStatusValidationForInvalidProgress() {
        assertThatThrownBy(() -> QueuePhotoAnalysisResponseMapper.parse(77L, "attempt-one",
                json.readTree(body("attempt-one").replace("\"done\":2", "\"done\":3"))))
                .isInstanceOf(IllegalStateException.class);
    }

    private String body(String executionId) {
        return """
                {"trip_id":77,"execution_id":"%s","status":"COMPLETED",
                 "progress":{"done":2,"total":2},"current_step":null,
                 "result":{"places":[],"unclassified":[]},"error":null}
                """.formatted(executionId);
    }
}
