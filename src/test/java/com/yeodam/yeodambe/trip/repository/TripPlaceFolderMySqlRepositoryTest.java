package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
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

    @Test
    void 한글_장소명과_동일_이름_ID순으로_다음_페이지를_조회한다() {
        User owner = users.save(new User("mysql-folder@test.com", "폴더회원"));
        Trip trip = trips.save(new Trip(owner.getUserId(), "한글여행", LocalDate.now(), LocalDate.now()));
        Trip other = trips.save(new Trip(owner.getUserId(), "다른여행", LocalDate.now(), LocalDate.now()));
        TripDetailPlace ga1 = savePlace(trip.getId(), "가", null);
        TripDetailPlace ga2 = savePlace(trip.getId(), "가", null);
        TripDetailPlace na = savePlace(trip.getId(), "나", null);
        TripDetailPlace da = savePlace(trip.getId(), "다", null);
        savePlace(trip.getId(), "라", LocalDateTime.now());
        savePlace(other.getId(), "가", null);

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
}
