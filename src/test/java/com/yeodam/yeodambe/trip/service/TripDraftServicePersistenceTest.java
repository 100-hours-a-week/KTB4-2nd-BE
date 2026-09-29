package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.InvalidTripDraftRequestException;
import com.yeodam.yeodambe.common.exception.TripDraftAlreadySubmittedException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripDraftRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.TripDraftSaveRequest;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TripDraftServicePersistenceTest {
    @Autowired TripDraftService service;
    @Autowired TripDraftRepository drafts;
    @Autowired UserRepository users;
    @Autowired TripRepository trips;
    @Autowired TransactionOperations transactions;

    @Test
    void 부분_초안을_저장하고_같은_PUT은_시각을_유지한다() {
        Long userId = users.save(new User(System.nanoTime() + "@draft.invalid", "초안")).getUserId();
        var input = new TripDraftSaveRequest("제주", List.of(), null, null);
        var first = service.save(userId, input);
        var repeated = service.save(userId, input);

        assertThat(repeated.draftId()).isEqualTo(first.draftId());
        assertThat(repeated.updatedAt()).isEqualTo(first.updatedAt());
        assertThat(service.find(userId).tripName()).isEqualTo("제주");
        service.delete(userId);
        service.delete(userId);
        assertThat(drafts.findByUserId(userId)).isEmpty();
    }

    @Test
    void 잘못된_교체는_기존_초안을_변경하지_않는다() {
        Long userId = users.save(new User(System.nanoTime() + "@draft.invalid", "초안")).getUserId();
        var saved = service.save(userId, new TripDraftSaveRequest("원본", List.of("50110"), null, null));
        var invalid = List.of(
                new TripDraftSaveRequest(" ", List.of("50110"), null, null),
                new TripDraftSaveRequest("원본", List.of("99999"), null, null),
                new TripDraftSaveRequest("원본", List.of("50110", "50110"), null, null),
                new TripDraftSaveRequest("원본", List.of("50110"), LocalDate.now(), null),
                new TripDraftSaveRequest("원본", List.of("50110"), LocalDate.now().plusDays(1), LocalDate.now().plusDays(2)),
                new TripDraftSaveRequest("원본", List.of("50110"), LocalDate.now().minusDays(92), LocalDate.now())
        );
        for (var request : invalid) {
            assertThatThrownBy(() -> service.save(userId, request)).isInstanceOf(InvalidTripDraftRequestException.class);
            assertThat(service.find(userId)).isEqualTo(saved);
        }
    }

    @Test
    void 동시_첫_저장은_사용자당_한_초안으로_수렴한다() throws Exception {
        Long userId = users.save(new User(System.nanoTime() + "@draft.invalid", "초안")).getUserId();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Long>) () -> {
                start.await();
                return service.save(userId, new TripDraftSaveRequest("동시", List.of(), null, null)).draftId();
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void 제출된_초안은_수정하거나_삭제할_수_없다() {
        Long userId = users.save(new User(System.nanoTime() + "@draft.invalid", "초안")).getUserId();
        var input = new TripDraftSaveRequest("제주", List.of("50110"), null, null);
        service.save(userId, input);
        Long tripId = trips.save(new Trip(userId, "제주", LocalDate.now(), LocalDate.now())).getId();
        var draft = drafts.findByUserId(userId).orElseThrow();
        draft.attach(tripId);
        drafts.saveAndFlush(draft);

        assertThatThrownBy(() -> service.save(userId, input)).isInstanceOf(TripDraftAlreadySubmittedException.class);
        assertThatThrownBy(() -> service.delete(userId)).isInstanceOf(TripDraftAlreadySubmittedException.class);
        assertThat(service.find(userId).submittedTripId()).isEqualTo(tripId);
    }

    @Test
    void 연결_해제와_완료_삭제는_소유자와_여행_ID가_모두_맞아야_한다() {
        Long userId = users.save(new User(System.nanoTime() + "@draft.invalid", "초안")).getUserId();
        Long otherId = users.save(new User(System.nanoTime() + "@draft.invalid", "다른이")).getUserId();
        Long tripId = trips.save(new Trip(userId, "제주", LocalDate.now(), LocalDate.now())).getId();
        var draft = drafts.save(new com.yeodam.yeodambe.trip.entity.TripDraft(
                userId, "제주", "[\"50110\"]", LocalDate.now(), LocalDate.now()));
        draft.attach(tripId);
        drafts.saveAndFlush(draft);

        transactions.executeWithoutResult(status -> {
            assertThat(drafts.clearSubmittedTripId(tripId + 1, userId)).isZero();
            assertThat(drafts.deleteBySubmittedTripIdAndUserId(tripId, otherId)).isZero();
        });
        assertThat(drafts.findByUserId(userId).orElseThrow().getSubmittedTripId()).isEqualTo(tripId);
        transactions.executeWithoutResult(status -> assertThat(drafts.clearSubmittedTripId(tripId, userId)).isOne());
        assertThat(drafts.findByUserId(userId).orElseThrow().getSubmittedTripId()).isNull();
    }
}
