package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.common.exception.AttachmentRestoreNotAllowedException;
import com.yeodam.yeodambe.common.exception.AttachmentNotFoundException;
import com.yeodam.yeodambe.common.exception.BulkRestoreFailedException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.*;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.entity.UserStats;
import com.yeodam.yeodambe.user.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Timeout(90)
class TripAttachmentRestoreMySqlTest {
    @Autowired private TripAttachmentRestoreService service;
    @Autowired private TripAttachmentDeletionService attachmentDeletion;
    @Autowired private TripDeletionService tripDeletion;
    @Autowired private TripWithdrawalService withdrawal;
    @Autowired private UserRepository users;
    @Autowired private UserStatsRepository stats;
    @MockitoSpyBean private TripRepository trips;
    @MockitoSpyBean private TripDetailPlaceRepository places;
    @MockitoSpyBean private TripRegionRepository regions;
    @Autowired private StoredFileRepository files;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionOperations transactions;
    @MockitoSpyBean private TripAttachmentRepository attachments;

    enum Deletion {
        ATTACHMENT,
        TRIP,
        WITHDRAWAL
    }

    @Test
    void 동일사진_동시복구는_한번만_성공하며_메타데이터와_통계를_보존한다() throws Exception {
        Fixture fixture = fixture();
        TripAttachment before = transactions.execute(status -> attachments.findById(fixture.first()).orElseThrow());
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Class<?>> request = () -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    restore(fixture);
                    return Void.class;
                } catch (AttachmentRestoreNotAllowedException denied) {
                    return denied.getClass();
                }
            };
            Future<Class<?>> first = executor.submit(request);
            Future<Class<?>> second = executor.submit(request);
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Void.class, AttachmentRestoreNotAllowedException.class);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        transactions.executeWithoutResult(status -> {
            TripAttachment restored = attachments.findById(fixture.first()).orElseThrow();
            assertThat(restored.getClassificationStatus()).isEqualTo(ClassificationStatus.ACTIVE);
            assertThat(restored.getIssue()).isEqualTo(AttachmentIssue.NONE);
            assertThat(restored.getTripPlaceId()).isEqualTo(fixture.place());
            assertThat(restored.getTakenAt()).isEqualTo(before.getTakenAt());
            assertThat(restored.getLatitude()).isEqualByComparingTo(before.getLatitude());
            assertThat(restored.getLongitude()).isEqualByComparingTo(before.getLongitude());
            assertThat(restored.getEvaluation()).isEqualTo(before.getEvaluation());
            assertThat(restored.getDeviceModel()).isEqualTo(before.getDeviceModel());
            assertThat(restored.getFileId()).isEqualTo(before.getFileId());
            assertThat(restored.getAnalyzeStorageKey()).isEqualTo(before.getAnalyzeStorageKey());
            assertThat(restored.getDisplayStorageKey()).isEqualTo(before.getDisplayStorageKey());
            assertThat(restored.getPreviewStorageKey()).isEqualTo(before.getPreviewStorageKey());
            UserStats usage = stats.findByUser_UserId(fixture.user()).orElseThrow();
            assertThat(usage.getAttachmentCount()).isEqualTo(2);
            assertThat(usage.getStorageUsedBytes()).isEqualTo(80);
            assertThat(places.findById(fixture.place()).orElseThrow().getThumbnailKey())
                    .isEqualTo(before.getPreviewStorageKey());
            assertThat(trips.findById(fixture.trip()).orElseThrow().getThumbnailKey())
                    .isEqualTo(before.getPreviewStorageKey());
        });
    }

    @Test
    void 다른사진_동시복구는_대기후에도_높은점수의_대표를_유지한다() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch secondRead = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        var delegate = mockingDetails(attachments).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = delegate.answer(invocation);
            secondRead.countDown();
            return result;
        }).when(attachments).findAccessibleById(fixture.last(), fixture.user());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transactions.executeWithoutResult(status -> {
                restore(fixture);
                entityManager.flush();
                firstWritten.countDown();
                await(commitFirst);
            }));
            assertThat(firstWritten.await(15, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> service.restoreOne(
                    fixture.user(), fixture.last(), new AttachmentRestoreRequest(fixture.place())
            ));
            assertThat(secondRead.await(10, TimeUnit.SECONDS)).isTrue();
            commitFirst.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            commitFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        transactions.executeWithoutResult(status -> {
            TripAttachment higher = attachments.findById(fixture.first()).orElseThrow();
            TripAttachment lower = attachments.findById(fixture.last()).orElseThrow();
            assertThat(higher.getClassificationStatus()).isEqualTo(ClassificationStatus.ACTIVE);
            assertThat(lower.getClassificationStatus()).isEqualTo(ClassificationStatus.ACTIVE);
            assertThat(places.findById(fixture.place()).orElseThrow().getThumbnailKey())
                    .isEqualTo(higher.getPreviewStorageKey());
            assertThat(trips.findById(fixture.trip()).orElseThrow().getThumbnailKey())
                    .isEqualTo(higher.getPreviewStorageKey());
        });
    }

    @ParameterizedTest
    @EnumSource(Deletion.class)
    void 삭제커밋선행_겹친복구는_삭제를_되살리지않는다(Deletion deletion) throws Exception {
        Fixture fixture = fixture();
        overlap(fixture, () -> delete(fixture, deletion), () -> {
            assertThatThrownBy(() -> restore(fixture)).isInstanceOf(AttachmentNotFoundException.class);
        });
        assertDeleted(fixture, deletion);
    }

    @ParameterizedTest
    @EnumSource(Deletion.class)
    void 복구커밋선행_겹친삭제는_삭제를_유지한다(Deletion deletion) throws Exception {
        Fixture fixture = fixture();
        overlap(fixture, () -> restore(fixture), () -> delete(fixture, deletion));
        assertDeleted(fixture, deletion);
    }

    private void overlap(Fixture fixture, Runnable first, Runnable second) throws Exception {
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        AtomicReference<Thread> secondThread = new AtomicReference<>();
        var attachmentDelegate = mockingDetails(attachments).getMockCreationSettings().getDefaultAnswer();
        var tripDelegate = mockingDetails(trips).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = attachmentDelegate.answer(invocation);
            if (Thread.currentThread() == secondThread.get()) {
                secondStarted.countDown();
            }
            return result;
        }).when(attachments).findAccessibleById(fixture.first(), fixture.user());
        doAnswer(invocation -> {
            if (Thread.currentThread() == secondThread.get()) {
                secondStarted.countDown();
            }
            return tripDelegate.answer(invocation);
        }).when(trips).findOwnedActiveForUpdate(fixture.trip(), fixture.user());
        doAnswer(invocation -> {
            if (Thread.currentThread() == secondThread.get()) {
                secondStarted.countDown();
            }
            return tripDelegate.answer(invocation);
        }).when(trips).findAllOwnedActiveForUpdate(fixture.user());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstFuture = executor.submit(() -> transactions.executeWithoutResult(status -> {
                first.run();
                entityManager.flush();
                firstWritten.countDown();
                await(commitFirst);
            }));
            assertThat(firstWritten.await(15, TimeUnit.SECONDS)).isTrue();
            Future<?> secondFuture = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                second.run();
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            commitFirst.countDown();
            firstFuture.get(30, TimeUnit.SECONDS);
            secondFuture.get(30, TimeUnit.SECONDS);
        } finally {
            commitFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 탈퇴의_장소삭제와_복구의_여행잠금이_겹쳐도_교착하지않는다() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch restoreLockedTrip = new CountDownLatch(1);
        CountDownLatch withdrawalEnteredTripLock = new CountDownLatch(1);
        CountDownLatch continueRestore = new CountDownLatch(1);
        var tripDelegate = mockingDetails(trips).getMockCreationSettings().getDefaultAnswer();
        var regionDelegate = mockingDetails(regions).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object result = tripDelegate.answer(invocation);
            restoreLockedTrip.countDown();
            await(continueRestore);
            return result;
        }).when(trips).findOwnedActiveForUpdate(fixture.trip(), fixture.user());
        doAnswer(invocation -> {
            withdrawalEnteredTripLock.countDown();
            return tripDelegate.answer(invocation);
        }).when(trips).findAllOwnedActiveForUpdate(fixture.user());
        doAnswer(invocation -> {
            assertThat(withdrawalEnteredTripLock.getCount()).isZero();
            return regionDelegate.answer(invocation);
        }).when(regions).softDeleteByUserId(eq(fixture.user()), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> restoreFuture = executor.submit(() -> restore(fixture));
            if (!restoreLockedTrip.await(10, TimeUnit.SECONDS)) {
                restoreFuture.get(1, TimeUnit.SECONDS);
                throw new IllegalStateException("Restore did not reach trip lock seam");
            }
            Future<?> withdrawalFuture = executor.submit(() -> delete(fixture, Deletion.WITHDRAWAL));
            assertThat(withdrawalEnteredTripLock.await(10, TimeUnit.SECONDS)).isTrue();
            continueRestore.countDown();
            restoreFuture.get(30, TimeUnit.SECONDS);
            withdrawalFuture.get(30, TimeUnit.SECONDS);
        } finally {
            continueRestore.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        var writes = inOrder(trips, regions, places, attachments);
        writes.verify(trips).findAllOwnedActiveForUpdate(fixture.user());
        writes.verify(regions).softDeleteByUserId(eq(fixture.user()), any());
        writes.verify(places).softDeleteByUserId(eq(fixture.user()), any());
        writes.verify(attachments).softDeleteByUserId(eq(fixture.user()), any());
        assertDeleted(fixture, Deletion.WITHDRAWAL);
    }

    @Test
    void 마지막조건부업데이트_영건은_앞선첨부와_대표를_롤백한다() {
        Fixture fixture = fixture();
        doReturn(0).when(attachments).restoreIfUnclassified(eq(fixture.last()), eq(fixture.trip()),
                eq(fixture.user()), eq(fixture.place()), eq(fixture.place()), any());
        assertThatThrownBy(() -> service.restoreBulk(fixture.user(), bulk(fixture)))
                .isInstanceOf(BulkRestoreFailedException.class);
        assertRolledBack(fixture);
    }

    @Test
    void 여행대표갱신실패는_이미쓴첨부와_폴더대표까지_롤백한다() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            entityManager.flush();
            throw new IllegalStateException("thumbnail failure");
        }).when(attachments).findAllActiveByTripId(fixture.trip(), ClassificationStatus.ACTIVE);
        assertThatThrownBy(() -> service.restoreBulk(fixture.user(), bulk(fixture)))
                .isInstanceOf(IllegalStateException.class);
        assertRolledBack(fixture);
    }

    private void assertRolledBack(Fixture fixture) {
        transactions.executeWithoutResult(status -> {
            for (Long id : List.of(fixture.first(), fixture.last())) {
                TripAttachment attachment = attachments.findById(id).orElseThrow();
                assertThat(attachment.getClassificationStatus()).isEqualTo(ClassificationStatus.UNCLASSIFIED);
                assertThat(attachment.getIssue()).isEqualTo(AttachmentIssue.BLURRY);
            }
            assertThat(places.findById(fixture.place()).orElseThrow().getThumbnailKey()).isEqualTo("old");
            assertThat(trips.findById(fixture.trip()).orElseThrow().getThumbnailKey()).isEqualTo("old");
        });
    }

    private void assertDeleted(Fixture fixture, Deletion deletion) {
        transactions.executeWithoutResult(status -> {
            TripAttachment photo = attachments.findById(fixture.first()).orElseThrow();
            assertThat(photo.getDeletedAt()).isNotNull();
            assertThat(files.findById(photo.getFileId()).orElseThrow().getDeletedAt()).isNotNull();
            if (deletion != Deletion.ATTACHMENT) {
                assertThat(trips.findById(fixture.trip()).orElseThrow().getDeletedAt()).isNotNull();
                assertThat(places.findById(fixture.place()).orElseThrow().getDeletedAt()).isNotNull();
            }
        });
    }

    private void restore(Fixture fixture) {
        service.restoreOne(fixture.user(), fixture.first(), new AttachmentRestoreRequest(fixture.place()));
    }

    private void delete(Fixture fixture, Deletion deletion) {
        switch (deletion) {
            case ATTACHMENT -> attachmentDeletion.deleteOne(fixture.user(), fixture.first());
            case TRIP -> tripDeletion.delete(fixture.trip(), fixture.user());
            case WITHDRAWAL -> withdrawal.withdrawAll(fixture.user(), LocalDateTime.now());
        }
    }

    private BulkAttachmentRestoreRequest bulk(Fixture fixture) {
        return new BulkAttachmentRestoreRequest(List.of(
                new BulkAttachmentRestoreRequest.Item(fixture.first(), fixture.place()),
                new BulkAttachmentRestoreRequest.Item(fixture.last(), fixture.place())
        ));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Controlled transaction timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private Fixture fixture() {
        return transactions.execute(status -> {
            User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@test.com", "복구"));
            UserStats usage = new UserStats(owner);
            usage.replaceActiveTripUsage(1, 2, 80);
            stats.saveAndFlush(usage);
            Trip trip = completedTrip(owner, "복구");
            TripDetailPlace place = savePlace(trip.getId(), "old");
            TripAttachment first = unclassified(owner, trip, place, 95);
            TripAttachment last = unclassified(owner, trip, place, 80);
            return new Fixture(owner.getUserId(), trip.getId(), place.getId(), first.getId(), last.getId());
        });
    }

    private TripAttachment unclassified(User owner, Trip trip, TripDetailPlace place, int evaluation) {
        TripAttachment attachment = saveAttachment(owner.getUserId(), trip.getId(), place.getId(),
                UUID.randomUUID().toString(), evaluation);
        attachment.unclassify(place.getId(), AttachmentIssue.BLURRY, RegionOrigin.EXIF, attachment.getTakenAt(),
                attachment.getLatitude(), attachment.getLongitude(), evaluation);
        attachments.saveAndFlush(attachment);
        return attachment;
    }

    private record Fixture(
            Long user,
            Long trip,
            Long place,
            Long first,
            Long last
    ) {
    }

    private Trip completedTrip(User owner, String name) {
        Trip trip = Trip.localMock(owner.getUserId(), name, LocalDate.now(), LocalDate.now(), "old");
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        return trips.saveAndFlush(trip);
    }

    private TripDetailPlace savePlace(Long tripId, String thumbnailKey) {
        return places.saveAndFlush(TripDetailPlace.fromAnalysis(
                tripId,
                1,
                "제주시",
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
        StoredFile file = StoredFile.uploaded(
                userId, key + ".jpg", "original/" + key, "image/jpeg");
        file.storageSize(10L);
        files.saveAndFlush(file);
        TripAttachment attachment = TripAttachment.initial(
                tripId, file.getId(), "analyze/" + key, "preview-" + key,
                "display/" + key);
        attachment.storageSizes(10L, 10L, 10L);
        ReflectionTestUtils.setField(attachment, "deviceModel", "test-camera");
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
