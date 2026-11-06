package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.common.exception.PlaceFolderNotFoundException;
import com.yeodam.yeodambe.common.exception.TripDetailNotAvailableException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.config.TripAttachmentCacheProperties;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.fixture.TripAttachmentCacheErrorMessage;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository.PhotoReadState;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageKey;
import com.yeodam.yeodambe.trip.service.request.AttachmentPageQuery;
import com.yeodam.yeodambe.trip.service.request.cursor.AttachmentCursor;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageData;
import com.yeodam.yeodambe.trip.service.response.AttachmentPageReadScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripAttachmentPageReaderTest {

    private final TripRepository trips = mock(TripRepository.class);
    private final AttachmentPageKey key = new AttachmentPageKey(7L, 3L, null);
    private final TripAccessService access = mock(TripAccessService.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripAttachmentPageStore pages = mock(TripAttachmentPageStore.class);
    private final TripAttachmentCacheProperties properties = new TripAttachmentCacheProperties();
    private final AttachmentPageQuery query = new AttachmentPageQuery(1L, 7L, 3L, null);
    private final Trip trip = mock(Trip.class);
    private final TripAttachmentPageReader reader = new TripAttachmentPageReader(
            access, trips, places, attachments, pages, properties, new ObjectMapper());

    @BeforeEach
    void prepare() {
        properties.setEnabled(true);
        when(access.requireReadableTrip(7L, 1L)).thenReturn(trip);
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(trip.getSequence()).thenReturn(9L);
        when(places.existsByIdAndTripIdAndDeletedAtIsNull(3L, 7L)).thenReturn(true);
    }

    @Test
    void 동등한_커서_표현은_같은_페이지_키를_사용한다() {
        String canonicalCursor = new AttachmentCursor(
                LocalDateTime.of(2026, 11, 4, 12, 0), 5L, 7L, 3L, null)
                .encode(new ObjectMapper());
        String reorderedJson = """
                { "issue": null, "tripPlaceId": 3, "tripId": 7,
                  "tripAttachmentId": 5, "createdAt": "2026-11-04T12:00:00" }
                """;
        String alternateCursor = Base64.getUrlEncoder().encodeToString(
                reorderedJson.getBytes(StandardCharsets.UTF_8));

        AttachmentPageKey normalized = reader.normalizeKey(new AttachmentPageQuery(1L, 7L, 3L, alternateCursor));

        assertThat(alternateCursor).isNotEqualTo(canonicalCursor);
        assertThat(normalized).isEqualTo(new AttachmentPageKey(7L, 3L, canonicalCursor));
        assertThat(reader.normalizeKey(new AttachmentPageQuery(2L, 7L, 3L, canonicalCursor))).isEqualTo(normalized);
    }

    @Test
    void 커서가_없으면_첫_페이지_키를_사용한다() {
        assertThat(reader.normalizeKey(query)).isEqualTo(key);
    }

    @ParameterizedTest
    @CsvSource({"8,3", "7,4"})
    void 다른_대상의_커서는_페이지_키로_사용할_수_없다(Long tripId, Long tripPlaceId) {
        String cursor = new AttachmentCursor(
                LocalDateTime.of(2026, 11, 4, 12, 0), 5L, tripId, tripPlaceId, null)
                .encode(new ObjectMapper());

        assertThatThrownBy(() -> reader.normalizeKey(new AttachmentPageQuery(1L, 7L, 3L, cursor)))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void 메타데이터_캐시가_적중하면_시퀀스를_유지하고_사진_조회를_생략한다() {
        AttachmentPageData cached = new AttachmentPageData(List.of(), false, null, 9L);
        when(pages.find(key, 9L)).thenReturn(Optional.of(cached));

        assertThat(reader.read(query)).isEqualTo(cached);

        verify(access).requireReadableTrip(7L, 1L);
        verifyNoInteractions(attachments);
    }

    @Test
    void 새_시퀀스는_새_메타데이터_캐시_키를_선택한다() {
        when(trip.getSequence()).thenReturn(10L);
        when(attachments.findByPlaceFolderWithCursor(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        AttachmentPageData page = reader.read(query);

        assertThat(page.sequence()).isEqualTo(10L);
        verify(pages).find(key, 10L);
        verify(pages).save(key, page);
    }

    @Test
    void 접근이_금지되면_잘못된_커서도_찾을_수_없음으로_처리한다() {
        when(access.requireReadableTrip(7L, 1L)).thenThrow(new TripNotFoundException());

        assertThatThrownBy(() -> reader.read(new AttachmentPageQuery(1L, 7L, 3L, "bad")))
                .isInstanceOf(PlaceFolderNotFoundException.class);

        verifyNoInteractions(pages, attachments);
    }

    @Test
    void 미완료_여행은_사진을_조회하거나_캐시할_수_없다() {
        when(trip.getProcessingStatus()).thenReturn(ProcessingStatus.PROCESSING);

        assertThatThrownBy(() -> reader.read(query)).isInstanceOf(TripDetailNotAvailableException.class);

        verifyNoInteractions(pages, attachments);
    }

    @Test
    void 다른_폴더의_커서는_접근_검사_후_거부한다() {
        String cursor = new AttachmentCursor(
                LocalDateTime.of(2026, 11, 4, 12, 0), 5L, 7L, 4L, null)
                .encode(new ObjectMapper());

        assertThatThrownBy(() -> reader.read(new AttachmentPageQuery(1L, 7L, 3L, cursor)))
                .isInstanceOf(InvalidCursorException.class);

        verifyNoInteractions(pages, attachments);
    }

    @Test
    void 메타데이터_Redis_실패는_권한을_확인한_DB_스냅샷으로_대체한다() {
        when(pages.find(key, 9L)).thenThrow(new DataAccessResourceFailureException(TripAttachmentCacheErrorMessage.STORE_UNAVAILABLE.message()));
        when(attachments.findByPlaceFolderWithCursor(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(reader.read(query).sequence()).isEqualTo(9L);

        verify(pages, never()).save(eq(key), any());
    }

    @Test
    void 공유_조회는_변경_후_현재_시퀀스를_사용한다() {
        PhotoReadState state = mock(PhotoReadState.class);
        when(trips.findPhotoReadStateById(7L)).thenReturn(Optional.of(state));
        when(state.getProcessingStatus()).thenReturn(ProcessingStatus.COMPLETED);
        when(state.getSequence()).thenReturn(12L);
        when(attachments.findByPlaceFolderWithCursor(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        assertThat(reader.readShared(key).sequence()).isEqualTo(12L);
        verify(pages).find(key, 12L);
        verifyNoInteractions(access);
    }

    @Test
    void 캐시가_비활성화되면_Redis를_사용하지_않는다() {
        properties.setEnabled(false);
        when(attachments.findByPlaceFolderWithCursor(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        AttachmentPageReadScope scope = reader.prepareRead(query);
        assertThat(reader.readValidated(scope).sequence()).isEqualTo(9L);

        verifyNoInteractions(pages);
    }
}
