package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.common.exception.TripUpdateNotAllowedException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripEditServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final TripAccessService access = mock(TripAccessService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TripService service = new TripService(mock(com.yeodam.yeodambe.trip.service.AdditionalAttachmentModificationGuard.class),
            trips, regions, mock(RegionCatalog.class), attachments, storage, access, mapper);
    private final LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 12, 0);
    private Trip trip;

    @BeforeEach
    void setUp() {
        trip = mock(Trip.class);
        when(trip.getId()).thenReturn(7L);
        when(trip.getTripName()).thenReturn("제주 여행");
        when(trip.getStartDate()).thenReturn(LocalDate.of(2026, 9, 1));
        when(trip.getEndDate()).thenReturn(LocalDate.of(2026, 9, 3));
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(access.requireReadableTrip(7L, 1L)).thenReturn(trip);
        TripRegion region = mock(TripRegion.class);
        when(region.getId()).thenReturn(31L);
        when(region.getRegionCode()).thenReturn("50110");
        when(region.getRegionName()).thenReturn("제주특별자치도 제주시");
        when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L)).thenReturn(List.of(region));
    }

    @Test
    void 사진이_없어도_여행_정보와_빈_페이지를_반환한다() {
        when(attachments.findForEditWithCursor(7L, null, null, PageRequest.of(0, 19)))
                .thenReturn(List.of());
        var result = service.findTripForEdit(7L, 1L, null);
        assertThat(result.tripId()).isEqualTo(7L);
        assertThat(result.tripName()).isEqualTo("제주 여행");
        assertThat(result.startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(result.endDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(result.regions().getFirst().regionCode()).isEqualTo("50110");
        assertThat(result.regions().getFirst().regionId()).isEqualTo(31L);
        assertThat(result.attachmentCount()).isZero();
        assertThat(result.attachments().items()).isEmpty();
        assertThat(result.attachments().hasNext()).isFalse();
        assertThat(result.attachments().nextCursor()).isNull();
        verifyNoInteractions(storage, trips);
    }

    @Test
    void 사진이_정확히_18장이면_다음_커서가_없다() {
        List<TripAttachment> page = photos(18);
        when(attachments.findForEditWithCursor(7L, null, null, PageRequest.of(0, 19)))
                .thenReturn(page);
        when(attachments.countForEditByTripId(7L)).thenReturn(18L);
        var result = service.findTripForEdit(7L, 1L, null);
        assertThat(result.attachments().items()).hasSize(18);
        assertThat(result.attachmentCount()).isEqualTo(18);
        assertThat(result.attachments().hasNext()).isFalse();
        assertThat(result.attachments().nextCursor()).isNull();
    }

    @Test
    void 사진_19장을_조회하면_18장만_반환하고_18번째로_커서를_만든다() {
        List<TripAttachment> page = photos(19);
        when(attachments.findForEditWithCursor(7L, null, null, PageRequest.of(0, 19)))
                .thenReturn(page);
        when(attachments.countForEditByTripId(7L)).thenReturn(30L);
        var result = service.findTripForEdit(7L, 1L, null);
        assertThat(result.attachments().items()).hasSize(18);
        assertThat(result.attachments().items().getFirst().thumbnailUrl()).isEqualTo("url/preview-100");
        assertThat(result.attachmentCount()).isEqualTo(30);
        assertThat(result.attachments().hasNext()).isTrue();
        assertThat(AttachmentCursor.decode(result.attachments().nextCursor(), mapper))
                .isEqualTo(new AttachmentCursor(createdAt, 83L));
        verify(storage, never()).createReadUrl("preview-82");
        verifyNoInteractions(trips);
    }

    @Test
    void 다음_페이지는_커서의_시각과_ID를_조회에_전달한다() {
        String cursor = new AttachmentCursor(createdAt, 83L).encode(mapper);
        TripAttachment nextPhoto = photo(82L);
        when(attachments.findForEditWithCursor(7L, createdAt, 83L, PageRequest.of(0, 19)))
                .thenReturn(List.of(nextPhoto));
        var result = service.findTripForEdit(7L, 1L, cursor);
        assertThat(result.attachments().items().getFirst().tripAttachmentId()).isEqualTo(82L);
        verify(attachments).findForEditWithCursor(7L, createdAt, 83L, PageRequest.of(0, 19));
    }

    @Test
    void 잘못된_커서는_사진_조회_전에_거부한다() {
        assertThatThrownBy(() -> service.findTripForEdit(7L, 1L, "invalid-cursor"))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(attachments, storage, regions);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessingStatus.class, names = {"PROCESSING", "FAILED", "CANCELED"})
    void 완료되지_않은_여행의_수정_화면은_거부한다(ProcessingStatus status) {
        when(trip.getProcessingStatus()).thenReturn(status);
        assertThatThrownBy(() -> service.findTripForEdit(7L, 1L, null))
                .isInstanceOf(TripUpdateNotAllowedException.class);
        verifyNoInteractions(attachments, storage, regions);
    }

    @Test
    void 다른_사용자의_여행은_사진을_조회하지_않는다() {
        when(access.requireReadableTrip(7L, 2L)).thenThrow(new TripNotFoundException());
        assertThatThrownBy(() -> service.findTripForEdit(7L, 2L, null))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(attachments, storage, regions);
    }

    private List<TripAttachment> photos(int count) {
        return LongStream.range(0, count).mapToObj(i -> photo(100L - i)).toList();
    }

    private TripAttachment photo(Long id) {
        TripAttachment attachment = mock(TripAttachment.class);
        when(attachment.getId()).thenReturn(id);
        when(attachment.getCreatedAt()).thenReturn(createdAt);
        when(attachment.getPreviewStorageKey()).thenReturn("preview-" + id);
        when(storage.createReadUrl("preview-" + id)).thenReturn("url/preview-" + id);
        return attachment;
    }
}
