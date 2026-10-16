package com.yeodam.yeodambe.trip;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.TripAttachmentDeletionService;
import com.yeodam.yeodambe.trip.service.TripMapCacheStore;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripMapCacheIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private TripRepository trips;
    @Autowired private TripRegionRepository regions;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @MockitoSpyBean private TripAttachmentRepository attachments;
    @Autowired private TripService service;
    @Autowired private TripAttachmentDeletionService deletions;
    @Autowired private TripMapCacheStore cache;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private TripAttachmentStorageClient storage;
    private Long userId;
    private Long tripId;
    private Long selectedAttachmentId;

    @BeforeEach
    void prepareCommittedFixture() {
        when(storage.createReadUrl(anyString())).thenAnswer(call -> "https://example.test/" + call.getArgument(0));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var user = users.save(new User(UUID.randomUUID() + "@yeodam.test", "cache"));
            userId = user.getUserId();
            stats.save(new UserStats(user));
            var trip = new Trip(userId, "before", LocalDate.now(), LocalDate.now());
            ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
            trips.save(trip);
            tripId = trip.getId();
            regions.save(new TripRegion(trip, "11000", "서울특별시", BigDecimal.ONE, BigDecimal.TEN));
            var place = places.save(TripDetailPlace.fromAnalysis(tripId, 1, "place",
                    BigDecimal.ONE, BigDecimal.TEN, LocalDateTime.now(), LocalDateTime.now(), "preview/high"));
            selectedAttachmentId = photo(place.getId(), 100, "high", "37.5").getId();
            photo(place.getId(), 80, "low", "35.1");
        });
    }

    @Test
    void repeatMapReadUsesRedisAndSkipsRealRepositoryQueries() {
        var first = service.findMap(userId);
        var second = service.findMap(userId);
        assertThat(second).isEqualTo(first);
        assertThat(second.markers()).singleElement().satisfies(marker -> {
            assertThat(marker.attachmentCount()).isEqualTo(2);
            assertThat(marker.thumbnailUrl()).isEqualTo("https://example.test/preview/high");
        });
        verify(attachments, times(1)).findMapRepresentatives(userId);
        verify(attachments, times(1)).countNotDeletedByTripIds(List.of(tripId));
        verify(storage, times(2)).createReadUrl("preview/high");
    }

    @Test
    void committedRenameChangesVersionAndReturnsNewName() {
        service.findMap(userId);
        String before = cache.version(userId).orElseThrow();
        service.updateTrip(tripId, userId, new TripUpdateRequest("after", null, null, null));
        assertThat(cache.version(userId).orElseThrow()).isNotEqualTo(before);
        assertThat(service.findMap(userId).markers()).singleElement()
                .satisfies(marker -> assertThat(marker.tripName()).isEqualTo("after"));
        verify(attachments, times(2)).findMapRepresentatives(userId);
    }

    @Test
    void committedPhotoDeletionRefreshesCountImageAndCoordinates() {
        service.findMap(userId);
        deletions.deleteOne(userId, selectedAttachmentId);
        assertThat(service.findMap(userId).markers()).singleElement().satisfies(marker -> {
            assertThat(marker.attachmentCount()).isEqualTo(1);
            assertThat(marker.thumbnailUrl()).isEqualTo("https://example.test/preview/low");
            assertThat(marker.latitude()).isEqualByComparingTo("35.1");
        });
        verify(attachments, times(2)).findMapRepresentatives(userId);
    }

    private TripAttachment photo(Long placeId, int score, String key, String latitude) {
        var file = StoredFile.uploaded(userId, key + ".jpg", "original/" + UUID.randomUUID(), "image/jpeg");
        file.storageSize(12L);
        files.save(file);
        var attachment = TripAttachment.initial(tripId, file.getId(), "analyze/" + key, "preview/" + key);
        attachment.storageSizes(8L, 4L, null);
        attachment.classify(placeId, RegionOrigin.EXIF, LocalDateTime.now(),
                new BigDecimal(latitude), new BigDecimal("127.2"), score);
        return attachments.save(attachment);
    }
}
