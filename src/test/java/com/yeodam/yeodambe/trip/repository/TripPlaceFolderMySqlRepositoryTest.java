package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.service.response.TripDetailMapResponse;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import com.yeodam.yeodambe.trip.service.TripService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripPlaceFolderMySqlRepositoryTest {
    @Autowired private UserRepository users;
    @Autowired private TripRepository trips;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private StoredFileRepository files;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private TripService tripService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void 한글_장소명과_동일_이름_ID순으로_다음_페이지를_조회한다() {
        User owner = users.save(new User("mysql-folder@test.com", "폴더회원"));
        Trip trip = trips.save(new Trip(owner.getUserId(), "한글여행", LocalDate.now(), LocalDate.now()));
        Trip other = trips.save(new Trip(owner.getUserId(), "다른여행", LocalDate.now(), LocalDate.now()));
        savePlace(trip.getId(), "가", null);
        TripDetailPlace ga1 = savePlace(trip.getId(), "가", null);
        TripDetailPlace ga2 = savePlace(trip.getId(), "가", null);
        TripDetailPlace na = savePlace(trip.getId(), "나", null);
        TripDetailPlace da = savePlace(trip.getId(), "다", null);
        TripDetailPlace deleted = savePlace(trip.getId(), "라", LocalDateTime.now());
        TripDetailPlace otherPlace = savePlace(other.getId(), "가", null);
        for (TripDetailPlace place : List.of(ga1, ga2, na, da, deleted)) {
            saveActivePhoto(owner.getUserId(), trip.getId(), place.getId());
        }
        saveActivePhoto(owner.getUserId(), other.getId(), otherPlace.getId());

        List<TripDetailPlace> first = places.findPlaceFoldersWithCursor(
                trip.getId(), null, null, PageRequest.of(0, 2));
        TripDetailPlace cursor = first.getLast();
        List<TripDetailPlace> second = places.findPlaceFoldersWithCursor(
                trip.getId(), cursor.getPlaceName(), cursor.getId(), PageRequest.of(0, 2));

        assertThat(first)
                .extracting(TripDetailPlace::getId)
                .containsExactly(ga1.getId(), ga2.getId());
        assertThat(second)
                .extracting(TripDetailPlace::getId)
                .containsExactly(na.getId(), da.getId());
    }

    @Test
    void 지도_통합_집계는_MySQL에서_한글_이름과_ID순으로_반환하고_SQL은_3회다() {
        User owner = users.save(new User("mysql-map@test.com", "지도회원"));
        Trip trip = new Trip(owner.getUserId(), "지도여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        trips.saveAndFlush(trip);
        TripDetailPlace ga1 = savePlace(trip.getId(), "가", null);
        TripDetailPlace ga2 = savePlace(trip.getId(), "가", null);
        TripDetailPlace na = savePlace(trip.getId(), "나", null);
        saveActivePhoto(owner.getUserId(), trip.getId(), ga1.getId());
        saveActivePhoto(owner.getUserId(), trip.getId(), ga1.getId());
        saveActivePhoto(owner.getUserId(), trip.getId(), ga2.getId());
        saveActivePhoto(owner.getUserId(), trip.getId(), na.getId());
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            TripDetailMapResponse response = tripService.findTripDetailMap(trip.getId(), owner.getUserId());

            assertThat(response.markers()).extracting(TripDetailMapResponse.Marker::tripPlaceId)
                    .containsExactly(ga1.getId(), ga2.getId(), na.getId());
            assertThat(response.markers()).extracting(TripDetailMapResponse.Marker::attachmentCount)
                    .containsExactly(2L, 1L, 1L);
            assertThat(response.markers().getFirst().latitude()).isEqualByComparingTo("33.45000000");
            assertThat(response.markers().getFirst().longitude()).isEqualByComparingTo("126.94000000");
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(3L);

            Trip empty = new Trip(owner.getUserId(), "빈지도", LocalDate.now(), LocalDate.now());
            ReflectionTestUtils.setField(empty, "processingStatus", ProcessingStatus.COMPLETED);
            trips.saveAndFlush(empty);
            statistics.clear();

            assertThat(tripService.findTripDetailMap(empty.getId(), owner.getUserId()).markers()).isEmpty();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(3L);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
    }

    private void saveActivePhoto(Long userId, Long tripId, Long placeId) {
        String key = java.util.UUID.randomUUID().toString();
        StoredFile file = files.saveAndFlush(StoredFile.uploaded(userId, "photo.jpg", "original/" + key, "image/jpeg"));
        TripAttachment photo = TripAttachment.initial(tripId, file.getId(), "analysis/" + key, "preview/" + key);
        photo.classify(placeId, RegionOrigin.EXIF, LocalDateTime.of(2026, 9, 1, 10, 0),
                BigDecimal.ZERO, BigDecimal.ZERO, 80);
        attachments.saveAndFlush(photo);
    }

    private TripDetailPlace savePlace(Long tripId, String name, LocalDateTime deletedAt) {
        TripDetailPlace place = TripDetailPlace.fromAnalysis(
                tripId,
                1,
                name,
                new BigDecimal("33.45000000"),
                new BigDecimal("126.94000000"),
                LocalDateTime.of(2026, 9, 1, 10, 0),
                LocalDateTime.of(2026, 9, 1, 11, 0),
                null
        );
        ReflectionTestUtils.setField(place, "deletedAt", deletedAt);
        return places.save(place);
    }
}
