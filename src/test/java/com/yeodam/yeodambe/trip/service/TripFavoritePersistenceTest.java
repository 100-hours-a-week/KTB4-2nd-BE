package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(TripService.class)
class TripFavoritePersistenceTest {
    @Autowired
    private TripService tripService;
    @Autowired
    private TripRepository trips;
    @Autowired
    private UserRepository users;
    @MockitoBean
    private RegionCatalog regionCatalog;
    @MockitoBean
    private TripAttachmentStorageClient tripAttachmentStorageClient;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 즐겨찾기_등록을_반복해도_true가_DB에_저장된다() {
        User user = users.saveAndFlush(new User("favorite-persistence@example.com", "사용자"));
        Trip trip = new Trip(user.getUserId(), "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        Trip saved = trips.saveAndFlush(trip);

        tripService.registerFavorite(saved.getId(), user.getUserId());
        tripService.registerFavorite(saved.getId(), user.getUserId());

        assertThat(trips.findById(saved.getId()).orElseThrow().getFavorite()).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 즐겨찾기_삭제를_반복해도_false가_DB에_저장된다() {
        User user = users.saveAndFlush(new User("favorite-removal@example.com", "사용자"));
        Trip trip = new Trip(user.getUserId(), "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        ReflectionTestUtils.setField(trip, "favorite", true);
        Trip saved = trips.saveAndFlush(trip);

        tripService.removeFavorite(saved.getId(), user.getUserId());
        tripService.removeFavorite(saved.getId(), user.getUserId());

        assertThat(trips.findById(saved.getId()).orElseThrow().getFavorite()).isFalse();
    }
}
