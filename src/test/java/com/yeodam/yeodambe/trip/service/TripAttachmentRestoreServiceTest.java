package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.*;
import com.yeodam.yeodambe.file.entity.StoredFile;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TripAttachmentRestoreServiceTest {
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripRepository trips = mock(TripRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAccessService access = mock(TripAccessService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final TripAttachmentRestoreService service = new TripAttachmentRestoreService(
            attachments, trips, places, access, entityManager,
            Validation.buildDefaultValidatorFactory().getValidator()
    );
    private Trip trip;
    private TripDetailPlace place;

    @BeforeEach
    void setUp() {
        trip = Trip.localMock(1L, "trip", LocalDate.now(), LocalDate.now(), "old");
        ReflectionTestUtils.setField(trip, "id", 10L);
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        place = mock(TripDetailPlace.class);
        lenient().when(place.getId()).thenReturn(20L);
        lenient().when(place.getTripId()).thenReturn(10L);
        lenient().when(trips.findOwnedActiveForUpdate(10L, 1L)).thenReturn(Optional.of(trip));
        lenient().when(places.findByIdForUpdate(20L)).thenReturn(Optional.of(place));
        lenient().when(attachments.restoreIfUnclassified(anyLong(), anyLong(), anyLong(), nullable(Long.class), anyLong(), any()))
                .thenReturn(1);
    }

    @Test
    void 매핑없는_사진은_목적지로_복구한다() {
        TripAttachment attachment = attachment(30L, null);
        when(attachments.findAccessibleById(30L, 1L)).thenReturn(Optional.of(attachment));
        var response = service.restoreOne(1L, 30L, new AttachmentRestoreRequest(20L));
        assertThat(response.classificationType()).isEqualTo("CLASSIFIED");
        assertThat(response.tripPlaceId()).isEqualTo(20L);
        verify(entityManager, times(2)).refresh(attachment);
    }

    @Test
    void 원래매핑이_다르면_거부한다() {
        when(attachments.findAccessibleById(30L, 1L)).thenReturn(Optional.of(attachment(30L, 99L)));
        assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(20L)))
                .isInstanceOf(RestorePlaceMismatchException.class);
        verify(attachments, never()).restoreIfUnclassified(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 입력오류와_목적지누락을_구분한다() {
        assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(null)))
                .isInstanceOf(RestorePlaceFolderRequiredException.class);
        assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(0L)))
                .isInstanceOf(InvalidRestoreRequestException.class);
        assertThatThrownBy(() -> service.restoreBulk(1L, new BulkAttachmentRestoreRequest(List.of())))
                .isInstanceOf(InvalidRestoreRequestException.class);
        var duplicate = new BulkAttachmentRestoreRequest.Item(30L, 20L);
        assertThatThrownBy(() -> service.restoreBulk(1L, new BulkAttachmentRestoreRequest(List.of(duplicate, duplicate))))
                .isInstanceOf(InvalidRestoreRequestException.class);
        var tooMany = LongStream.rangeClosed(1, 201).mapToObj(id -> new BulkAttachmentRestoreRequest.Item(id, 20L)).toList();
        assertThatThrownBy(() -> service.restoreBulk(1L, new BulkAttachmentRestoreRequest(tooMany)))
                .isInstanceOf(InvalidRestoreRequestException.class);
    }

    @Test
    void 이백개와_요청순서_중복목적지를_보존한다() {
        List<TripAttachment> found = LongStream.rangeClosed(1, 200).mapToObj(id -> attachment(id, 20L)).toList();
        var items = LongStream.rangeClosed(1, 200).map(id -> 201 - id)
                .mapToObj(id -> new BulkAttachmentRestoreRequest.Item(id, 20L)).toList();
        when(attachments.findAllActiveWithTripAndFileByIds(any())).thenReturn(found);
        var result = service.restoreBulk(1L, new BulkAttachmentRestoreRequest(items));
        assertThat(result.restoredTripAttachmentIds()).containsExactlyElementsOf(items.stream().map(BulkAttachmentRestoreRequest.Item::tripAttachmentId).toList());
        assertThat(result.tripPlaceIds()).hasSize(200).containsOnly(20L);
    }

    @Test
    void 마지막검증실패는_업데이트하지_않는다() {
        TripAttachment last = attachment(31L, 20L);
        ReflectionTestUtils.setField(last, "classificationStatus", ClassificationStatus.ACTIVE);
        when(attachments.findAllActiveWithTripAndFileByIds(any())).thenReturn(List.of(attachment(30L, 20L), last));
        var request = new BulkAttachmentRestoreRequest(List.of(
                new BulkAttachmentRestoreRequest.Item(30L, 20L), new BulkAttachmentRestoreRequest.Item(31L, 20L)
        ));
        assertThatThrownBy(() -> service.restoreBulk(1L, request)).isInstanceOf(BulkRestoreFailedException.class);
        verify(attachments, never()).restoreIfUnclassified(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 업데이트영건과_디비장애를_구분한다() {
        when(attachments.findAccessibleById(30L, 1L)).thenReturn(Optional.of(attachment(30L, 20L)));
        when(attachments.restoreIfUnclassified(any(), any(), any(), any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(20L)))
                .isInstanceOf(AttachmentRestoreNotAllowedException.class);
        when(attachments.findAllActiveWithTripAndFileByIds(any())).thenThrow(new IllegalStateException("database"));
        assertThatThrownBy(() -> service.restoreBulk(1L, new BulkAttachmentRestoreRequest(List.of(new BulkAttachmentRestoreRequest.Item(30L, 20L)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 목적지필수오류는_다른아이디오류보다_우선한다() {
        var mixed = new BulkAttachmentRestoreRequest(List.of(new BulkAttachmentRestoreRequest.Item(-1L, null)));
        assertThatThrownBy(() -> service.restoreBulk(1L, mixed)).isInstanceOf(RestorePlaceFolderRequiredException.class);
        var nullItem = new BulkAttachmentRestoreRequest(java.util.Arrays.asList((BulkAttachmentRestoreRequest.Item) null));
        assertThatThrownBy(() -> service.restoreBulk(1L, nullItem)).isInstanceOf(InvalidRestoreRequestException.class);
    }

    @Test
    void 여행상태와_사진상태_폴더와_소유권을_검증한다() {
        TripAttachment attachment = attachment(30L, 20L);
        when(attachments.findAccessibleById(30L, 1L)).thenReturn(Optional.of(attachment));
        for (ProcessingStatus status : List.of(ProcessingStatus.PROCESSING, ProcessingStatus.FAILED)) {
            ReflectionTestUtils.setField(trip, "processingStatus", status);
            assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(20L)))
                    .isInstanceOf(AttachmentRestoreNotAllowedException.class);
        }
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        ReflectionTestUtils.setField(attachment, "issue", AttachmentIssue.NONE);
        assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(20L)))
                .isInstanceOf(AttachmentRestoreNotAllowedException.class);
        ReflectionTestUtils.setField(attachment, "issue", AttachmentIssue.BLURRY);
        when(place.getTripId()).thenReturn(99L);
        assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(20L)))
                .isInstanceOf(PlaceFolderNotFoundException.class);
        when(place.getTripId()).thenReturn(10L);
        when(place.getDeletedAt()).thenReturn(java.time.LocalDateTime.now());
        assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(20L)))
                .isInstanceOf(PlaceFolderNotFoundException.class);
        when(places.findByIdForUpdate(20L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(20L)))
                .isInstanceOf(PlaceFolderNotFoundException.class);
        doThrow(new TripNotFoundException()).when(access).requireWritableTrip(10L, 1L);
        assertThatThrownBy(() -> service.restoreOne(1L, 30L, new AttachmentRestoreRequest(20L)))
                .isInstanceOf(AttachmentNotFoundException.class);
    }

    private TripAttachment attachment(Long id, Long placeId) {
        TripAttachment attachment = TripAttachment.initial(10L, id, "analyze", "preview");
        attachment.unclassify(placeId, AttachmentIssue.BLURRY, RegionOrigin.EXIF, null, null, null, 90);
        ReflectionTestUtils.setField(attachment, "id", id);
        ReflectionTestUtils.setField(attachment, "trip", trip);
        ReflectionTestUtils.setField(attachment, "tripPlaceId", placeId);
        ReflectionTestUtils.setField(attachment, "file", StoredFile.uploaded(1L, "file.jpg", "key", "image/jpeg"));
        return attachment;
    }
}
