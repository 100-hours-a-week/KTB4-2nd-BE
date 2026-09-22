package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TripProcessingCancellationRepositoryTest {
    @Autowired private TripRepository trips;
    @Autowired private TripRegionRepository regions;
    @Autowired private TripDetailPlaceRepository places;
    @Autowired private TripAttachmentRepository attachments;
    @Autowired private StoredFileRepository files;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void 처리중_여행과_연관_행을_같은_시각에_소프트_삭제한다() {
        User user = users.saveAndFlush(new User("user@example.com", "사용자"));
        Trip trip = trips.saveAndFlush(new Trip(
                user.getUserId(), "여행", LocalDate.now(), LocalDate.now()));
        TripRegion region = regions.save(new TripRegion(
                trip, "50110", "제주특별자치도 제주시",
                new BigDecimal("33.5"), new BigDecimal("126.5")));
        TripDetailPlace place = places.save(TripDetailPlace.fromAnalysis(
                trip.getId(), 1, new BigDecimal("33.5"), new BigDecimal("126.5"),
                LocalDateTime.now(), LocalDateTime.now(), "preview"));
        StoredFile file = files.save(StoredFile.uploaded(
                user.getUserId(), "photo.jpg", "original", "image/jpeg"));
        TripAttachment attachment = attachments.save(TripAttachment.initial(
                trip.getId(), file.getId(), "analyze", "preview"));
        entityManager.flush();
        entityManager.clear();
        LocalDateTime canceledAt = LocalDateTime.of(2026, 9, 21, 12, 0);

        assertThat(trips.cancelProcessing(trip.getId(), user.getUserId(),
                ProcessingStatus.PROCESSING, ProcessingStatus.CANCELED, canceledAt)).isOne();
        assertThat(regions.softDeleteByTripId(trip.getId(), canceledAt)).isOne();
        assertThat(places.softDeleteByTripId(trip.getId(), canceledAt)).isOne();
        assertThat(attachments.softDeleteByTripId(trip.getId(), canceledAt)).isOne();
        assertThat(files.softDeleteByIds(List.of(file.getId()), canceledAt)).isOne();
        entityManager.clear();

        assertThat(trips.findById(trip.getId()).orElseThrow().getProcessingStatus())
                .isEqualTo(ProcessingStatus.CANCELED);
        assertThat(List.of(
                trips.findById(trip.getId()).orElseThrow().getDeletedAt(),
                regions.findById(region.getId()).orElseThrow().getDeletedAt(),
                places.findById(place.getId()).orElseThrow().getDeletedAt(),
                attachments.findById(attachment.getId()).orElseThrow().getDeletedAt(),
                files.findById(file.getId()).orElseThrow().getDeletedAt()
        )).containsOnly(canceledAt);
    }

    @Test
    void 완료된_여행은_취소_조건부_갱신에서_제외한다() {
        User user = users.saveAndFlush(new User("completed@example.com", "완료자"));
        Trip trip = trips.saveAndFlush(new Trip(
                user.getUserId(), "완료 여행", LocalDate.now(), LocalDate.now()));
        trips.finishInitialUpload(
                trip.getId(), user.getUserId(), ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED);

        assertThat(trips.cancelProcessing(trip.getId(), user.getUserId(),
                ProcessingStatus.PROCESSING, ProcessingStatus.CANCELED, LocalDateTime.now())).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 취소_트랜잭션이_먼저_상태를_바꾸면_첨부_저장용_잠금_조회가_커밋을_기다린다() throws Exception {
        User user = users.saveAndFlush(new User("race@example.com", "경합자"));
        Trip trip = trips.saveAndFlush(new Trip(
                user.getUserId(), "경합 여행", LocalDate.now(), LocalDate.now()));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch canceled = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancel = executor.submit(() -> transaction.executeWithoutResult(status -> {
                trips.cancelProcessing(trip.getId(), user.getUserId(),
                        ProcessingStatus.PROCESSING, ProcessingStatus.CANCELED, LocalDateTime.now());
                canceled.countDown();
                await(allowCommit);
            }));
            assertThat(canceled.await(1, TimeUnit.SECONDS)).isTrue();

            var upload = executor.submit(() -> transaction.execute(status ->
                    trips.findProcessableForUpdate(
                            trip.getId(), user.getUserId(), ProcessingStatus.PROCESSING)));

            assertThrows(TimeoutException.class, () -> upload.get(200, TimeUnit.MILLISECONDS));
            allowCommit.countDown();
            cancel.get(1, TimeUnit.SECONDS);
            assertThat(upload.get(1, TimeUnit.SECONDS)).isEmpty();
        } finally {
            allowCommit.countDown();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
