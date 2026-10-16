package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.InitialAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.AttachmentCleanupTarget;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TripObjectCleanupServiceTest {
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final StoredFileRepository files = mock(StoredFileRepository.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final InitialAttachmentUploadTransactionService uploadTransactions = mock(InitialAttachmentUploadTransactionService.class);
    private final InitialAttachmentUploadItemRepository uploadItems =
            mock(InitialAttachmentUploadItemRepository.class);
    private final AttachmentCleanupScheduleStore cleanupSchedule = mock(AttachmentCleanupScheduleStore.class);
    private final AttachmentCleanupScheduleStore.Claim claim = new AttachmentCleanupScheduleStore.Claim(30L, "token");
    private final TripObjectCleanupService service = new TripObjectCleanupService(
            attachments, files, trips, places, storage,
                uploadItems,
                uploadTransactions,
                mock(com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository.class),
                mock(AdditionalAttachmentUploadTransactionService.class), cleanupSchedule);
    private TripAttachment attachment;

    @BeforeEach
    void setUp() {
        when(uploadTransactions.databaseTime()).thenReturn(LocalDateTime.now());
        StoredFile file = StoredFile.uploaded(1L, "photo.jpg", "original", "image/jpeg");
        attachment = TripAttachment.initial(7L, 20L, "analyze", "preview", "display");
        attachment.softDelete(LocalDateTime.now().minusDays(31));
        ReflectionTestUtils.setField(attachment, "id", 30L);
        ReflectionTestUtils.setField(attachment, "file", file);
        when(attachments.findExpiredPendingCleanup(any(LocalDateTime.class), any()))
                .thenReturn(List.of(attachment));
        when(attachments.findAllForObjectCleanupByIds(List.of(30L))).thenReturn(List.of(attachment));
    }

    @Test
    void 복구_대상이_없으면_예약을_등록하거나_객체를_삭제하지_않는다() {
        when(attachments.findCleanupScheduleTargets(0L, PageRequest.of(0, 100)))
                .thenReturn(List.of());

        service.recoverSchedules();

        verifyNoInteractions(cleanupSchedule, storage);
    }

    @Test
    void 복구는_백개씩_마지막_ID_이후를_읽고_삭제시각에_삼십일을_더해_예약한다() {
        LocalDateTime deletedAt = LocalDateTime.of(2026, 10, 1, 12, 0);
        var first = java.util.stream.LongStream.rangeClosed(1, 100)
                .mapToObj(id -> new AttachmentCleanupTarget(id, deletedAt))
                .toList();
        when(attachments.findCleanupScheduleTargets(0L, PageRequest.of(0, 100)))
                .thenReturn(first);
        when(attachments.findCleanupScheduleTargets(100L, PageRequest.of(0, 100)))
                .thenReturn(List.of(new AttachmentCleanupTarget(101L, deletedAt.plusDays(1))));

        service.recoverSchedules();

        for (var target : first) {
            verify(cleanupSchedule).scheduleIfAbsent(target.attachmentId(),
                    deletedAt.plusDays(30).atZone(ZoneId.systemDefault()).toInstant());
        }
        verify(cleanupSchedule).scheduleIfAbsent(101L,
                deletedAt.plusDays(31).atZone(ZoneId.systemDefault()).toInstant());
        verify(attachments, times(2)).findCleanupScheduleTargets(anyLong(), any());
        verifyNoInteractions(storage);
    }

    @Test
    void 복구가_정확히_백개면_다음_조회가_빈_목록인지_확인하고_종료한다() {
        LocalDateTime deletedAt = LocalDateTime.now();
        var targets = java.util.stream.LongStream.rangeClosed(1, 100)
                .mapToObj(id -> new AttachmentCleanupTarget(id, deletedAt)).toList();
        when(attachments.findCleanupScheduleTargets(0L, PageRequest.of(0, 100)))
                .thenReturn(targets);
        when(attachments.findCleanupScheduleTargets(100L, PageRequest.of(0, 100)))
                .thenReturn(List.of());

        service.recoverSchedules();

        verify(attachments, times(2)).findCleanupScheduleTargets(anyLong(), any());
        verify(cleanupSchedule, times(100)).scheduleIfAbsent(anyLong(), any());
    }

    @Test
    void 복구_중_Redis_장애는_호출자에게_전달하고_삭제를_수행하지_않는다() {
        LocalDateTime deletedAt = LocalDateTime.now();
        when(attachments.findCleanupScheduleTargets(0L, PageRequest.of(0, 100)))
                .thenReturn(List.of(new AttachmentCleanupTarget(1L, deletedAt)));
        var failure = new org.springframework.data.redis.RedisConnectionFailureException("unavailable");
        when(cleanupSchedule.scheduleIfAbsent(anyLong(), any())).thenThrow(failure);

        assertThatThrownBy(service::recoverSchedules).isSameAs(failure);

        verifyNoInteractions(storage);
    }

    @Test
    void 삼십일이_지난_참조되지_않은_네_객체를_삭제하고_첨부를_DELETED로_표시한다() {
        service.retryPending();

        verify(storage).delete("original");
        verify(storage).delete("analyze");
        verify(storage).delete("preview");
        verify(storage).delete("display");
        assertThat(attachment.getClassificationStatus()).isEqualTo(ClassificationStatus.DELETED);
    }

    @ParameterizedTest
    @EnumSource(ActiveReference.class)
    void 모든_활성_참조_유형이_객체_삭제를_막는다(ActiveReference reference) {
        switch (reference) {
            case FILE -> when(files.existsByObjectKeyAndDeletedAtIsNull("original")).thenReturn(true);
            case ANALYZE -> when(attachments.existsByAnalyzeStorageKeyAndDeletedAtIsNull("original"))
                    .thenReturn(true);
            case PREVIEW -> when(attachments.existsByPreviewStorageKeyAndDeletedAtIsNull("original"))
                    .thenReturn(true);
            case DISPLAY -> when(attachments.existsByDisplayStorageKeyAndDeletedAtIsNull("original"))
                    .thenReturn(true);
            case TRIP_THUMBNAIL -> when(trips.existsByThumbnailKeyAndDeletedAtIsNull("original"))
                    .thenReturn(true);
            case PLACE_THUMBNAIL -> when(places.existsByThumbnailKeyAndDeletedAtIsNull("original"))
                    .thenReturn(true);
        }

        service.retryPending();

        verify(storage, never()).delete("original");
        verify(storage).delete("analyze");
        verify(storage).delete("preview");
        verify(storage).delete("display");
        assertThat(attachment.getClassificationStatus()).isNotEqualTo(ClassificationStatus.DELETED);
    }

    @Test
    void 하나라도_삭제에_실패하면_DELETED로_표시하지_않아_재시도한다() {
        doThrow(new IllegalStateException("S3"))
                .when(storage).delete("display");

        service.retryPending();

        assertThat(attachment.getClassificationStatus()).isNotEqualTo(ClassificationStatus.DELETED);
    }

    @Test
    void 표시본이_NULL이면_기존_세_객체만_정리한다() {
        StoredFile file = StoredFile.uploaded(1L, "legacy.jpg", "legacy-original", "image/jpeg");
        TripAttachment legacy = TripAttachment.initial(
                7L, 21L, "legacy-analyze", "legacy-preview");
        legacy.softDelete(LocalDateTime.now().minusDays(31));
        ReflectionTestUtils.setField(legacy, "file", file);
        when(attachments.findExpiredPendingCleanup(any(LocalDateTime.class), any()))
                .thenReturn(List.of(legacy));

        service.retryPending();

        verify(storage).delete("legacy-original");
        verify(storage).delete("legacy-analyze");
        verify(storage).delete("legacy-preview");
        verify(storage, never()).delete(null);
        verify(attachments, never()).existsByDisplayStorageKeyAndDeletedAtIsNull(null);
    }

    @Test
    void 정리_기록은_삼십일_전_보존하고_경계부터_삭제한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 12, 0);
        when(uploadTransactions.databaseTime()).thenReturn(now);
        when(attachments.findExpiredPendingCleanup(any(), any())).thenReturn(List.of());
        var item = mock(InitialAttachmentUploadItem.class);
        when(item.getId()).thenReturn(41L);
        when(uploadItems.findPendingCleanup(any(), any())).thenReturn(List.of(item));
        when(uploadTransactions.cleanupObjects(item)).thenReturn(List.of(
                new InitialAttachmentUploadTransactionService.CleanupObject("recent", now.minusDays(30).plusSeconds(1), "CANCELED", false),
                new InitialAttachmentUploadTransactionService.CleanupObject("expired", now.minusDays(30), "CONVERSION_FAILED", false),
                new InitialAttachmentUploadTransactionService.CleanupObject("active", now.minusDays(31), "FAILED", false)));
        when(files.existsByObjectKeyAndDeletedAtIsNull("active")).thenReturn(true);

        service.retryPending();

        verify(storage, never()).delete("recent");
        verify(storage, never()).delete("active");
        verify(storage).delete("expired");
        verify(uploadTransactions).recordObjectDeleted(41L, "expired");
        verify(uploadTransactions, never()).recordObjectDeleted(41L, "active");
    }

    @Test
    void 첨부_삭제_시각보다_DB_정리_기록의_삼십일_보존을_우선한다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 12, 0);
        when(uploadTransactions.databaseTime()).thenReturn(now);
        var item = mock(InitialAttachmentUploadItem.class);
        when(uploadItems.findByTripAttachmentId(30L)).thenReturn(Optional.of(item));
        when(uploadTransactions.cleanupObjects(item)).thenReturn(List.of(
                new InitialAttachmentUploadTransactionService.CleanupObject(
                        "original", now.minusDays(30).plusSeconds(1), "FAILED", false)));

        service.retryPending();

        verifyNoInteractions(storage);
        assertThat(attachment.getClassificationStatus()).isNotEqualTo(ClassificationStatus.DELETED);
    }

    @Test
    void 선점한_첨부가_없으면_S3_호출_없이_선점만_정리한다() {
        when(attachments.findAllForObjectCleanupByIds(List.of(30L))).thenReturn(List.of());

        service.processClaim(claim);

        verify(cleanupSchedule).complete(claim);
        verifyNoInteractions(storage);
    }

    @Test
    void 미삭제_첨부는_S3_호출_없이_선점만_정리한다() {
        ReflectionTestUtils.setField(attachment, "deletedAt", null);

        service.processClaim(claim);

        verify(cleanupSchedule).complete(claim);
        verifyNoInteractions(storage);
    }

    @Test
    void 이미_정리된_첨부는_S3를_다시_삭제하지_않는다() {
        attachment.markObjectCleanupCompleted();

        service.processClaim(claim);

        verify(cleanupSchedule).complete(claim);
        verifyNoInteractions(storage);
    }

    @Test
    void 보존기간이_남으면_예약을_먼저_등록한_뒤_선점을_정리한다() {
        LocalDateTime deletedAt = LocalDateTime.now().minusDays(10);
        attachment.softDelete(deletedAt);

        service.processClaim(claim);

        var ordered = inOrder(cleanupSchedule);
        ordered.verify(cleanupSchedule).schedule(30L,
                deletedAt.plusDays(30).atZone(ZoneId.systemDefault()).toInstant());
        ordered.verify(cleanupSchedule).complete(claim);
        verifyNoInteractions(storage);
    }

    @Test
    void 미래_예약_등록에_실패하면_선점을_정리하지_않는다() {
        attachment.softDelete(LocalDateTime.now().minusDays(10));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Redis unavailable"))
                .when(cleanupSchedule).schedule(eq(30L), any());

        assertThatThrownBy(() -> service.processClaim(claim))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        verify(cleanupSchedule, never()).complete(any());
        verifyNoInteractions(storage);
    }

    @Test
    void S3_삭제와_DB_저장을_끝낸_뒤_선점을_정리한다() {
        service.processClaim(claim);

        var ordered = inOrder(storage, attachments, cleanupSchedule);
        ordered.verify(storage).delete("original");
        ordered.verify(storage).delete("analyze");
        ordered.verify(storage).delete("preview");
        ordered.verify(storage).delete("display");
        ordered.verify(attachments).save(attachment);
        ordered.verify(cleanupSchedule).complete(claim);
        assertThat(attachment.getClassificationStatus()).isEqualTo(ClassificationStatus.DELETED);
    }

    @Test
    void 선점_작업의_S3_삭제가_실패하면_완료_처리하지_않는다() {
        doThrow(new IllegalStateException("S3 unavailable")).when(storage).delete("display");

        service.processClaim(claim);

        verify(cleanupSchedule, never()).complete(any());
        verify(attachments, never()).save(any());
    }

    @Test
    void 선점_작업의_DB_저장이_실패하면_완료_처리하지_않는다() {
        doThrow(new IllegalStateException("DB unavailable")).when(attachments).save(attachment);

        assertThatThrownBy(() -> service.processClaim(claim)).hasMessage("DB unavailable");

        verify(cleanupSchedule, never()).complete(any());
    }

    @Test
    void 업로드_객체의_추가_보존기간이_남으면_선점을_완료하지_않는다() {
        LocalDateTime now = LocalDateTime.now();
        when(uploadTransactions.databaseTime()).thenReturn(now);
        var item = mock(InitialAttachmentUploadItem.class);
        when(uploadItems.findByTripAttachmentId(30L)).thenReturn(Optional.of(item));
        when(uploadTransactions.cleanupObjects(item)).thenReturn(List.of(
                new InitialAttachmentUploadTransactionService.CleanupObject(
                        "original", now.minusDays(30).plusSeconds(1), "FAILED", false)));

        service.processClaim(claim);

        verifyNoInteractions(storage);
        verify(cleanupSchedule, never()).complete(any());
    }

    @Test
    void 예약이_없으면_DB와_S3를_호출하지_않는다() {
        when(cleanupSchedule.claim(any())).thenReturn(Optional.empty());

        service.processScheduled();

        verify(cleanupSchedule).claim(Duration.ofMinutes(5));
        verifyNoInteractions(attachments, files, trips, places, storage, uploadTransactions, uploadItems);
    }

    @Test
    void 한_사진의_DB_처리가_실패해도_다음_선점을_처리한다() {
        var next = new AttachmentCleanupScheduleStore.Claim(31L, "next-token");
        when(cleanupSchedule.claim(any())).thenReturn(Optional.of(claim), Optional.of(next), Optional.empty());
        when(attachments.findAllForObjectCleanupByIds(List.of(30L)))
                .thenThrow(new IllegalStateException("DB unavailable"));

        service.processScheduled();

        verify(cleanupSchedule, never()).complete(claim);
        verify(cleanupSchedule).complete(next);
        verify(cleanupSchedule, times(3)).claim(Duration.ofMinutes(5));
    }

    @Test
    void 작업이_계속_있어도_한번에_백개까지만_처리한다() {
        when(cleanupSchedule.claim(any())).thenReturn(Optional.of(claim));
        when(attachments.findAllForObjectCleanupByIds(List.of(30L))).thenReturn(List.of());

        service.processScheduled();

        verify(cleanupSchedule, times(100)).claim(Duration.ofMinutes(5));
        verify(attachments, times(100)).findAllForObjectCleanupByIds(List.of(30L));
        verify(cleanupSchedule, times(100)).complete(claim);
    }

    @Test
    void Redis_선점_장애를_빈_큐로_숨기지_않는다() {
        when(cleanupSchedule.claim(any())).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("Redis unavailable"));

        assertThatThrownBy(service::processScheduled)
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        verifyNoInteractions(attachments, storage);
    }

    private enum ActiveReference {
        FILE,
        ANALYZE,
        PREVIEW,
        DISPLAY,
        TRIP_THUMBNAIL,
        PLACE_THUMBNAIL
    }
}
