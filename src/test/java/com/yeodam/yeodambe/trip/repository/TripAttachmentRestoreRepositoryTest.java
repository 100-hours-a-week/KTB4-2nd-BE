package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TripAttachmentRestoreRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private StoredFileRepository files;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private EntityManager entityManager;

    private static final LocalDateTime TAKEN_AT = LocalDateTime.of(2026, 9, 1, 10, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 10, 12, 0);

    @ParameterizedTest
    @EnumSource(value = AttachmentIssue.class, names = {"BLURRY", "DUPLICATED", "UNCLEAR_LOCATION"})
    void 허용사유의_사진은_메타데이터를_보존하며_복구한다(AttachmentIssue issue) {
        Fixture fixture = fixture(issue);
        TripAttachment before = fixture.attachment();
        assertThat(restore(fixture, null)).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();
        TripAttachment restored = attachments.findById(before.getId()).orElseThrow();
        assertThat(restored.getTripPlaceId()).isEqualTo(fixture.place().getId());
        assertThat(restored.getClassificationStatus()).isEqualTo(ClassificationStatus.ACTIVE);
        assertThat(restored.getIssue()).isEqualTo(AttachmentIssue.NONE);
        assertThat(restored.getUpdatedAt()).isEqualTo(UPDATED_AT);
        assertThat(restored).usingRecursiveComparison()
                .ignoringFields("tripPlaceId", "tripPlace", "classificationStatus", "issue", "updatedAt", "trip", "file")
                .isEqualTo(before);
        assertThat(files.findById(before.getFileId()).orElseThrow().getObjectKey()).isEqualTo("original/photo");
    }

    @Test
    void 원래매핑과_일치하는_경우만_복구한다() {
        Fixture fixture = fixture(AttachmentIssue.BLURRY);
        ReflectionTestUtils.setField(fixture.attachment(), "tripPlaceId", fixture.place().getId());
        assertThat(restore(fixture, null)).isZero();
        assertThat(restore(fixture, fixture.place().getId() + 1)).isZero();
        assertThat(restore(fixture, fixture.place().getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"PROCESSING", "FAILED", "CANCELED"})
    void 완료되지_않은_여행은_거부한다(ProcessingStatus status) {
        Fixture fixture = fixture(AttachmentIssue.BLURRY);
        ReflectionTestUtils.setField(fixture.trip(), "processingStatus", status);
        assertThat(restore(fixture, null)).isZero();
    }

    @Test
    void NONE_사유의_미분류_사진은_거부한다() {
        Fixture fixture = fixture(AttachmentIssue.NONE);
        assertThat(restore(fixture, null)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = ClassificationStatus.class, names = {"ACTIVE", "DISCARDED", "DELETED"})
    void 허용사유여도_미분류가_아닌_사진은_변경하지_않는다(ClassificationStatus status) {
        Fixture fixture = fixture(AttachmentIssue.BLURRY);
        ReflectionTestUtils.setField(fixture.attachment(), "classificationStatus", status);
        entityManager.flush();
        TripAttachment before = fixture.attachment();
        assertThat(restore(fixture, null)).isZero();
        entityManager.flush();
        entityManager.clear();
        TripAttachment unchanged = attachments.findById(before.getId()).orElseThrow();
        assertThat(unchanged.getClassificationStatus()).isEqualTo(status);
        assertThat(unchanged.getIssue()).isEqualTo(AttachmentIssue.BLURRY);
        assertThat(unchanged).usingRecursiveComparison()
                .ignoringFields("tripPlace", "trip", "file")
                .isEqualTo(before);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"attachment", "file", "trip"})
    void 삭제된_첨부_파일_여행은_거부한다(String deletedEntity) {
        Fixture fixture = fixture(AttachmentIssue.BLURRY);
        Object target = switch (deletedEntity) {
            case "attachment" -> fixture.attachment();
            case "file" -> fixture.file();
            default -> fixture.trip();
        };
        ReflectionTestUtils.setField(target, "deletedAt", UPDATED_AT);
        assertThat(restore(fixture, null)).isZero();
        entityManager.clear();
        assertThat(attachments.findById(fixture.attachment().getId()).orElseThrow().getClassificationStatus())
                .isEqualTo(ClassificationStatus.UNCLASSIFIED);
    }

    @Test
    void 다른_소유자와_다른_여행은_거부한다() {
        Fixture fixture = fixture(AttachmentIssue.BLURRY);
        assertThat(attachments.restoreIfUnclassified(fixture.attachment().getId(), fixture.trip().getId(),
                fixture.trip().getUserId() + 1, null, fixture.place().getId(), UPDATED_AT)).isZero();
        assertThat(attachments.restoreIfUnclassified(fixture.attachment().getId(), fixture.trip().getId() + 1,
                fixture.trip().getUserId(), null, fixture.place().getId(), UPDATED_AT)).isZero();
    }

    @Test
    void 목적지_잠금조회는_존재여부를_반환한다() {
        Fixture fixture = fixture(AttachmentIssue.BLURRY);
        assertThat(places.findByIdForUpdate(fixture.place().getId())).contains(fixture.place());
        assertThat(places.findByIdForUpdate(Long.MAX_VALUE)).isEmpty();
    }

    private int restore(Fixture fixture, Long expectedPlaceId) {
        return attachments.restoreIfUnclassified(fixture.attachment().getId(), fixture.trip().getId(),
                fixture.trip().getUserId(), expectedPlaceId, fixture.place().getId(), UPDATED_AT);
    }

    private Fixture fixture(AttachmentIssue issue) {
        User owner = users.save(new User("restore@test.com", "소유자"));
        Trip trip = new Trip(owner.getUserId(), "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        trips.save(trip);
        TripDetailPlace place = places.save(TripDetailPlace.fromAnalysis(trip.getId(), 1, "제주시",
                new BigDecimal("33.45000000"), new BigDecimal("126.94000000"), TAKEN_AT, TAKEN_AT, "preview/photo"));
        StoredFile file = StoredFile.uploaded(owner.getUserId(), "photo.jpg", "original/photo", "image/jpeg");
        file.storageSize(10L);
        files.save(file);
        TripAttachment attachment = TripAttachment.initial(trip.getId(), file.getId(), "analyze/photo", "preview/photo", "display/photo");
        attachment.storageSizes(20L, 30L, 40L);
        attachment.unclassify(null, issue, RegionOrigin.EXIF, TAKEN_AT,
                new BigDecimal("33.45000000"), new BigDecimal("126.94000000"), 85);
        ReflectionTestUtils.setField(attachment, "deviceModel", "camera");
        attachments.saveAndFlush(attachment);
        return new Fixture(trip, place, file, attachment);
    }

    private record Fixture(
            Trip trip,
            TripDetailPlace place,
            StoredFile file,
            TripAttachment attachment
    ) {
    }
}
