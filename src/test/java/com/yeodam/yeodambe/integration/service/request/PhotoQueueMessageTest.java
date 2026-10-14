package com.yeodam.yeodambe.integration.service.request;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PhotoQueueMessageTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void photosReadyContainsOnlyBatchIdentityAndPhotoIdsAndKeys() {
        var message = PhotosReadyMessage.create(77L, "execution-one", 2,
                List.of(new PhotosReadyMessage.Attachment(101L, "analyze/one")));
        var body = json.readTree(json.writeValueAsString(message));
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.path("type").asString()).isEqualTo("photos_ready");
        assertThat(body.path("trip_id").asLong()).isEqualTo(77L);
        assertThat(body.path("execution_id").asString()).isEqualTo("execution-one");
        assertThat(body.path("batch_no").asInt()).isEqualTo(2);
        var photo = body.path("attachments").get(0);
        assertThat(photo.size()).isEqualTo(2);
        assertThat(photo.path("trip_attachment_id").asLong()).isEqualTo(101L);
        assertThat(photo.path("analyze_storage_key").asString()).isEqualTo("analyze/one");
        assertThat(photo.has("taken_at")).isFalse();
        assertThat(photo.has("latitude")).isFalse();
        assertThat(photo.has("device_model")).isFalse();
    }

    @Test
    void processHasFlatQueueFieldsAndCompleteOriginalMetadataIncludingNulls() {
        var input = input();
        var message = PhotoProcessMessage.from(77L, "attempt-new", input);
        var body = json.readTree(json.writeValueAsString(message));
        assertThat(body.size()).isEqualTo(7);
        assertThat(body.path("type").asString()).isEqualTo("process");
        assertThat(body.path("trip_id").asLong()).isEqualTo(77L);
        assertThat(body.path("execution_id").asString()).isEqualTo("attempt-new");
        assertThat(body.path("trip_name").asString()).isEqualTo("제주도 가을");
        assertThat(body.path("period").path("start_date").asString()).isEqualTo("2026-10-12");
        assertThat(body.path("regions").get(0).path("latitude").decimalValue()).isEqualByComparingTo("33.4996");
        var photo = body.path("attachments").get(0);
        assertThat(photo.size()).isEqualTo(6);
        assertThat(photo.path("taken_at").asString()).isEqualTo("2026-10-12T06:14:00+09:00");
        var missing = body.path("attachments").get(1);
        for (String field : List.of("taken_at", "latitude", "longitude", "device_model")) {
            assertThat(missing.has(field)).isTrue();
            assertThat(missing.path(field).isNull()).isTrue();
        }
        assertThat(body.has("data")).isFalse();
        assertThat(body.has("request")).isFalse();
        assertThat(message.attachments()).isEqualTo(input.attachments());
    }

    @Test
    void sameAttemptUsesSameExecutionIdAndRetryChangesOnlyAttemptIdentity() {
        var input = input();
        var ready = PhotosReadyMessage.create(77L, "first-attempt", 1, List.of());
        var first = PhotoProcessMessage.from(77L, "first-attempt", input);
        var retry = PhotoProcessMessage.from(77L, "second-attempt", input);
        assertThat(first.executionId()).isEqualTo(ready.executionId());
        assertThat(retry.executionId()).isNotEqualTo(first.executionId());
        assertThat(retry.attachments()).isEqualTo(first.attachments());
        assertThat(input.executionId()).isEqualTo("existing-http-execution");
        var legacy = json.readTree(json.writeValueAsString(input));
        assertThat(legacy.has("type")).isFalse();
        assertThat(legacy.has("trip_id")).isFalse();
    }

    private TripPhotoAnalysisRequest input() {
        return new TripPhotoAnalysisRequest("existing-http-execution", "제주도 가을",
                new TripPhotoAnalysisRequest.Period(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-14")),
                List.of(new TripPhotoAnalysisRequest.Region(new BigDecimal("33.4996"), new BigDecimal("126.5312"))),
                List.of(new TripPhotoAnalysisRequest.Photo(101L, "analyze/one",
                                OffsetDateTime.parse("2026-10-12T06:14:00+09:00"), BigDecimal.ONE, BigDecimal.TEN, "camera"),
                        new TripPhotoAnalysisRequest.Photo(102L, "analyze/two", null, null, null, null)));
    }
}
