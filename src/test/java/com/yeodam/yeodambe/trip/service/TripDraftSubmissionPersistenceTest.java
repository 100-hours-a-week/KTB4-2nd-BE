package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripDraftNotFoundException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.TripDraftSaveRequest;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripDraftSubmissionPersistenceTest {
    @Autowired TripDraftService drafts;
    @Autowired TripRepository trips;
    @Autowired UserRepository users;

    @Test
    void 제출을_반복해도_여행은_하나만_생긴다() {
        Long userId = users.save(new User(System.nanoTime() + "@submit.invalid", "제출")).getUserId();
        Long draftId = drafts.save(userId, new TripDraftSaveRequest("제주", List.of("50110"),
                LocalDate.now().minusDays(2), LocalDate.now())).draftId();
        var first = drafts.submit(userId, draftId);
        var replay = drafts.submit(userId, draftId);

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.trip().tripId()).isEqualTo(first.trip().tripId());
        assertThat(drafts.find(userId).submittedTripId()).isEqualTo(first.trip().tripId());
    }

    @Test
    void 미완성_초안과_타인_초안은_여행을_만들지_않는다() {
        Long owner = users.save(new User(System.nanoTime() + "@submit.invalid", "제출")).getUserId();
        Long other = users.save(new User(System.nanoTime() + "@submit.invalid", "다른이")).getUserId();
        Long draftId = drafts.save(owner, new TripDraftSaveRequest("제주", List.of(), null, null)).draftId();

        assertThatThrownBy(() -> drafts.submit(owner, draftId)).isInstanceOf(InvalidTripRequestException.class);
        assertThatThrownBy(() -> drafts.submit(other, draftId)).isInstanceOf(TripDraftNotFoundException.class);
        assertThat(trips.countByUserIdAndProcessingStatusAndDeletedAtIsNull(owner,
                com.yeodam.yeodambe.trip.entity.ProcessingStatus.PROCESSING)).isZero();
    }

    @Test
    void 동일한_여행명이_이미_있으면_초안_연결은_남지_않는다() {
        Long userId = users.save(new User(System.nanoTime() + "@submit.invalid", "제출")).getUserId();
        LocalDate today = LocalDate.now();
        trips.save(new Trip(userId, "제주", today, today));
        Long draftId = drafts.save(userId, new TripDraftSaveRequest("제주", List.of("50110"), today, today)).draftId();

        assertThatThrownBy(() -> drafts.submit(userId, draftId)).isInstanceOf(TripNameDuplicatedException.class);
        assertThat(drafts.find(userId).submittedTripId()).isNull();
    }
}
