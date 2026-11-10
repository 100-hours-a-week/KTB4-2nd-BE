package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.fixture.TripAnalysisTestFixture;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.service.response.TripAttachmentListResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"app.trip.edit-lock.enabled=true", "app.photo-list-cache.enabled=true"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripPhotoDeletionCacheIntegrationTest {
    @Autowired
    private TripAttachmentListService photos;
    @Autowired
    private TripDetailPlaceRepository places;
    @Autowired
    private TripPhotoDeletionService deletion;
    @Autowired
    private TripAnalysisExecutionService scheduling;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private TripAttachmentStorageClient storage;

    @Test
    void 캐시에_기존_목록이_있어도_삭제_예약을_새_세대의_사진_목록에_반영한다() {
        TripAnalysisTestFixture fixture = new TripAnalysisTestFixture(jdbc);
        Long ownerId = fixture.createOwner();
        Long tripId = fixture.createTrip(ownerId);
        Long photoId = fixture.createPhoto(tripId, ownerId);
        TripDetailPlace place = places.saveAndFlush(TripDetailPlace.fromAnalysis(tripId, 1, "서울",
                new BigDecimal("37.5"), new BigDecimal("127.0"), LocalDateTime.now(), LocalDateTime.now(), null));
        jdbc.update("UPDATE trip_attachments SET trip_place_id = ?, classification_status = 'ACTIVE' WHERE trip_attachment_id = ?",
                place.getId(), photoId);
        when(storage.createReadUrl(anyString())).thenAnswer(invocation -> "https://read.test/" + invocation.<String>getArgument(0));
        fixture.prepareAddition(tripId, ownerId);
        scheduling.startNext(tripId).orElseThrow();
        TripAttachmentListResponse before = photos.findByPlaceFolder(ownerId, tripId, place.getId(), null);
        assertThat(before.items()).hasSize(1);
        assertThat(before.items().getFirst().pendingDeletion()).isFalse();
        assertThat(photos.findByPlaceFolder(ownerId, tripId, place.getId(), null)).isEqualTo(before);

        deletion.deleteOne(ownerId, photoId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            TripAttachmentListResponse after = photos.findByPlaceFolder(ownerId, tripId, place.getId(), null);
            assertThat(after.items()).hasSize(1);
            assertThat(after.items().getFirst().tripAttachmentId()).isEqualTo(photoId);
            assertThat(after.items().getFirst().pendingDeletion()).isTrue();
            assertThat(after.sequence()).isGreaterThan(before.sequence());
        });
    }
}
