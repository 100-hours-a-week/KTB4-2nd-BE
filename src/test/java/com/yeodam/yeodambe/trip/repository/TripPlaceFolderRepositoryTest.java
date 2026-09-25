package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TripPlaceFolderRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private EntityManager entityManager;

    @Test
    void 미삭제_폴더를_장소명과_ID순으로_커서_조회한다() {
        User owner = users.save(new User("folder-owner@test.com", "폴더회원"));
        Trip trip = trips.save(new Trip(owner.getUserId(), "폴더여행", LocalDate.now(), LocalDate.now()));
        Trip other = trips.save(new Trip(owner.getUserId(), "다른여행", LocalDate.now(), LocalDate.now()));
        TripDetailPlace alpha1 = savePlace(trip.getId(), "Alpha", null);
        TripDetailPlace alpha2 = savePlace(trip.getId(), "Alpha", null);
        TripDetailPlace beta = savePlace(trip.getId(), "Beta", null);
        savePlace(trip.getId(), "Aardvark", LocalDateTime.now());
        savePlace(other.getId(), "Alpha", null);
        flushAndClear();

        assertThat(places.findPlaceFoldersWithCursor(
                trip.getId(), null, null, PageRequest.of(0, 7)))
                .extracting(TripDetailPlace::getId)
                .containsExactly(alpha1.getId(), alpha2.getId(), beta.getId());

        assertThat(places.findPlaceFoldersWithCursor(
                trip.getId(), "Alpha", alpha1.getId(), PageRequest.of(0, 7)))
                .extracting(TripDetailPlace::getId)
                .containsExactly(alpha2.getId(), beta.getId());
    }

    @Test
    void 활성_첨부와_미삭제_파일만_폴더별로_집계한다() {
        User owner = users.save(new User("count-owner@test.com", "집계회원"));
        Trip trip = trips.save(new Trip(owner.getUserId(), "집계여행", LocalDate.now(), LocalDate.now()));
        TripDetailPlace first = savePlace(trip.getId(), "Alpha", null);
        TripDetailPlace second = savePlace(trip.getId(), "Beta", null);

        saveAttachment(owner.getUserId(), trip.getId(), first.getId(), ClassificationStatus.ACTIVE, false, false);
        saveAttachment(owner.getUserId(), trip.getId(), first.getId(), ClassificationStatus.DISCARDED, false, false);
        saveAttachment(owner.getUserId(), trip.getId(), first.getId(), ClassificationStatus.UNCLASSIFIED, false, false);
        saveAttachment(owner.getUserId(), trip.getId(), first.getId(), ClassificationStatus.DELETED, false, false);
        saveAttachment(owner.getUserId(), trip.getId(), first.getId(), ClassificationStatus.ACTIVE, true, false);
        saveAttachment(owner.getUserId(), trip.getId(), first.getId(), ClassificationStatus.ACTIVE, false, true);
        saveAttachment(owner.getUserId(), trip.getId(), second.getId(), ClassificationStatus.ACTIVE, false, false);
        flushAndClear();

        assertThat(attachments.countActiveByTripPlaceIds(List.of(first.getId(), second.getId())))
                .containsExactlyInAnyOrder(
                        new PlaceFolderAttachmentCount(first.getId(), 1L),
                        new PlaceFolderAttachmentCount(second.getId(), 1L)
                );
    }

    private TripDetailPlace savePlace(Long tripId, String name, LocalDateTime deletedAt) {
        TripDetailPlace place = TripDetailPlace.fromAnalysis(
                tripId,
                1,
                new BigDecimal("33.45000000"),
                new BigDecimal("126.94000000"),
                LocalDateTime.of(2026, 9, 1, 10, 0),
                LocalDateTime.of(2026, 9, 1, 11, 0),
                null
        );
        ReflectionTestUtils.setField(place, "placeName", name);
        ReflectionTestUtils.setField(place, "deletedAt", deletedAt);
        return places.save(place);
    }

    private void saveAttachment(
            Long userId,
            Long tripId,
            Long placeId,
            ClassificationStatus status,
            boolean attachmentDeleted,
            boolean fileDeleted
    ) {
        StoredFile file = StoredFile.uploaded(
                userId,
                "photo.jpg",
                "original/" + System.nanoTime(),
                "image/jpeg"
        );
        if (fileDeleted) file.softDelete(LocalDateTime.now());
        files.save(file);

        TripAttachment attachment = TripAttachment.initial(
                tripId,
                file.getId(),
                "analyze/" + System.nanoTime(),
                "preview/" + System.nanoTime()
        );
        ReflectionTestUtils.setField(attachment, "tripPlaceId", placeId);
        ReflectionTestUtils.setField(attachment, "classificationStatus", status);
        ReflectionTestUtils.setField(attachment, "regionOrigin", RegionOrigin.EXIF);
        ReflectionTestUtils.setField(attachment, "issue", AttachmentIssue.NONE);
        if (attachmentDeleted) attachment.softDelete(LocalDateTime.now());
        attachments.save(attachment);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
