package com.yeodam.yeodambe.trip;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class TripMapRepresentativeMysqlTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoredFileRepository files;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private TripRegionRepository regions;
    @Autowired private TripService service;
    @Autowired private EntityManager entityManager;
    @MockitoBean private TripAttachmentStorageClient storage;
    private User owner;
    private final Map<Long, Long> placeIds = new HashMap<>();

    @BeforeEach
    void prepare() {
        owner = users.save(new User(UUID.randomUUID() + "@yeodam.test", "map"));
        when(storage.createReadUrl(anyString())).thenAnswer(call -> "https://example.test/" + call.getArgument(0));
    }

    @Test
    void selectsNextHighestCoordinatePhotoAndKeepsImageCoordinatesAndGlobalThumbnailConsistent() {
        var trip = trip(owner.getUserId(), ProcessingStatus.COMPLETED, false);
        trip.changeThumbnailKey("existing-global-thumbnail");
        photo(trip, 100, null, null, "no-coordinates");
        photo(trip, 99, BigDecimal.ONE, null, "partial-coordinates");
        photo(trip, 85, new BigDecimal("37.5"), new BigDecimal("127.2"), "selected");
        photo(trip, 80, BigDecimal.ONE, BigDecimal.TEN, "lower");
        flushAndClear();
        var result = service.findMap(owner.getUserId());
        assertThat(result.markers()).hasSize(1);
        var marker = result.markers().getFirst();
        assertThat(marker.tripId()).isEqualTo(trip.getId());
        assertThat(marker.latitude()).isEqualByComparingTo("37.5");
        assertThat(marker.longitude()).isEqualByComparingTo("127.2");
        assertThat(marker.thumbnailUrl()).isEqualTo("https://example.test/preview/selected");
        assertThat(marker.attachmentCount()).isEqualTo(4);
        assertThat(trips.findById(trip.getId()).orElseThrow().getThumbnailKey()).isEqualTo("existing-global-thumbnail");
        verify(storage).createReadUrl("preview/selected");
    }

    @Test
    void sameScoreUsesLowerAttachmentId() {
        var trip = trip(owner.getUserId(), ProcessingStatus.COMPLETED, false);
        var first = photo(trip, 90, BigDecimal.ONE, BigDecimal.TEN, "first");
        var second = photo(trip, 90, BigDecimal.ONE, BigDecimal.TEN, "second");
        assertThat(first.getId()).isLessThan(second.getId());
        flushAndClear();
        assertThat(attachments.findMapRepresentatives(owner.getUserId())).singleElement()
                .satisfies(value -> assertThat(value.previewStorageKey()).isEqualTo("preview/first"));
    }

    @Test
    void nullScoreRanksAfterZeroAndZeroCoordinatesAreAllowed() {
        var trip = trip(owner.getUserId(), ProcessingStatus.COMPLETED, false);
        photo(trip, null, BigDecimal.ONE, BigDecimal.TEN, "null-score");
        photo(trip, 0, BigDecimal.ZERO, BigDecimal.ZERO, "zero-score");
        flushAndClear();
        assertThat(attachments.findMapRepresentatives(owner.getUserId())).singleElement().satisfies(value -> {
            assertThat(value.previewStorageKey()).isEqualTo("preview/zero-score");
            assertThat(value.latitude()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(value.longitude()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void allNullScoresStillSelectOneLowerIdPhoto() {
        var trip = trip(owner.getUserId(), ProcessingStatus.COMPLETED, false);
        photo(trip, null, BigDecimal.ONE, BigDecimal.TEN, "first-null");
        photo(trip, null, BigDecimal.ONE, BigDecimal.TEN, "second-null");
        flushAndClear();
        assertThat(attachments.findMapRepresentatives(owner.getUserId())).singleElement()
                .satisfies(value -> assertThat(value.previewStorageKey()).isEqualTo("preview/first-null"));
    }

    @Test
    void deletedPhotosDeletedFilesAndUnclassifiedPhotosCannotWin() {
        var trip = trip(owner.getUserId(), ProcessingStatus.COMPLETED, false);
        var deletedPhoto = photo(trip, 100, BigDecimal.ONE, BigDecimal.TEN, "deleted-photo");
        deletedPhoto.softDelete(LocalDateTime.now());
        var deletedFile = photo(trip, 99, BigDecimal.ONE, BigDecimal.TEN, "deleted-file");
        files.findById(deletedFile.getFileId()).orElseThrow().softDelete(LocalDateTime.now());
        var unclassified = photo(trip, 98, BigDecimal.ONE, BigDecimal.TEN, "unclassified");
        unclassified.unclassify(null, AttachmentIssue.BLURRY, RegionOrigin.EXIF,
                LocalDateTime.now(), BigDecimal.ONE, BigDecimal.TEN, 98);
        photo(trip, 20, BigDecimal.ONE, BigDecimal.TEN, "eligible");
        flushAndClear();
        assertThat(attachments.findMapRepresentatives(owner.getUserId())).singleElement()
                .satisfies(value -> assertThat(value.previewStorageKey()).isEqualTo("preview/eligible"));
    }

    @Test
    void onlyOwnCompletedUndeletedTripsAppear() {
        var other = users.save(new User(UUID.randomUUID() + "@yeodam.test", "other"));
        var visible = trip(owner.getUserId(), ProcessingStatus.COMPLETED, false);
        photo(visible, 90, BigDecimal.ONE, BigDecimal.TEN, "visible");
        for (var status : List.of(ProcessingStatus.PROCESSING, ProcessingStatus.FAILED, ProcessingStatus.CANCELED)) {
            photo(trip(owner.getUserId(), status, false), 100, BigDecimal.ONE, BigDecimal.TEN, status.name());
        }
        photo(trip(owner.getUserId(), ProcessingStatus.COMPLETED, true), 100, BigDecimal.ONE, BigDecimal.TEN, "deleted-trip");
        photo(trip(other.getUserId(), ProcessingStatus.COMPLETED, false), 100, BigDecimal.ONE, BigDecimal.TEN, "other-trip");
        flushAndClear();
        assertThat(attachments.findMapRepresentatives(owner.getUserId()))
                .extracting(TripMapRepresentative::tripId).containsExactly(visible.getId());
    }

    @Test
    void coordinateLessTripIsExcludedFromMapButKeptInDatabase() {
        var trip = trip(owner.getUserId(), ProcessingStatus.COMPLETED, false);
        photo(trip, 100, null, null, "no-gps");
        flushAndClear();
        assertThat(service.findMap(owner.getUserId()).markers()).isEmpty();
        assertThat(trips.findByIdAndUserIdAndDeletedAtIsNull(trip.getId(), owner.getUserId())).isPresent();
        verifyNoInteractions(storage);
    }

    @Test
    void multipleRegionsAndSameCoordinatesDoNotMergeOrDuplicateTripMarkers() {
        var first = trip(owner.getUserId(), ProcessingStatus.COMPLETED, false);
        var second = trip(owner.getUserId(), ProcessingStatus.COMPLETED, false);
        photo(first, 90, BigDecimal.ONE, BigDecimal.TEN, "first-trip");
        photo(second, 90, BigDecimal.ONE, BigDecimal.TEN, "second-trip");
        regions.saveAll(List.of(
                new TripRegion(first, "11000", "seoul", BigDecimal.ONE, BigDecimal.TEN),
                new TripRegion(first, "26000", "busan", BigDecimal.ONE, BigDecimal.TEN),
                new TripRegion(second, "11000", "seoul", BigDecimal.ONE, BigDecimal.TEN)));
        flushAndClear();
        assertThat(service.findMap(owner.getUserId()).markers()).extracting(value -> value.tripId())
                .containsExactly(first.getId(), second.getId());
    }

    private Trip trip(Long userId, ProcessingStatus status, boolean deleted) {
        var trip = new Trip(userId, "map-" + UUID.randomUUID().toString().substring(0, 4), LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        if (deleted) ReflectionTestUtils.setField(trip, "deletedAt", LocalDateTime.now());
        return trips.save(trip);
    }

    private TripAttachment photo(Trip trip, Integer score, BigDecimal latitude, BigDecimal longitude, String key) {
        var file = StoredFile.uploaded(trip.getUserId(), key + ".jpg", "original/" + UUID.randomUUID(), "image/jpeg");
        file.storageSize(12L);
        files.save(file);
        Long placeId = placeIds.computeIfAbsent(trip.getId(), id -> places.save(TripDetailPlace.fromAnalysis(
                id, 1, "place", BigDecimal.ONE, BigDecimal.TEN, LocalDateTime.now(), LocalDateTime.now(), "place-preview")).getId());
        var attachment = TripAttachment.initial(trip.getId(), file.getId(), "analyze/" + key, "preview/" + key);
        attachment.storageSizes(8L, 4L, null);
        attachment.classify(placeId, RegionOrigin.EXIF, LocalDateTime.now(), latitude, longitude, score);
        return attachments.save(attachment);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
