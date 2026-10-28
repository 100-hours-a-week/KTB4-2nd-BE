package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.service.response.TripSearchCandidateResponse;
import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};MODE=MySQL",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TripSearchRepositoryTest {
    @Autowired
    private EntityManager em;
    @Autowired
    private TripAttachmentRepository repository;

    @Test
    void 검색_후보와_결과_조회에_소유권과_처리_상태와_삭제_여부를_적용한다() {
        User owner = user("owner");
        User other = user("other");
        Trip active = trip(owner, "active", true);
        Trip foreign = trip(other, "foreign", true);
        Trip processing = trip(owner, "processing", false);
        Trip deleted = trip(owner, "deleted", true);
        deleted.softDelete(LocalDateTime.now());
        TripAttachment valid = photo(active, owner, null, true);
        TripAttachment foreignPhoto = photo(foreign, other, null, true);
        TripAttachment processingPhoto = photo(processing, owner, null, true);
        TripAttachment deletedTripPhoto = photo(deleted, owner, null, true);
        TripAttachment unclassified = photo(active, owner, null, false);
        TripAttachment removed = photo(active, owner, null, true);
        removed.softDelete(LocalDateTime.now());
        TripAttachment deletedFile = photo(active, owner, null, true);
        em.find(StoredFile.class, deletedFile.getFileId()).softDelete(LocalDateTime.now());
        em.flush();
        List<Long> all = List.of(valid.getId(), foreignPhoto.getId(), processingPhoto.getId(),
                deletedTripPhoto.getId(), unclassified.getId(), removed.getId(), deletedFile.getId());
        em.clear();
        assertThat(repository.findSearchCandidates(owner.getUserId(), null, null,
                false, List.of(""), PageRequest.of(0, 400)))
                .extracting(TripSearchCandidateResponse::tripAttachmentId).containsExactly(valid.getId());
        assertThat(repository.findSearchResults(owner.getUserId(), all))
                .extracting(TripAttachment::getId).containsExactly(valid.getId());
        assertThat(repository.countActiveByTripIds(List.of(active.getId())))
                .containsExactly(new TripAttachmentCount(active.getId(), 1L));
    }

    @Test
    void 날짜_범위에_윤일을_포함하고_지역_조건으로_사진이_중복되지_않는다() {
        User owner = user("date");
        Trip active = trip(owner, "dated", true);
        em.persist(new TripRegion(active, "01", "제주", BigDecimal.valueOf(33.0), BigDecimal.valueOf(126.0)));
        em.persist(new TripRegion(active, "02", "제주", BigDecimal.valueOf(33.1), BigDecimal.valueOf(126.1)));
        TripRegion removedRegion = new TripRegion(active, "03", "속초", BigDecimal.valueOf(38.0), BigDecimal.valueOf(128.0));
        removedRegion.softDelete(LocalDateTime.now());
        em.persist(removedRegion);
        TripAttachment last = photo(active, owner, LocalDateTime.of(2024, 2, 29, 23, 59, 59), true);
        photo(active, owner, LocalDateTime.of(2024, 3, 1, 0, 0), true);
        photo(active, owner, null, true);
        em.flush();
        em.clear();
        assertThat(repository.findSearchCandidates(owner.getUserId(),
                LocalDate.of(2024, 2, 29).atStartOfDay(), LocalDate.of(2024, 3, 1).atStartOfDay(),
                true, List.of("제주"), PageRequest.of(0, 400)))
                .extracting(TripSearchCandidateResponse::tripAttachmentId).containsExactly(last.getId());
        assertThat(repository.findSearchCandidates(owner.getUserId(), null, null,
                true, List.of("속초"), PageRequest.of(0, 400))).isEmpty();
        assertThat(repository.findSearchCandidates(owner.getUserId(), null, null,
                true, List.of("제주"), PageRequest.of(0, 400))).hasSize(3).doesNotHaveDuplicates();
        assertThat(repository.findSearchCandidates(owner.getUserId(), null,
                LocalDate.of(2024, 3, 1).atStartOfDay(), false, List.of(""), PageRequest.of(0, 400)))
                .extracting(TripSearchCandidateResponse::tripAttachmentId).containsExactly(last.getId());
    }

    @Test
    void 삭제되거나_다른_여행에_속한_장소의_사진은_후보와_결과에서_제외한다() {
        User owner = user("placeOwn");
        Trip own = trip(owner, "own", true);
        Trip other = trip(user("placeOther"), "other", true);
        TripDetailPlace deleted = place(own, "삭제 장소");
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
        TripDetailPlace foreign = place(other, "다른 여행 장소");
        TripAttachment deletedPhoto = photo(own, owner, null, true);
        TripAttachment foreignPhoto = photo(own, owner, null, true);
        deletedPhoto.classify(deleted.getId(), RegionOrigin.EXIF, null, null, null, 80);
        foreignPhoto.classify(foreign.getId(), RegionOrigin.EXIF, null, null, null, 80);
        em.flush();
        em.clear();

        assertThat(repository.findSearchCandidates(owner.getUserId(), null, null,
                false, List.of(""), PageRequest.of(0, 400)))
                .isEmpty();
        assertThat(repository.findSearchResults(owner.getUserId(), List.of(deletedPhoto.getId(), foreignPhoto.getId())))
                .isEmpty();
    }

    @Test
    void 보정된_촬영일로_필터하고_KST_자정_하한을_포함한다() {
        User owner = user("corrected");
        Trip own = trip(owner, "corrected", true);
        TripAttachment corrected = photo(own, owner, null, true);
        corrected.originalMetadata(OffsetDateTime.parse("2026-10-11T23:30:00+09:00"),
                null, null, "camera");
        corrected.classify(null, RegionOrigin.UNKNOWN, LocalDateTime.of(2026, 10, 12, 0, 0),
                null, null, 80);
        photo(own, owner, LocalDateTime.of(2026, 10, 13, 0, 0), true);
        photo(own, owner, null, true);
        em.flush();
        em.clear();

        assertThat(repository.findSearchCandidates(owner.getUserId(),
                LocalDateTime.of(2026, 10, 12, 0, 0), LocalDateTime.of(2026, 10, 13, 0, 0),
                false, List.of(""), PageRequest.of(0, 400)))
                .extracting(TripSearchCandidateResponse::tripAttachmentId).containsExactly(corrected.getId());
        assertThat(repository.findSearchCandidates(owner.getUserId(),
                LocalDateTime.of(2026, 10, 12, 0, 0), null, false, List.of(""), PageRequest.of(0, 400)))
                .hasSize(2);
    }

    @Test
    void 장소_사진_수에_다른_여행에서_잘못_연결된_사진을_포함하지_않는다() {
        User owner = user("countOwn");
        Trip own = trip(owner, "countOwn", true);
        User otherUser = user("countOther");
        Trip other = trip(otherUser, "countOther", true);
        TripDetailPlace place = place(own, "장소");
        TripAttachment valid = photo(own, owner, null, true);
        TripAttachment invalid = photo(other, otherUser, null, true);
        valid.classify(place.getId(), RegionOrigin.EXIF, null, null, null, 80);
        invalid.classify(place.getId(), RegionOrigin.EXIF, null, null, null, 80);
        em.flush();
        em.clear();

        assertThat(repository.countActiveByTripPlaceIds(List.of(place.getId())))
                .containsExactly(new PlaceFolderAttachmentCount(place.getId(), 1L));
    }

    private TripDetailPlace place(Trip trip, String name) {
        TripDetailPlace place = TripDetailPlace.fromAnalysis(trip.getId(), 1, name,
                BigDecimal.ZERO, BigDecimal.ZERO, LocalDateTime.of(2026, 10, 12, 0, 0),
                LocalDateTime.of(2026, 10, 12, 1, 0), "place-preview");
        em.persist(place);
        return place;
    }

    private User user(String name) {
        User user = new User(name + "@search.test", name);
        em.persist(user);
        return user;
    }

    private Trip trip(User owner, String name, boolean completed) {
        Trip trip = new Trip(owner.getUserId(), name, LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1));
        em.persist(trip);
        if (completed) {
            em.createQuery("update Trip t set t.processingStatus = :status where t.id = :id")
                    .setParameter("status", ProcessingStatus.COMPLETED)
                    .setParameter("id", trip.getId()).executeUpdate();
            em.refresh(trip);
        }
        return trip;
    }

    private TripAttachment photo(Trip trip, User owner, LocalDateTime taken, boolean active) {
        StoredFile file = StoredFile.uploaded(owner.getUserId(), "photo.jpg", "original", "image/jpeg");
        em.persist(file);
        TripAttachment photo = TripAttachment.initial(trip.getId(), file.getId(), "analyze", "preview");
        if (active) {
            photo.classify(null, RegionOrigin.UNKNOWN, taken, null, null, 80);
        }
        em.persist(photo);
        return photo;
    }
}
