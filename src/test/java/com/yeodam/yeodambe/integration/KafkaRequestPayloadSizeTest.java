package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.integration.service.request.PhotoProcessMessage;
import com.yeodam.yeodambe.integration.service.request.PhotosReadyMessage;
import com.yeodam.yeodambe.integration.service.request.TripPhotoAnalysisRequest;
import com.yeodam.yeodambe.story.client.request.StoryGenerationMessage;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.trip.event.TripCreateRequestEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRequestPayloadSizeTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private static final String EXECUTION_ID = "00000000-0000-0000-0000-000000000001";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 14);
    private static final OffsetDateTime TAKEN_AT = OffsetDateTime.parse("2026-10-14T12:00:00+09:00");

    @ParameterizedTest
    @CsvSource({"1,false", "10,false", "200,false", "200,true"})
    void 요청_JSON의_UTF8_크기를_측정한다(int photoCount, boolean longFields) {
        String tripName = longFields ? "가".repeat(10) : "서울여행";
        String placeName = longFields ? "나".repeat(50) : "경복궁";
        String deviceModel = longFields ? "다".repeat(100) : "iPhone 16";
        var photos = IntStream.rangeClosed(1, photoCount)
                .mapToObj(id -> new TripPhotoAnalysisRequest.Photo(
                        (long) id, storageKey(id, longFields), TAKEN_AT,
                        new BigDecimal("37.579617"), new BigDecimal("126.977041"), deviceModel
                ))
                .toList();
        var request = new TripPhotoAnalysisRequest(EXECUTION_ID, tripName,
                new TripPhotoAnalysisRequest.Period(DATE, DATE),
                List.of(new TripPhotoAnalysisRequest.Region(new BigDecimal("37.579617"),
                        new BigDecimal("126.977041"))), photos);
        var creation = new TripCreateRequestEvent("process", 42L, EXECUTION_ID, null, "TRIP_CREATED",
                tripName, request.period(), request.regions(), mapper.valueToTree(photos));
        measure("process", photoCount, creation, longFields);
        measure("process_update", photoCount, PhotoProcessMessage.from(42L, EXECUTION_ID, request), longFields);

        var batch = photos.stream().limit(10)
                .map(photo -> new PhotosReadyMessage.Attachment(photo.tripAttachmentId(), photo.analyzeStorageKey()))
                .toList();
        measure("photos_ready", batch.size(), PhotosReadyMessage.create(42L, EXECUTION_ID, 1, batch), longFields);

        int placeCount = Math.min(photoCount, 10);
        var places = IntStream.range(0, placeCount)
                .mapToObj(index -> new StoryGenerationMessage.Place(
                        (long) index + 1,
                        placeName,
                        photos.stream()
                                .filter(photo -> (photo.tripAttachmentId() - 1) % placeCount == index)
                                .map(photo -> new StoryGenerationMessage.Attachment(
                                        photo.tripAttachmentId(),
                                        photo.analyzeStorageKey(),
                                        photo.takenAt(),
                                        100
                                ))
                                .toList()
                ))
                .toList();
        var story = new StoryGenerationMessage(42L, EXECUTION_ID, tripName,
                new StoryGenerationMessage.Period(DATE, DATE), Story.Mood.PLAIN, places);
        measure("story", photoCount, story, longFields);
    }

    private String storageKey(int id, boolean longFields) {
        String suffix = "/" + id + ".jpg";
        String prefix = longFields ? "a".repeat(500 - suffix.length()) : "analyze/trips/42/photos";
        return prefix + suffix;
    }

    private void measure(String type, int photoCount, Object message, boolean longFields) {
        String payload = mapper.writeValueAsString(message);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        String wireType = "process_update".equals(type) ? "process" : type;
        assertThat(mapper.readTree(payload).path("type").asString()).isEqualTo(wireType);
        assertThat(mapper.readTree(payload).path("trip_id").asLong()).isEqualTo(42L);
        assertThat(bytes.length).isGreaterThanOrEqualTo(payload.length());
        System.out.printf("KAFKA_PAYLOAD_SIZE type=%s photos=%d longFields=%s bytes=%d%n",
                type, photoCount, longFields, bytes.length);
    }
}
