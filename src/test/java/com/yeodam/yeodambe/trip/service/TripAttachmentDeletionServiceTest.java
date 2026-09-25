package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TripAttachmentDeletionServiceTest {

    private final TripAttachmentRepository tripAttachmentRepository = mock(TripAttachmentRepository.class);
    private final UserStatsService userStats = mock(UserStatsService.class);
    private final TripObjectCleanupService cleanup = mock(TripObjectCleanupService.class);
    private final TripAttachmentDeletionService service =
            new TripAttachmentDeletionService(tripAttachmentRepository, userStats, cleanup);

    @Test
    void 중복된_첨부_ID는_조회_전에_거부한다() {
        assertThatThrownBy(() -> service.deleteBulk(1L, List.of(11L, 11L)))
                .isInstanceOf(InvalidAttachmentIdsException.class);

        verifyNoInteractions(tripAttachmentRepository);
    }

    @Test
    void 다른_회원의_첨부가_섞이면_삭제하지_않는다() {
        TripAttachment attachment = mock(TripAttachment.class);
        Trip trip = mock(Trip.class);
        when(attachment.getTrip()).thenReturn(trip);
        when(trip.getUserId()).thenReturn(2L);
        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(11L)))
                .thenReturn(List.of(attachment));

        assertThatThrownBy(() -> service.deleteBulk(1L, List.of(11L)))
                .isInstanceOf(WritePermissionRequiredException.class);

        verify(attachment, never()).softDelete(any());
        verify(tripAttachmentRepository, never()).flush();
    }

    @Test
    void 대표사진을_삭제하면_점수가_가장_높은_사진으로_교체한다() {
        TripAttachment deletedAttachment = mock(TripAttachment.class);
        StoredFile deletedFile = mock(StoredFile.class);
        TripDetailPlace place = mock(TripDetailPlace.class);
        TripAttachment lowerEvaluation = mock(TripAttachment.class);
        TripAttachment replacement = mock(TripAttachment.class);

        when(deletedAttachment.getTripPlace()).thenReturn(place);
        when(deletedAttachment.getId()).thenReturn(11L);
        when(deletedAttachment.getPreviewStorageKey()).thenReturn("old-preview");
        when(place.getThumbnailKey()).thenReturn("old-preview");
        when(place.getId()).thenReturn(7L);
        when(deletedAttachment.getFile()).thenReturn(deletedFile);
        when(lowerEvaluation.getEvaluation()).thenReturn(70);
        when(lowerEvaluation.getId()).thenReturn(13L);
        when(lowerEvaluation.getPreviewStorageKey()).thenReturn("lower-preview");
        when(replacement.getEvaluation()).thenReturn(95);
        when(replacement.getId()).thenReturn(12L);
        when(replacement.getPreviewStorageKey()).thenReturn("new-preview");
        when(tripAttachmentRepository.findAccessibleById(11L, 1L))
                .thenReturn(Optional.of(deletedAttachment));
        when(tripAttachmentRepository.findAllActiveByTripPlaceId(
                place.getId(), ClassificationStatus.ACTIVE))
                .thenReturn(List.of(lowerEvaluation, replacement));

        service.deleteOne(1L, 11L);

        verify(place).changeThumbnailKey("new-preview");
        verify(deletedAttachment).softDelete(any());
        verify(deletedFile).softDelete(any());
        verify(userStats).refreshFromActiveTrips(1L);
        verify(cleanup).process(List.of(11L));
    }

    @Test
    void 커밋_후_객체_정리_실패는_첨부_삭제_성공을_바꾸지_않는다() {
        TripAttachment attachment = mock(TripAttachment.class);
        StoredFile file = mock(StoredFile.class);
        when(attachment.getId()).thenReturn(11L);
        when(attachment.getFile()).thenReturn(file);
        when(tripAttachmentRepository.findAccessibleById(11L, 1L))
                .thenReturn(Optional.of(attachment));
        doThrow(new IllegalStateException("S3")).when(cleanup).process(List.of(11L));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteOne(1L, 11L);

            verifyNoInteractions(cleanup);
            assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit));
            verify(cleanup).process(List.of(11L));
            verify(attachment).softDelete(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
