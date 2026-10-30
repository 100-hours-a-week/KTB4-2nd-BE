package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidAttachmentIdsException;
import com.yeodam.yeodambe.common.exception.AttachmentDeleteNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.yeodam.yeodambe.common.exception.WritePermissionRequiredException;
import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TripAttachmentDeletionServiceTest {

    private final TripAttachmentRepository tripAttachmentRepository = mock(TripAttachmentRepository.class);
    private final UserStatsService userStats = mock(UserStatsService.class);
    private final TripMapCacheStore mapCache = mock(TripMapCacheStore.class);
    private final AdditionalAttachmentModificationGuard guard = mock(AdditionalAttachmentModificationGuard.class);
    private final jakarta.persistence.EntityManager entityManager = mock(jakarta.persistence.EntityManager.class);
    private final TripAttachmentDeletionService service =
            new TripAttachmentDeletionService(mock(com.yeodam.yeodambe.trip.repository.TripRepository.class), guard, entityManager, tripAttachmentRepository, userStats, mapCache);

    @Test
    void 공유_삭제의_잘못된_ID는_조회와_부분_삭제_전에_거절한다() {
        Trip trip = mock(Trip.class);
        List<List<Long>> invalidIds = List.of(
                List.of(), List.of(1L, 1L), List.of(0L), List.of(-1L),
                Arrays.asList(1L, null), LongStream.rangeClosed(1, 201).boxed().toList());
        for (List<Long> ids : invalidIds) {
            assertThatThrownBy(() -> service.deleteForSharedEditing(trip, ids))
                    .isInstanceOf(InvalidAttachmentIdsException.class);
        }
        assertThatThrownBy(() -> service.deleteForSharedEditing(trip, null))
                .isInstanceOf(InvalidAttachmentIdsException.class);
        verifyNoInteractions(tripAttachmentRepository, entityManager, userStats, mapCache);
    }

    @Test
    void 첨부_200개는_일괄_삭제할_수_있다() {
        List<Long> ids = LongStream.rangeClosed(1, 200).boxed().toList();
        Trip trip = mock(Trip.class);
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(trip.getUserId()).thenReturn(1L);
        List<TripAttachment> attachments = ids.stream().map(id -> {
            TripAttachment attachment = mock(TripAttachment.class);
            StoredFile file = mock(StoredFile.class);
            when(attachment.getTrip()).thenReturn(trip);
            when(attachment.getFile()).thenReturn(file);
            return attachment;
        }).toList();
        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(ids))
                .thenReturn(attachments);

        assertDoesNotThrow(() -> service.deleteBulk(1L, ids));

        verify(tripAttachmentRepository).findAllActiveWithTripAndFileByIds(ids);
        for (TripAttachment attachment : attachments) {
            verify(attachment).softDelete(any());
            verify(attachment.getFile()).softDelete(any());
        }
        verify(entityManager).refresh(trip);
        verify(tripAttachmentRepository).flush();
        verify(userStats).refreshFromActiveTrips(1L);
        verify(mapCache).evictAfterCommit(1L);
    }

    @Test
    void 첨부_201개는_DB_조회와_통계_갱신_전에_거부한다() {
        List<Long> ids = LongStream.rangeClosed(1, 201).boxed().toList();

        assertThatThrownBy(() -> service.deleteBulk(1L, ids))
                .isInstanceOf(InvalidAttachmentIdsException.class);

        verifyNoInteractions(tripAttachmentRepository, userStats);
        verifyNoInteractions(mapCache);
    }

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
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(attachment.getTrip()).thenReturn(trip);
        when(trip.getUserId()).thenReturn(2L);
        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(11L)))
                .thenReturn(List.of(attachment));

        assertThatThrownBy(() -> service.deleteBulk(1L, List.of(11L)))
                .isInstanceOf(WritePermissionRequiredException.class);

        verify(attachment, never()).softDelete(any());
        verify(tripAttachmentRepository, never()).flush();
        verifyNoInteractions(mapCache);
    }

    @Test
    void 대표사진을_삭제하면_점수가_가장_높은_사진으로_교체한다() {
        TripAttachment deletedAttachment = mock(TripAttachment.class);
        StoredFile deletedFile = mock(StoredFile.class);
        Trip trip = mock(Trip.class);
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        TripDetailPlace place = mock(TripDetailPlace.class);
        TripAttachment lowerEvaluation = mock(TripAttachment.class);
        TripAttachment replacement = mock(TripAttachment.class);

        when(deletedAttachment.getTripPlace()).thenReturn(place);
        when(deletedAttachment.getTrip()).thenReturn(trip);
        when(deletedAttachment.getTripId()).thenReturn(99L);
        when(trip.getId()).thenReturn(99L);
        when(place.getTripId()).thenReturn(99L);
        when(deletedAttachment.getId()).thenReturn(11L);
        when(deletedAttachment.getPreviewStorageKey()).thenReturn("old-preview");
        when(place.getThumbnailKey()).thenReturn("old-preview");
        when(place.getId()).thenReturn(7L);
        when(deletedAttachment.getFile()).thenReturn(deletedFile);
        when(lowerEvaluation.getEvaluation()).thenReturn(70);
        when(lowerEvaluation.getId()).thenReturn(13L);
        when(lowerEvaluation.getTripPlaceId()).thenReturn(7L);
        when(lowerEvaluation.getPreviewStorageKey()).thenReturn("lower-preview");
        when(replacement.getEvaluation()).thenReturn(95);
        when(replacement.getId()).thenReturn(12L);
        when(replacement.getTripPlaceId()).thenReturn(7L);
        when(replacement.getPreviewStorageKey()).thenReturn("new-preview");
        when(tripAttachmentRepository.findAccessibleById(11L, 1L))
                .thenReturn(Optional.of(deletedAttachment));
        when(tripAttachmentRepository.findAllActiveByTripId(
                99L, ClassificationStatus.ACTIVE))
                .thenReturn(List.of(lowerEvaluation, replacement));

        service.deleteOne(1L, 11L);

        verify(place).changeThumbnailKey("new-preview");
        verify(tripAttachmentRepository).findAllActiveByTripId(99L, ClassificationStatus.ACTIVE);
        verify(tripAttachmentRepository, never()).findAllActiveByTripPlaceId(any(), any());
        verify(deletedAttachment).softDelete(any());
        verify(deletedFile).softDelete(any());
        verify(userStats).refreshFromActiveTrips(1L);
        verify(mapCache).evictAfterCommit(1L);
    }

    @Test
    void 없는_단일_첨부는_무효화를_예약하지_않는다() {
        assertThatThrownBy(() -> service.deleteOne(1L, 11L))
                .isInstanceOf(com.yeodam.yeodambe.common.exception.AttachmentNotFoundException.class);
        verifyNoInteractions(mapCache);
    }

    @Test
    void 통계_갱신에_실패하면_무효화를_예약하지_않는다() {
        TripAttachment attachment = mock(TripAttachment.class);
        StoredFile file = mock(StoredFile.class);
        Trip trip = mock(Trip.class);
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(attachment.getTrip()).thenReturn(trip);
        when(attachment.getFile()).thenReturn(file);
        when(tripAttachmentRepository.findAccessibleById(11L, 1L))
                .thenReturn(Optional.of(attachment));
        doThrow(new IllegalStateException("stats failed"))
                .when(userStats).refreshFromActiveTrips(1L);

        assertThatThrownBy(() -> service.deleteOne(1L, 11L))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapCache);
    }
    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"PROCESSING", "FAILED", "CANCELED"})
    void 완료되지_않은_여행의_단일_삭제는_변경_전에_차단한다(ProcessingStatus status) {
        Trip trip = mock(Trip.class);
        TripAttachment photo = mock(TripAttachment.class);
        StoredFile file = mock(StoredFile.class);
        when(photo.getTrip()).thenReturn(trip);
        when(photo.getFile()).thenReturn(file);
        when(photo.getTripId()).thenReturn(7L);
        when(trip.getProcessingStatus()).thenReturn(status);
        when(tripAttachmentRepository.findAccessibleById(11L, 1L)).thenReturn(Optional.of(photo));

        assertThatThrownBy(() -> service.deleteOne(1L, 11L))
                .isInstanceOf(AttachmentDeleteNotAllowedException.class);

        var order = inOrder(guard, entityManager, trip);
        order.verify(guard).check(7L, 1L);
        order.verify(entityManager).refresh(trip);
        order.verify(trip).getProcessingStatus();
        verify(photo, never()).softDelete(any());
        verify(file, never()).softDelete(any());
        verify(tripAttachmentRepository, never()).flush();
        verifyNoInteractions(userStats, mapCache);
    }

    @Test
    void 완료_여행과_처리중_여행이_섞이면_어떤_사진도_삭제하지_않는다() {
        Trip completed = mock(Trip.class);
        Trip processing = mock(Trip.class);
        when(completed.getUserId()).thenReturn(1L);
        when(processing.getUserId()).thenReturn(1L);
        when(completed.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(processing.getProcessingStatus()).thenReturn(ProcessingStatus.PROCESSING);
        TripAttachment first = mock(TripAttachment.class);
        TripAttachment second = mock(TripAttachment.class);
        when(first.getTrip()).thenReturn(completed);
        when(second.getTrip()).thenReturn(processing);
        when(first.getTripId()).thenReturn(7L);
        when(second.getTripId()).thenReturn(8L);
        when(tripAttachmentRepository.findAllActiveWithTripAndFileByIds(List.of(11L, 12L)))
                .thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.deleteBulk(1L, List.of(11L, 12L)))
                .isInstanceOf(AttachmentDeleteNotAllowedException.class);

        verify(first, never()).softDelete(any());
        verify(second, never()).softDelete(any());
        verify(tripAttachmentRepository, never()).flush();
        verifyNoInteractions(userStats, mapCache);
    }

    @Test
    void 추가_업로드중_삭제_차단은_기존대로_유지한다() {
        TripAttachment photo = mock(TripAttachment.class);
        when(photo.getTripId()).thenReturn(7L);
        when(tripAttachmentRepository.findAccessibleById(11L, 1L)).thenReturn(Optional.of(photo));
        doThrow(new TripAttachmentAddNotAllowedException()).when(guard).check(7L, 1L);

        assertThatThrownBy(() -> service.deleteOne(1L, 11L))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        verifyNoInteractions(entityManager, userStats, mapCache);
        verify(photo, never()).softDelete(any());
    }

}
