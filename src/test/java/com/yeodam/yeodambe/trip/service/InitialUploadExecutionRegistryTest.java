package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripInitialAttachmentUploadNotAllowedException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InitialUploadExecutionRegistryTest {
    private final InitialUploadExecutionRegistry registry = new InitialUploadExecutionRegistry();

    @Test
    void 기존_단일_실행_예약도_분석_상태로_전환할_수_있다() {
        String executionId = registry.reserve(7L);

        assertTrue(registry.markAnalysisStarted(7L, executionId));
        assertTrue(registry.isAnalysisStarted(7L));
    }

    @Test
    void 첫_배치를_STORING으로_예약한다() {
        var reservation = registry.reserveBatch(7L, 1, 2);

        assertTrue(reservation.firstBatch());
        assertTrue(registry.isCurrent(7L, reservation.executionId()));
        assertEquals(InitialUploadExecutionRegistry.State.STORING, registry.snapshot(7L).state());
        assertEquals(1, registry.snapshot(7L).activeBatchNo());
    }

    @Test
    void 저장을_마친_다음_순번의_배치만_예약한다() {
        var first = registry.reserveBatch(7L, 1, 2);
        registry.completeBatch(7L, first.executionId(), 1, 3, List.of(photo("1")), false);

        assertEquals(InitialUploadExecutionRegistry.State.UPLOADING, registry.snapshot(7L).state());
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> registry.reserveBatch(7L, 1, 2));
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> registry.reserveBatch(7L, 3, 2));

        var second = registry.reserveBatch(7L, 2, 2);
        assertEquals(first.executionId(), second.executionId());
        assertFalse(second.firstBatch());
    }

    @Test
    void 캐시가_없거나_전체_수가_바뀌거나_저장_중이면_후속_배치를_거부한다() {
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> registry.reserveBatch(7L, 2, 2));

        var first = registry.reserveBatch(7L, 1, 2);
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> registry.reserveBatch(7L, 2, 2));

        registry.completeBatch(7L, first.executionId(), 1, 3, List.of(photo("1")), false);
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> registry.reserveBatch(7L, 2, 3));
    }

    @Test
    void 마지막_배치는_전체_장수를_확정하고_AI_호출_직전에_ANALYZING으로_전환한다() {
        var first = registry.reserveBatch(7L, 1, 2);
        registry.completeBatch(7L, first.executionId(), 1, 3, List.of(photo("1")), false);
        registry.reserveBatch(7L, 2, 2);

        var snapshot = registry.completeBatch(
                7L, first.executionId(), 2, 4, List.of(photo("2")), true);

        assertEquals(InitialUploadExecutionRegistry.State.STORING, snapshot.state());
        assertEquals(2, snapshot.attachmentCount());
        assertEquals(7, snapshot.uploadedBytes());
        assertEquals(2, snapshot.photos().size());
        assertFalse(registry.isAnalysisStarted(7L));

        assertTrue(registry.markAnalysisStarted(7L, first.executionId()));
        assertTrue(registry.isAnalysisStarted(7L));
    }

    @Test
    void 완료_표시와_누적_장수가_다르면_거부한다() {
        var first = registry.reserveBatch(7L, 1, 2);

        assertThrows(InvalidAttachmentUploadException.class,
                () -> registry.completeBatch(
                        7L, first.executionId(), 1, 3, List.of(photo("1")), true));
    }

    @Test
    void 실패한_배치는_같은_순번으로_다시_예약할_수_있다() {
        var first = registry.reserveBatch(7L, 1, 2);
        registry.failBatch(7L, first.executionId(), 1);
        var retryFirst = registry.reserveBatch(7L, 1, 2);
        registry.completeBatch(7L, retryFirst.executionId(), 1, 3, List.of(photo("1")), false);
        registry.reserveBatch(7L, 2, 2);
        registry.failBatch(7L, retryFirst.executionId(), 2);

        assertDoesNotThrow(() -> registry.reserveBatch(7L, 2, 2));
    }

    @Test
    void 취소는_저장_완료를_차단하고_ANALYZING일_때만_AI_취소가_필요하다() {
        var uploading = registry.reserveBatch(7L, 1, 2);
        assertFalse(registry.cancel(7L));
        assertThrows(TripInitialAttachmentUploadNotAllowedException.class,
                () -> registry.completeBatch(
                        7L, uploading.executionId(), 1, 3, List.of(photo("1")), false));

        var analyzing = registry.reserveBatch(8L, 1, 1);
        registry.completeBatch(8L, analyzing.executionId(), 1, 3, List.of(photo("2")), true);
        registry.markAnalysisStarted(8L, analyzing.executionId());
        assertTrue(registry.cancel(8L));
        assertFalse(registry.isCurrent(8L, analyzing.executionId()));
    }

    private InitialUploadExecutionRegistry.StoredPhoto photo(String suffix) {
        StoredFile file = StoredFile.uploaded(1L, suffix + ".jpg", "original-" + suffix, "image/jpeg");
        TripAttachment attachment = TripAttachment.initial(7L, null, "analyze-" + suffix, "preview-" + suffix);
        return new InitialUploadExecutionRegistry.StoredPhoto(
                file,
                attachment,
                new DerivedPhotoKeys("original-" + suffix, "analyze-" + suffix, "preview-" + suffix)
        );
    }
}
