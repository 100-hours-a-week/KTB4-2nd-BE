package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({AdditionalAttachmentModificationGuard.class, TripAttachmentDeletionService.class, TripObjectCleanupService.class, UserStatsService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripAttachmentDeletionPersistenceTest {
    @MockitoBean
    private com.yeodam.yeodambe.trip.service.TripMapCacheStore mapCache;
    @Autowired
    private TripAttachmentDeletionService service;
    @Autowired
    private UserRepository users;
    @Autowired
    private UserStatsRepository stats;
    @Autowired
    private TripRepository trips;
    @Autowired
    private TripDetailPlaceRepository places;
    @Autowired
    private StoredFileRepository files;
    @Autowired
    private TripAttachmentRepository attachments;

    @MockitoBean
    private InitialAttachmentUploadTransactionService uploadTransactions;

    @MockitoBean
    private AdditionalAttachmentUploadTransactionService additionalTransactions;

    @MockitoBean

    private TripAttachmentStorageClient storage;

    @Test
    void 대표사진을_삭제하면_남은_사진중_최고평가_사진키를_저장한다() {
        User owner = users.saveAndFlush(new User("delete-thumbnail@test.com", "대표삭제"));
        stats.saveAndFlush(new UserStats(owner));
        Trip trip = trips.saveAndFlush(Trip.localMock(
                owner.getUserId(), "대표삭제", LocalDate.now(), LocalDate.now(), "preview-old"));
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        trips.saveAndFlush(trip);
        TripDetailPlace place = savePlace(trip.getId(), "preview-old");
        TripAttachment old = saveAttachment(owner.getUserId(), trip.getId(), place.getId(),
                "old", 100);
        saveAttachment(owner.getUserId(), trip.getId(), place.getId(), "lower", 70);
        TripAttachment replacement = saveAttachment(
                owner.getUserId(), trip.getId(), place.getId(), "replacement", 95);

        service.deleteOne(owner.getUserId(), old.getId());

        assertThat(places.findById(place.getId()).orElseThrow().getThumbnailKey())
                .isEqualTo(replacement.getPreviewStorageKey());
        assertThat(places.findById(place.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(trips.findById(trip.getId()).orElseThrow().getThumbnailKey())
                .isEqualTo(replacement.getPreviewStorageKey());
        assertThat(attachments.findById(old.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(files.findById(old.getFileId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageUsedBytes())
                .isEqualTo(20L);
        verifyNoInteractions(storage);
    }

    @Test
    void 마지막_대표사진을_삭제하면_장소도_같은시각에_삭제처리한다() {
        User owner = users.saveAndFlush(new User("delete-last@test.com", "마지막삭제"));
        stats.saveAndFlush(new UserStats(owner));
        Trip trip = trips.saveAndFlush(Trip.localMock(
                owner.getUserId(), "마지막삭제", LocalDate.now(), LocalDate.now(), "preview-only"));
        TripDetailPlace place = savePlace(trip.getId(), "preview-only");
        TripAttachment only = saveAttachment(
                owner.getUserId(), trip.getId(), place.getId(), "only", 100);

        service.deleteOne(owner.getUserId(), only.getId());

        var deletedPlace = places.findById(place.getId()).orElseThrow();
        assertThat(deletedPlace.getThumbnailKey()).isNull();
        assertThat(deletedPlace.getDeletedAt()).isNotNull()
                .isEqualTo(attachments.findById(only.getId()).orElseThrow().getDeletedAt());
        assertThat(trips.findById(trip.getId()).orElseThrow().getThumbnailKey()).isNull();
        assertThat(attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                trip.getId(), ClassificationStatus.ACTIVE)).isZero();
    }

    @Test
    void 여행_대표사진을_일괄_삭제하면_남은_최고평가_사진으로_교체한다() {
        User owner = users.saveAndFlush(new User("bulk-trip-thumbnail@test.com", "일괄삭제"));
        stats.saveAndFlush(new UserStats(owner));
        Trip trip = trips.saveAndFlush(Trip.localMock(
                owner.getUserId(), "일괄삭제", LocalDate.now(), LocalDate.now(), "preview-old"));
        TripDetailPlace place = savePlace(trip.getId(), "preview-old");
        TripAttachment old = saveAttachment(
                owner.getUserId(), trip.getId(), place.getId(), "old", 100);
        TripAttachment alsoDeleted = saveAttachment(
                owner.getUserId(), trip.getId(), place.getId(), "also-deleted", 95);
        TripAttachment replacement = saveAttachment(
                owner.getUserId(), trip.getId(), place.getId(), "replacement", 70);

        service.deleteBulk(owner.getUserId(), List.of(old.getId(), alsoDeleted.getId()));

        assertThat(trips.findById(trip.getId()).orElseThrow().getThumbnailKey())
                .isEqualTo(replacement.getPreviewStorageKey());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"stale-preview"})
    void 대표키가_없거나_잘못되어도_마지막_사진을_삭제하면_장소를_삭제한다(String thumbnailKey) {
        User owner = users.saveAndFlush(new User("missing-thumbnail-" + java.util.UUID.randomUUID() + "@test.com", "마지막삭제"));
        stats.saveAndFlush(new UserStats(owner));
        Trip trip = trips.saveAndFlush(Trip.localMock(owner.getUserId(), "대표키누락", LocalDate.now(), LocalDate.now(), null));
        TripDetailPlace place = savePlace(trip.getId(), thumbnailKey);
        TripAttachment only = saveAttachment(owner.getUserId(), trip.getId(), place.getId(),
                "no-thumbnail-" + java.util.UUID.randomUUID(), 80);

        service.deleteOne(owner.getUserId(), only.getId());

        var deletedPlace = places.findById(place.getId()).orElseThrow();
        assertThat(deletedPlace.getDeletedAt()).isNotNull();
        assertThat(deletedPlace.getThumbnailKey()).isNull();
        verifyNoInteractions(storage);
    }

    @Test
    void 같은_폴더의_사진을_모두_일괄삭제해도_다른_폴더는_유지한다() {
        User owner = users.saveAndFlush(new User("empty-bulk@test.com", "전체삭제"));
        stats.saveAndFlush(new UserStats(owner));
        Trip trip = trips.saveAndFlush(Trip.localMock(owner.getUserId(), "일괄삭제", LocalDate.now(), LocalDate.now(), null));
        TripDetailPlace removed = savePlace(trip.getId(), null);
        TripDetailPlace retained = savePlace(trip.getId(), "preview-retained-bulk");
        var first = saveAttachment(owner.getUserId(), trip.getId(), removed.getId(), "first-bulk", 90);
        var second = saveAttachment(owner.getUserId(), trip.getId(), removed.getId(), "second-bulk", 80);
        var remaining = saveAttachment(owner.getUserId(), trip.getId(), retained.getId(), "retained-bulk", 70);

        service.deleteBulk(owner.getUserId(), List.of(first.getId(), second.getId()));

        assertThat(places.findById(removed.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(places.findById(retained.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(attachments.findById(remaining.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(places.findPlaceFoldersWithCursor(trip.getId(), null, null,
                org.springframework.data.domain.PageRequest.of(0, 7)))
                .extracting(TripDetailPlace::getId).containsExactly(retained.getId());
    }

    private TripDetailPlace savePlace(
            Long tripId,
            String thumbnailKey
    ) {
        return places.saveAndFlush(TripDetailPlace.fromAnalysis(
                tripId,
                1,
                "제주시",
                new BigDecimal("33.45000000"),
                new BigDecimal("126.94000000"),
                LocalDateTime.of(2026, 9, 1, 10, 0),
                LocalDateTime.of(2026, 9, 1, 11, 0),
                thumbnailKey
        ));
    }

    private TripAttachment saveAttachment(
            Long userId,
            Long tripId,
            Long placeId,
            String key,
            int evaluation
    ) {
        StoredFile file = StoredFile.uploaded(
                userId, key + ".jpg", "original/" + key, "image/jpeg");
        file.storageSize(10L);
        files.saveAndFlush(file);
        TripAttachment attachment = TripAttachment.initial(
                tripId, file.getId(), "analyze/" + key, "preview-" + key,
                "display/" + key);
        attachment.storageSizes(10L, 10L, 10L);
        attachment.classify(
                placeId,
                RegionOrigin.EXIF,
                LocalDateTime.of(2026, 9, 1, 10, 0),
                new BigDecimal("33.45000000"),
                new BigDecimal("126.94000000"),
                evaluation
        );
        return attachments.saveAndFlush(attachment);
    }
}
