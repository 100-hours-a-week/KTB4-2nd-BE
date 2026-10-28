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
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.trip.service.response.AttachmentRestoreResponse;
import com.yeodam.yeodambe.trip.service.response.BulkAttachmentRestoreResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.yeodam.yeodambe.trip.service.request.AttachmentRestoreRequest;
import com.yeodam.yeodambe.trip.service.request.BulkAttachmentRestoreRequest;
import com.yeodam.yeodambe.common.exception.BulkRestoreFailedException;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import jakarta.validation.Validator;
import jakarta.validation.Validation;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};MODE=MySQL",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({TripAttachmentRestoreService.class, AdditionalAttachmentModificationGuard.class, TripAccessService.class, TripAttachmentRestorePersistenceTest.ValidationConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripAttachmentRestorePersistenceTest {
    @MockitoBean
    private com.yeodam.yeodambe.trip.service.TripMapCacheStore mapCache;
    @Autowired private TripAttachmentRestoreService service;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @MockitoSpyBean private TripAttachmentRepository attachments;

    @MockitoBean private TripAttachmentStorageClient storage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class ValidationConfig {
        @Bean
        Validator validator() {
            return Validation.buildDefaultValidatorFactory().getValidator();
        }
    }

    @Test
    void 복구는_메타데이터와_통계를_보존하고_대표사진을_갱신한다() {
        User owner = users.saveAndFlush(new User("restore@test.com", "복구"));
        stats.saveAndFlush(new UserStats(owner));
        Trip trip = completedTrip(owner, "복구");
        TripDetailPlace place = savePlace(trip.getId(), "old");
        TripAttachment attachment = saveAttachment(owner.getUserId(), trip.getId(), place.getId(), "restored", 95);
        attachment.unclassify(attachment.getTripPlaceId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF, attachment.getTakenAt(),
                attachment.getLatitude(), attachment.getLongitude(), attachment.getEvaluation());
        attachments.saveAndFlush(attachment);
        long storageBefore = stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageUsedBytes();

        var response = service.restoreOne(owner.getUserId(), attachment.getId(), new AttachmentRestoreRequest(place.getId()));

        TripAttachment restored = attachments.findById(attachment.getId()).orElseThrow();
        assertThat(response.classificationType()).isEqualTo("CLASSIFIED");
        assertThat(restored.getClassificationStatus()).isEqualTo(ClassificationStatus.ACTIVE);
        assertThat(restored.getIssue()).isEqualTo(AttachmentIssue.NONE);
        assertThat(restored.getTakenAt()).isEqualTo(attachment.getTakenAt());
        assertThat(restored.getLatitude()).isEqualTo(attachment.getLatitude());
        assertThat(restored.getLongitude()).isEqualTo(attachment.getLongitude());
        assertThat(restored.getEvaluation()).isEqualTo(95);
        assertThat(restored.getAnalyzeStorageKey()).isEqualTo(attachment.getAnalyzeStorageKey());
        assertThat(restored.getDisplayStorageKey()).isEqualTo(attachment.getDisplayStorageKey());
        assertThat(restored.getDeletedAt()).isNull();
        assertThat(files.findById(restored.getFileId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(places.findById(place.getId()).orElseThrow().getThumbnailKey()).isEqualTo("preview-restored");
        assertThat(trips.findById(trip.getId()).orElseThrow().getThumbnailKey()).isEqualTo("preview-restored");
        assertThat(stats.findByUser_UserId(owner.getUserId()).orElseThrow().getStorageUsedBytes()).isEqualTo(storageBefore);
    }

    @Test
    void 마지막_항목이_잘못되면_전체가_변경되지_않는다() {
        User owner = users.saveAndFlush(new User("restore-rollback@test.com", "롤백"));
        Trip trip = completedTrip(owner, "롤백");
        TripDetailPlace place = savePlace(trip.getId(), "old");
        TripAttachment first = saveAttachment(owner.getUserId(), trip.getId(), place.getId(), "first", 95);
        first.unclassify(first.getTripPlaceId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF, first.getTakenAt(), first.getLatitude(), first.getLongitude(), 95);
        attachments.saveAndFlush(first);
        TripAttachment last = saveAttachment(owner.getUserId(), trip.getId(), place.getId(), "last", 90);
        var request = new BulkAttachmentRestoreRequest(List.of(
                new BulkAttachmentRestoreRequest.Item(first.getId(), place.getId()),
                new BulkAttachmentRestoreRequest.Item(last.getId(), place.getId())
        ));

        assertThatThrownBy(() -> service.restoreBulk(owner.getUserId(), request)).isInstanceOf(BulkRestoreFailedException.class);

        assertThat(attachments.findById(first.getId()).orElseThrow().getClassificationStatus()).isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(places.findById(place.getId()).orElseThrow().getThumbnailKey()).isEqualTo("old");
        assertThat(trips.findById(trip.getId()).orElseThrow().getThumbnailKey()).isEqualTo("old");
    }

    @Test
    void 대표조회실패는_이미복구한_첨부까지_롤백한다() {
        User owner = users.saveAndFlush(new User("restore-thumb-fail@test.com", "대표실패"));
        Trip trip = completedTrip(owner, "대표실패");
        TripDetailPlace place = savePlace(trip.getId(), "old");
        TripAttachment attachment = saveAttachment(owner.getUserId(), trip.getId(), place.getId(), "failure", 90);
        attachment.unclassify(attachment.getTripPlaceId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF, attachment.getTakenAt(),
                attachment.getLatitude(), attachment.getLongitude(), 90);
        attachments.saveAndFlush(attachment);
        doThrow(new IllegalStateException("thumbnail failure")).when(attachments)
                .findAllActiveByTripId(trip.getId(), ClassificationStatus.ACTIVE);

        assertThatThrownBy(() -> service.restoreOne(owner.getUserId(), attachment.getId(), new AttachmentRestoreRequest(place.getId())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(attachments.findById(attachment.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(attachments.findById(attachment.getId()).orElseThrow().getIssue()).isEqualTo(AttachmentIssue.BLURRY);
        assertThat(places.findById(place.getId()).orElseThrow().getThumbnailKey()).isEqualTo("old");
        assertThat(trips.findById(trip.getId()).orElseThrow().getThumbnailKey()).isEqualTo("old");
    }

    @Test
    void 마지막업데이트_영건은_앞선복구를_롤백한다() {
        User owner = users.saveAndFlush(new User("restore-update-fail@test.com", "변경실패"));
        Trip trip = completedTrip(owner, "변경실패");
        TripDetailPlace place = savePlace(trip.getId(), "old");
        TripAttachment first = saveAttachment(owner.getUserId(), trip.getId(), place.getId(), "first-update", 90);
        TripAttachment last = saveAttachment(owner.getUserId(), trip.getId(), place.getId(), "last-update", 80);
        for (TripAttachment attachment : List.of(first, last)) {
            attachment.unclassify(attachment.getTripPlaceId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF, attachment.getTakenAt(),
                    attachment.getLatitude(), attachment.getLongitude(), attachment.getEvaluation());
            attachments.saveAndFlush(attachment);
        }
        doReturn(0).when(attachments).restoreIfUnclassified(eq(last.getId()), eq(trip.getId()),
                eq(owner.getUserId()), eq(place.getId()), eq(place.getId()), any());
        var request = new BulkAttachmentRestoreRequest(List.of(
                new BulkAttachmentRestoreRequest.Item(first.getId(), place.getId()),
                new BulkAttachmentRestoreRequest.Item(last.getId(), place.getId())
        ));

        assertThatThrownBy(() -> service.restoreBulk(owner.getUserId(), request))
                .isInstanceOf(BulkRestoreFailedException.class);

        assertThat(attachments.findById(first.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(attachments.findById(last.getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(places.findById(place.getId()).orElseThrow().getThumbnailKey()).isEqualTo("old");
    }

    @ParameterizedTest
    @EnumSource(value = AdditionalAttachmentUploadStatus.class, names = {
            "PENDING", "VERIFIED", "CONVERTING", "PREPARED", "QUEUED", "PROCESSING"
    })
    void 사진추가_진행중이면_완료여행의_단건복원을_차단한다(AdditionalAttachmentUploadStatus status) {
        RestoreFixture fixture = restoreFixture();
        saveBatch(fixture, status);

        assertThatThrownBy(() -> service.restoreOne(
                fixture.userId(), fixture.attachmentId(), new AttachmentRestoreRequest(fixture.placeId())
        )).isInstanceOf(TripAttachmentAddNotAllowedException.class);

        assertUnchanged(fixture);
        verifyNoInteractions(mapCache);
    }

    @ParameterizedTest
    @EnumSource(value = AdditionalAttachmentUploadStatus.class, names = {
            "PENDING", "VERIFIED", "CONVERTING", "PREPARED", "QUEUED", "PROCESSING"
    })
    void 다건복원에서_한여행만_사진추가중이어도_전체를_변경하지_않는다(
            AdditionalAttachmentUploadStatus status
    ) {
        RestoreFixture first = restoreFixture();
        User owner = users.findById(first.userId()).orElseThrow();
        RestoreFixture second = restoreFixture(owner);
        saveBatch(second, status);
        BulkAttachmentRestoreRequest request = new BulkAttachmentRestoreRequest(List.of(
                new BulkAttachmentRestoreRequest.Item(first.attachmentId(), first.placeId()),
                new BulkAttachmentRestoreRequest.Item(second.attachmentId(), second.placeId())
        ));

        assertThatThrownBy(() -> service.restoreBulk(first.userId(), request))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);

        assertUnchanged(first);
        assertUnchanged(second);
        verifyNoInteractions(mapCache);
    }

    @ParameterizedTest
    @EnumSource(value = AdditionalAttachmentUploadStatus.class, names = {"COMPLETED", "FAILED"})
    void 사진추가_종료후에는_단건복원을_허용한다(AdditionalAttachmentUploadStatus status) {
        RestoreFixture fixture = restoreFixture();
        saveBatch(fixture, status);

        AttachmentRestoreResponse response = service.restoreOne(
                fixture.userId(), fixture.attachmentId(), new AttachmentRestoreRequest(fixture.placeId())
        );

        assertThat(response.classificationType()).isEqualTo("CLASSIFIED");
        assertThat(attachments.findById(fixture.attachmentId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.ACTIVE);
        verify(mapCache).evictAfterCommit(fixture.userId());
    }

    @ParameterizedTest
    @EnumSource(value = AdditionalAttachmentUploadStatus.class, names = {"COMPLETED", "FAILED"})
    void 사진추가_종료후에는_다건복원을_허용한다(AdditionalAttachmentUploadStatus status) {
        RestoreFixture fixture = restoreFixture();
        saveBatch(fixture, status);
        BulkAttachmentRestoreRequest request = new BulkAttachmentRestoreRequest(List.of(
                new BulkAttachmentRestoreRequest.Item(fixture.attachmentId(), fixture.placeId())
        ));

        BulkAttachmentRestoreResponse response = service.restoreBulk(fixture.userId(), request);

        assertThat(response.restoredTripAttachmentIds()).containsExactly(fixture.attachmentId());
        assertThat(attachments.findById(fixture.attachmentId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.ACTIVE);
        verify(mapCache).evictAfterCommit(fixture.userId());
    }

    private RestoreFixture restoreFixture() {
        User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@test.com", "복원가드"));
        stats.saveAndFlush(new UserStats(owner));
        return restoreFixture(owner);
    }

    private RestoreFixture restoreFixture(User owner) {
        Trip trip = completedTrip(owner, "복원가드");
        TripDetailPlace place = savePlace(trip.getId(), "old");
        TripAttachment attachment = saveAttachment(
                owner.getUserId(), trip.getId(), place.getId(), UUID.randomUUID().toString(), 90
        );
        attachment.unclassify(place.getId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF,
                attachment.getTakenAt(), attachment.getLatitude(), attachment.getLongitude(), 90);
        attachments.saveAndFlush(attachment);
        return new RestoreFixture(owner.getUserId(), trip.getId(), place.getId(), attachment.getId());
    }

    private void saveBatch(RestoreFixture fixture, AdditionalAttachmentUploadStatus status) {
        jdbcTemplate.update("""
                INSERT INTO additional_attachment_upload_batches
                    (upload_id, addition_id, trip_id, user_id, batch_no, total_attachment_count,
                     is_last_batch, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 1, true, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                fixture.tripId(), fixture.userId(), status.name());
    }

    private void assertUnchanged(RestoreFixture fixture) {
        TripAttachment attachment = attachments.findById(fixture.attachmentId()).orElseThrow();
        assertThat(attachment.getClassificationStatus()).isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(attachment.getIssue()).isEqualTo(AttachmentIssue.BLURRY);
        assertThat(attachment.getTripPlaceId()).isEqualTo(fixture.placeId());
        assertThat(places.findById(fixture.placeId()).orElseThrow().getThumbnailKey()).isEqualTo("old");
        assertThat(trips.findById(fixture.tripId()).orElseThrow().getThumbnailKey()).isEqualTo("old");
        assertThat(stats.findByUser_UserId(fixture.userId()).orElseThrow().getStorageUsedBytes()).isZero();
    }

    private record RestoreFixture(
            Long userId,
            Long tripId,
            Long placeId,
            Long attachmentId
    ) {
    }

    private Trip completedTrip(User owner, String name) {
        Trip trip = Trip.localMock(owner.getUserId(), name, LocalDate.now(), LocalDate.now(), "old");
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        return trips.saveAndFlush(trip);
    }

    private TripDetailPlace savePlace(Long tripId, String thumbnailKey) {
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
