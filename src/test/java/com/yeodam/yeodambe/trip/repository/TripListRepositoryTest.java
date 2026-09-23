package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TripListRepositoryTest {
    @Autowired
    private TripRepository trips;
    @Autowired
    private UserRepository users;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TripRegionRepository tripRegions;

    @Test
    void 본인의_미삭제_여행을_처리상태와_무관하게_최신순으로_조회한다() {
        User owner = users.save(new User("owner-list@test.com", "목록회원"));
        User other = users.save(new User("other-list@test.com", "다른회원"));
        Trip processing = save(owner.getUserId(), "처리중", false, ProcessingStatus.PROCESSING, null);
        Trip completed = save(owner.getUserId(), "완료", false, ProcessingStatus.COMPLETED, null);
        save(owner.getUserId(), "삭제", false, ProcessingStatus.COMPLETED, LocalDateTime.now());
        save(other.getUserId(), "타인", false, ProcessingStatus.COMPLETED, null);
        flushAndClear();

        List<Trip> result = trips.findListLatest(
                owner.getUserId(), null, null, PageRequest.of(0, 8));

        assertThat(result).extracting(Trip::getId)
                .containsExactly(completed.getId(), processing.getId());
    }

    @Test
    void 최신순_커서는_동일한_생성시각에서_tripId로_다음_항목을_찾는다() {
        User owner = users.save(new User("same-time@test.com", "동시회원"));
        Trip first = save(owner.getUserId(), "첫째", false, ProcessingStatus.COMPLETED, null);
        Trip second = save(owner.getUserId(), "둘째", false, ProcessingStatus.COMPLETED, null);
        Trip third = save(owner.getUserId(), "셋째", false, ProcessingStatus.COMPLETED, null);
        entityManager.flush();
        LocalDateTime sameTime = LocalDateTime.of(2026, 9, 22, 10, 0);
        entityManager.createNativeQuery("UPDATE trips SET created_at = :createdAt")
                .setParameter("createdAt", sameTime)
                .executeUpdate();
        entityManager.clear();

        List<Trip> result = trips.findListLatest(
                owner.getUserId(), sameTime, second.getId(), PageRequest.of(0, 8));

        assertThat(result).extracting(Trip::getId).containsExactly(first.getId());
        assertThat(result).extracting(Trip::getId).doesNotContain(third.getId());
    }

    @Test
    void 즐겨찾기와_일반_그룹을_각각_오래된순으로_조회한다() {
        User owner = users.save(new User("favorite-list@test.com", "즐겨찾기회원"));
        Trip normal = save(owner.getUserId(), "일반", false, ProcessingStatus.COMPLETED, null);
        Trip favorite = save(owner.getUserId(), "즐겨찾기", true, ProcessingStatus.COMPLETED, null);
        flushAndClear();

        List<Trip> favorites = trips.findFavoriteGroupOldest(
                owner.getUserId(), true, null, null, PageRequest.of(0, 8));
        List<Trip> normals = trips.findFavoriteGroupOldest(
                owner.getUserId(), false, null, null, PageRequest.of(0, 8));

        assertThat(favorites).extracting(Trip::getId).containsExactly(favorite.getId());
        assertThat(normals).extracting(Trip::getId).containsExactly(normal.getId());
    }

    @Test
    void 오래된순_커서는_동일한_생성시각에서_큰_tripId부터_이어진다() {
        User owner = users.save(new User("oldest-time@test.com", "오래된순회원"));
        Trip first = save(owner.getUserId(), "첫째", false, ProcessingStatus.COMPLETED, null);
        Trip second = save(owner.getUserId(), "둘째", false, ProcessingStatus.COMPLETED, null);
        Trip third = save(owner.getUserId(), "셋째", false, ProcessingStatus.COMPLETED, null);
        LocalDateTime sameTime = setSameCreatedAt();

        List<Trip> result = trips.findListOldest(
                owner.getUserId(), sameTime, second.getId(), PageRequest.of(0, 8));

        assertThat(result).extracting(Trip::getId).containsExactly(third.getId());
        assertThat(result).extracting(Trip::getId).doesNotContain(first.getId());
    }

    @Test
    void 즐겨찾기_최신순_커서는_해당_그룹_안에서만_이어진다() {
        User owner = users.save(new User("favorite-cursor@test.com", "그룹회원"));
        Trip olderFavorite = save(owner.getUserId(), "이전즐찾", true, ProcessingStatus.COMPLETED, null);
        Trip cursorFavorite = save(owner.getUserId(), "기준즐찾", true, ProcessingStatus.COMPLETED, null);
        save(owner.getUserId(), "일반", false, ProcessingStatus.COMPLETED, null);
        LocalDateTime sameTime = setSameCreatedAt();

        List<Trip> result = trips.findFavoriteGroupLatest(
                owner.getUserId(), true, sameTime, cursorFavorite.getId(), PageRequest.of(0, 8));

        assertThat(result).extracting(Trip::getId).containsExactly(olderFavorite.getId());
        assertThat(result).allMatch(Trip::getFavorite);
    }

    @Test
    void 여행별_미삭제_지역명만_일괄_조회한다() {
        User owner = users.save(new User("region-list@test.com", "지역회원"));
        Trip trip = save(owner.getUserId(), "지역여행", false, ProcessingStatus.COMPLETED, null);
        TripRegion visible = region(trip, "50110", "제주", null);
        TripRegion deleted = region(trip, "26110", "부산", LocalDateTime.now());
        tripRegions.saveAll(List.of(visible, deleted));
        flushAndClear();

        List<TripRegionName> result = tripRegions.findNamesByTripIds(List.of(trip.getId()));

        assertThat(result).containsExactly(new TripRegionName(trip.getId(), "제주"));
    }

    private Trip save(
            Long userId,
            String name,
            boolean favorite,
            ProcessingStatus status,
            LocalDateTime deletedAt
    ) {
        Trip trip = new Trip(userId, name, LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "favorite", favorite);
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        ReflectionTestUtils.setField(trip, "deletedAt", deletedAt);
        return trips.save(trip);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private LocalDateTime setSameCreatedAt() {
        entityManager.flush();
        LocalDateTime sameTime = LocalDateTime.of(2026, 9, 22, 10, 0);
        entityManager.createNativeQuery("UPDATE trips SET created_at = :createdAt")
                .setParameter("createdAt", sameTime)
                .executeUpdate();
        entityManager.clear();
        return sameTime;
    }

    private TripRegion region(
            Trip trip,
            String code,
            String name,
            LocalDateTime deletedAt
    ) {
        TripRegion region = new TripRegion(
                trip,
                code,
                name,
                new BigDecimal("35.00000000"),
                new BigDecimal("127.00000000")
        );
        ReflectionTestUtils.setField(region, "deletedAt", deletedAt);
        return region;
    }
}
