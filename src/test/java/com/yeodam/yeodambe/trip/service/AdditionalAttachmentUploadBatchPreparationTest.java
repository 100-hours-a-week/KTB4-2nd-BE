package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentUploadLimitExceededException;
import com.yeodam.yeodambe.common.exception.InvalidAttachmentUploadException;
import com.yeodam.yeodambe.common.exception.TripAttachmentAddNotAllowedException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadBatch;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadItem;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentUploadStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadBatchRepository;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentUploadItemRepository;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.AdditionalAttachmentUploadUrlRequest;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdditionalAttachmentUploadBatchPreparationTest {
    private static final long TRIP_ID = 10L;
    private static final long USER_ID = 2L;
    private static final String ADDITION_ID = "550e8400-e29b-41d4-a716-446655440000";
    private final TripRepository tripRepository = mock(TripRepository.class);
    private final AdditionalAttachmentUploadBatchRepository batchRepository =
            mock(AdditionalAttachmentUploadBatchRepository.class);
    private final AdditionalAttachmentUploadItemRepository itemRepository =
            mock(AdditionalAttachmentUploadItemRepository.class);
    private final TripAttachmentRepository attachmentRepository = mock(TripAttachmentRepository.class);
    private final AdditionalAttachmentUploadUrlService service = new AdditionalAttachmentUploadUrlService(
            tripRepository, batchRepository, itemRepository, attachmentRepository,
            mock(TripAttachmentStorageClient.class));
    private final Trip trip = mock(Trip.class);

    @BeforeEach
    void setUp() {
        when(tripRepository.findOwnedActiveForUpdate(TRIP_ID, USER_ID)).thenReturn(Optional.of(trip));
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(batchRepository.save(any(AdditionalAttachmentUploadBatch.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void savesPendingBatchAndOrderedFileMetadataWithDistinctKeys() {
        when(attachmentRepository.countForEditByTripId(TRIP_ID)).thenReturn(198L);
        var request = new AdditionalAttachmentUploadUrlRequest(ADDITION_ID, 1, 2, true,
                List.of(file(1024), file(2048)));

        var batch = service.prepareBatch(TRIP_ID, USER_ID, request);

        assertThat(UUID.fromString(batch.getUploadId())).isNotNull();
        assertThat(batch.getAdditionId()).isEqualTo(ADDITION_ID);
        assertThat(batch.getTripId()).isEqualTo(TRIP_ID);
        assertThat(batch.getUserId()).isEqualTo(USER_ID);
        assertThat(batch.getStatus()).isEqualTo(AdditionalAttachmentUploadStatus.PENDING);
        assertThat(batch.getLastBatch()).isTrue();
        var captor = ArgumentCaptor.forClass(AdditionalAttachmentUploadItem.class);
        verify(itemRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AdditionalAttachmentUploadItem::getFileOrder)
                .containsExactly(1, 2);
        assertThat(captor.getAllValues()).extracting(AdditionalAttachmentUploadItem::getSizeBytes)
                .containsExactly(1024L, 2048L);
        assertThat(captor.getAllValues()).allSatisfy(item -> {
            assertThat(item.getBatch()).isSameAs(batch);
            assertThat(item.getObjectKey()).startsWith("trip-additions/" + ADDITION_ID + "/original/");
        });
        assertThat(captor.getAllValues()).extracting(AdditionalAttachmentUploadItem::getObjectKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void rejectsMissingOrInaccessibleTripWithoutSaving() {
        when(tripRepository.findOwnedActiveForUpdate(TRIP_ID, USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(1, 1, true)))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(batchRepository, itemRepository, attachmentRepository);
    }

    @Test
    void rejectsTripNotCompleted() {
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.PROCESSING);
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(1, 1, true)))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        verify(batchRepository, never()).save(any());
    }

    @Test
    void rejectsOtherActiveAddition() {
        when(batchRepository.existsByTripIdAndAdditionIdNotAndStatusIn(eq(TRIP_ID), eq(ADDITION_ID), any()))
                .thenReturn(true);
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(1, 1, true)))
                .isInstanceOf(TripAttachmentAddNotAllowedException.class);
        verify(batchRepository, never()).save(any());
        verify(itemRepository, never()).save(any());
    }

    @Test
    void reusesSameBatchWithoutCreatingRecordsAgain() {
        var batch = previousBatch(1, 1, true, AdditionalAttachmentUploadStatus.PENDING);
        when(batchRepository.findByAdditionIdAndBatchNo(ADDITION_ID, 1)).thenReturn(Optional.of(batch));
        when(itemRepository.findAllByBatch_IdOrderByFileOrderAsc(7L))
                .thenReturn(List.of(savedItem(batch, 1024)));

        assertThat(service.prepareBatch(TRIP_ID, USER_ID, request(1, 1, true))).isSameAs(batch);
        verify(batchRepository, never()).save(any());
        verify(itemRepository, never()).save(any());
    }

    @Test
    void rejectsChangedMetadataForExistingBatch() {
        var batch = previousBatch(1, 1, true, AdditionalAttachmentUploadStatus.PENDING);
        when(batchRepository.findByAdditionIdAndBatchNo(ADDITION_ID, 1)).thenReturn(Optional.of(batch));
        when(itemRepository.findAllByBatch_IdOrderByFileOrderAsc(7L))
                .thenReturn(List.of(savedItem(batch, 2048)));
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(1, 1, true)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        verify(batchRepository, never()).save(any());
    }

    @Test
    void rejectsTotalOverTwoHundredIncludingExistingPhotos() {
        when(attachmentRepository.countForEditByTripId(TRIP_ID)).thenReturn(200L);
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(1, 1, true)))
                .isInstanceOf(AttachmentUploadLimitExceededException.class);
        verify(batchRepository, never()).save(any());
    }

    @Test
    void acceptsNextBatchAfterPreviousBatchVerified() {
        var previous = previousBatch(1, 2, false, AdditionalAttachmentUploadStatus.VERIFIED);
        when(batchRepository.findAllByAdditionIdOrderByBatchNoAsc(ADDITION_ID)).thenReturn(List.of(previous));
        when(itemRepository.findAdditionItems(ADDITION_ID)).thenReturn(List.of(savedItem(previous, 1024)));

        var batch = service.prepareBatch(TRIP_ID, USER_ID, request(2, 2, true));

        assertThat(batch.getBatchNo()).isEqualTo(2);
        assertThat(batch.getTotalAttachmentCount()).isEqualTo(2);
        assertThat(batch.getLastBatch()).isTrue();
        verify(itemRepository).save(any(AdditionalAttachmentUploadItem.class));
    }

    @Test
    void rejectsSkippedBatchNumberAndUnverifiedPreviousBatch() {
        var previous = previousBatch(1, 3, false, AdditionalAttachmentUploadStatus.VERIFIED);
        when(batchRepository.findAllByAdditionIdOrderByBatchNoAsc(ADDITION_ID)).thenReturn(List.of(previous));
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(3, 3, true)))
                .isInstanceOf(InvalidAttachmentUploadException.class);

        when(previous.getStatus()).thenReturn(AdditionalAttachmentUploadStatus.PENDING);
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(2, 3, false)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        verify(batchRepository, never()).save(any());
    }

    @Test
    void rejectsFirstBatchStartingAtTwoAndIncorrectLastFlag() {
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(2, 2, false)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(1, 2, true)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(1, 1, false)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        verify(batchRepository, never()).save(any());
    }

    @Test
    void rejectsAdditionIdBelongingToDifferentTrip() {
        var previous = previousBatch(1, 2, false, AdditionalAttachmentUploadStatus.VERIFIED);
        when(previous.getTripId()).thenReturn(999L);
        when(batchRepository.findAllByAdditionIdOrderByBatchNoAsc(ADDITION_ID)).thenReturn(List.of(previous));
        assertThatThrownBy(() -> service.prepareBatch(TRIP_ID, USER_ID, request(2, 2, true)))
                .isInstanceOf(InvalidAttachmentUploadException.class);
        verify(batchRepository, never()).save(any());
    }

    private AdditionalAttachmentUploadUrlRequest request(int batchNo, int total, boolean complete) {
        return new AdditionalAttachmentUploadUrlRequest(ADDITION_ID, batchNo, total, complete, List.of(file(1024)));
    }

    private AdditionalAttachmentUploadUrlRequest.Attachment file(long bytes) {
        return new AdditionalAttachmentUploadUrlRequest.Attachment("same.jpg", "image/jpeg", bytes);
    }

    private AdditionalAttachmentUploadBatch previousBatch(
            int batchNo, int total, boolean last, AdditionalAttachmentUploadStatus status) {
        var batch = mock(AdditionalAttachmentUploadBatch.class);
        when(batch.getId()).thenReturn(7L);
        when(batch.getAdditionId()).thenReturn(ADDITION_ID);
        when(batch.getTripId()).thenReturn(TRIP_ID);
        when(batch.getUserId()).thenReturn(USER_ID);
        when(batch.getBatchNo()).thenReturn(batchNo);
        when(batch.getTotalAttachmentCount()).thenReturn(total);
        when(batch.getLastBatch()).thenReturn(last);
        when(batch.getStatus()).thenReturn(status);
        return batch;
    }

    private AdditionalAttachmentUploadItem savedItem(AdditionalAttachmentUploadBatch batch, long bytes) {
        return new AdditionalAttachmentUploadItem(batch, 1, "same.jpg", "image/jpeg", bytes, "old-key");
    }
}
