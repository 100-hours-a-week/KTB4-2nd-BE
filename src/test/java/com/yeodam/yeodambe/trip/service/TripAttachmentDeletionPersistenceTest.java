package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(TripAttachmentDeletionService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripAttachmentDeletionPersistenceTest {
    @Autowired private TripAttachmentDeletionService service;
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;

    @Test
    void 대표사진을_삭제하면_남은_사진중_최고평가_사진키를_저장한다() {
        User owner = users.saveAndFlush(new User("delete-thumbnail@test.com", "대표삭제"));
        Trip trip = trips.saveAndFlush(new Trip(
                owner.getUserId(), "대표삭제", LocalDate.now(), LocalDate.now()));
        TripDetailPlace place = savePlace(trip.getId(), "preview-old");
        TripAttachment old = saveAttachment(owner.getUserId(), trip.getId(), place.getId(),
                "old", 100);
        saveAttachment(owner.getUserId(), trip.getId(), place.getId(), "lower", 70);
        TripAttachment replacement = saveAttachment(
                owner.getUserId(), trip.getId(), place.getId(), "replacement", 95);

        service.deleteOne(owner.getUserId(), old.getId());

        assertThat(places.findById(place.getId()).orElseThrow().getThumbnailKey())
                .isEqualTo(replacement.getPreviewStorageKey());
        assertThat(attachments.findById(old.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(files.findById(old.getFileId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void 마지막_대표사진을_삭제하면_대표사진키를_null로_저장한다() {
        User owner = users.saveAndFlush(new User("delete-last@test.com", "마지막삭제"));
        Trip trip = trips.saveAndFlush(new Trip(
                owner.getUserId(), "마지막삭제", LocalDate.now(), LocalDate.now()));
        TripDetailPlace place = savePlace(trip.getId(), "preview-only");
        TripAttachment only = saveAttachment(
                owner.getUserId(), trip.getId(), place.getId(), "only", 100);

        service.deleteOne(owner.getUserId(), only.getId());

        assertThat(places.findById(place.getId()).orElseThrow().getThumbnailKey()).isNull();
        assertThat(attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                trip.getId(), ClassificationStatus.ACTIVE)).isZero();
    }

    private TripDetailPlace savePlace(Long tripId, String thumbnailKey) {
        return places.saveAndFlush(TripDetailPlace.fromAnalysis(
                tripId,
                1,
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
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(
                userId, key + ".jpg", "original/" + key, "image/jpeg"));
        TripAttachment attachment = TripAttachment.initial(
                tripId, file.getId(), "analyze/" + key, "preview-" + key);
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
