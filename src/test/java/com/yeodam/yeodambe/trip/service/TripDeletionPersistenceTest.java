package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripStorageObjectKeys;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({TripDeletionService.class, TripObjectCleanupService.class, UserStatsService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripDeletionPersistenceTest {
    @Autowired private TripDeletionService service;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private TripRepository trips;
    @Autowired private TripRegionRepository regions;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;

    @MockitoBean private TripAttachmentStorageClient storage;

    @Test
    void 여행과_연관_데이터를_같은_시각에_삭제하고_남은_통계를_저장한다() {
        User owner = owner("trip-delete@test.com");
        Trip target = completedTrip(owner, "삭제여행");
        Trip remaining = completedTrip(owner, "남은여행");
        TripRegion region = regions.saveAndFlush(new TripRegion(
                target, "50110", "제주시", new BigDecimal("33.5"), new BigDecimal("126.5")));
        TripDetailPlace place = places.saveAndFlush(TripDetailPlace.fromAnalysis(
                target.getId(), 1, new BigDecimal("33.5"), new BigDecimal("126.5"),
                LocalDateTime.now(), LocalDateTime.now(), "target-preview"));
        TripAttachment deletedAttachment = attachment(owner, target, place.getId(), "target");
        TripAttachment remainingAttachment = attachment(owner, remaining, null, "remaining");
        when(storage.size(anyString())).thenReturn(10L);

        service.delete(target.getId(), owner.getUserId());

        LocalDateTime deletedAt = trips.findById(target.getId()).orElseThrow().getDeletedAt();
        assertThat(deletedAt).isNotNull();
        assertThat(regions.findById(region.getId()).orElseThrow().getDeletedAt()).isEqualTo(deletedAt);
        assertThat(places.findById(place.getId()).orElseThrow().getDeletedAt()).isEqualTo(deletedAt);
        assertThat(attachments.findById(deletedAttachment.getId()).orElseThrow().getDeletedAt())
                .isEqualTo(deletedAt);
        assertThat(files.findById(deletedAttachment.getFileId()).orElseThrow().getDeletedAt())
                .isEqualTo(deletedAt);

        UserStats refreshed = stats.findByUser_UserId(owner.getUserId()).orElseThrow();
        assertThat(refreshed.getTripCount()).isOne();
        assertThat(refreshed.getAttachmentCount()).isOne();
        assertThat(refreshed.getStorageUsedBytes()).isEqualTo(30L);
        assertThat(files.findById(remainingAttachment.getFileId()).orElseThrow().getDeletedAt())
                .isNull();
    }

    @Test
    void 통계_계산이_실패하면_삭제를_전부_롤백한다() {
        User owner = owner("trip-rollback@test.com");
        Trip target = completedTrip(owner, "롤백여행");
        Trip remaining = completedTrip(owner, "남은여행");
        TripAttachment targetAttachment = attachment(owner, target, null, "target");
        attachment(owner, remaining, null, "remaining");
        when(storage.size(anyString())).thenThrow(new IllegalStateException("S3"));

        assertThrows(IllegalStateException.class,
                () -> service.delete(target.getId(), owner.getUserId()));

        assertThat(trips.findById(target.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(attachments.findById(targetAttachment.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(files.findById(targetAttachment.getFileId()).orElseThrow().getDeletedAt()).isNull();
        verify(storage, never()).delete(anyString());
    }

    @Test
    void 다른_소유자의_여행은_삭제할_수_없다() {
        User owner = owner("owned-trip@test.com");
        User requester = owner("other-requester@test.com");
        Trip target = completedTrip(owner, "타인여행");

        assertThrows(TripNotFoundException.class,
                () -> service.delete(target.getId(), requester.getUserId()));

        assertThat(trips.findById(target.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void 이미_삭제된_여행은_다시_삭제할_수_없다() {
        User owner = owner("deleted-trip-owner@test.com");
        Trip target = completedTrip(owner, "삭제된여행");
        LocalDateTime deletedAt = LocalDateTime.now();
        target.softDelete(deletedAt);
        trips.saveAndFlush(target);

        assertThrows(TripNotFoundException.class,
                () -> service.delete(target.getId(), owner.getUserId()));

        assertThat(trips.findById(target.getId()).orElseThrow().getDeletedAt())
                .isEqualTo(deletedAt);
    }

    @Test
    void 통계_조회는_소유자의_완료된_미삭제_첨부와_파일만_반환한다() {
        User owner = owner("stats-owner@test.com");
        User other = owner("stats-other@test.com");
        Trip completed = completedTrip(owner, "완료여행");
        Trip processing = trips.saveAndFlush(new Trip(
                owner.getUserId(), "처리여행", LocalDate.now(), LocalDate.now()));
        Trip otherTrip = completedTrip(other, "타인여행");
        Trip deletedTrip = completedTrip(owner, "삭제여행");
        deletedTrip.softDelete(LocalDateTime.now());
        trips.saveAndFlush(deletedTrip);

        attachment(owner, completed, null, "included");
        attachment(owner, processing, null, "processing");
        attachment(other, otherTrip, null, "other");
        attachment(owner, deletedTrip, null, "deleted-trip");
        TripAttachment deletedAttachment = attachment(owner, completed, null, "deleted-attachment");
        deletedAttachment.softDelete(LocalDateTime.now());
        attachments.saveAndFlush(deletedAttachment);
        TripAttachment deletedFileAttachment = attachment(owner, completed, null, "deleted-file");
        StoredFile deletedFile = files.findById(deletedFileAttachment.getFileId()).orElseThrow();
        deletedFile.softDelete(LocalDateTime.now());
        files.saveAndFlush(deletedFile);

        List<TripStorageObjectKeys> result = attachments.findAllForStats(
                owner.getUserId(), ProcessingStatus.COMPLETED);

        assertThat(result).containsExactly(new TripStorageObjectKeys(
                "original/included", "analyze/included", "preview/included"));
    }

    @Test
    void 객체_정리_재조회는_삭제됐지만_정리되지_않은_첨부만_반환한다() {
        User owner = owner("cleanup-query@test.com");
        Trip trip = completedTrip(owner, "정리여행");
        TripAttachment pending = attachment(owner, trip, null, "pending");
        pending.softDelete(LocalDateTime.now());
        attachments.saveAndFlush(pending);
        TripAttachment completed = attachment(owner, trip, null, "completed");
        completed.softDelete(LocalDateTime.now());
        completed.markObjectCleanupCompleted();
        attachments.saveAndFlush(completed);
        TripAttachment active = attachment(owner, trip, null, "active");

        List<TripAttachment> result = attachments.findPendingCleanup(PageRequest.of(0, 100));

        assertThat(result).extracting(TripAttachment::getId)
                .contains(pending.getId())
                .doesNotContain(completed.getId(), active.getId());
        assertThat(result).allMatch(found ->
                found.getDeletedAt() != null
                        && found.getClassificationStatus() != ClassificationStatus.DELETED);
    }

    private User owner(String email) {
        User owner = users.saveAndFlush(new User(email, "소유자"));
        stats.saveAndFlush(new UserStats(owner));
        return owner;
    }

    private Trip completedTrip(User owner, String name) {
        Trip trip = new Trip(owner.getUserId(), name, LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        return trips.saveAndFlush(trip);
    }

    private TripAttachment attachment(User owner, Trip trip, Long placeId, String key) {
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(
                owner.getUserId(), key + ".jpg", "original/" + key, "image/jpeg"));
        TripAttachment attachment = TripAttachment.initial(
                trip.getId(), file.getId(), "analyze/" + key, "preview/" + key);
        if (placeId != null) {
            attachment.classify(placeId, RegionOrigin.EXIF, LocalDateTime.now(), null, null, 100);
        }
        return attachments.saveAndFlush(attachment);
    }
}
