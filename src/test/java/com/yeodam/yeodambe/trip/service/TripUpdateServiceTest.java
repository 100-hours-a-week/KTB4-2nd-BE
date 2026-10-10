package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.common.exception.*;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.request.TripUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripUpdateServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final RegionCatalog catalog = mock(RegionCatalog.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final TripService service = new TripService(
            trips, regions, catalog, attachments, storage, mock(TripAccessService.class),
            new tools.jackson.databind.ObjectMapper(), mock(StoryRepository.class));
    private Trip trip;
    private TripRegion seoul;
    private final LocalDate start = LocalDate.of(2026, 9, 1);
    private final LocalDate end = LocalDate.of(2026, 9, 3);

    @BeforeEach
    void setUp() {
        trip = new Trip(1L, "기존 여행", start, end);
        ReflectionTestUtils.setField(trip, "id", 7L);
        ReflectionTestUtils.setField(trip, "processingStatus", ProcessingStatus.COMPLETED);
        trip.changeFavorite(true);
        trip.changeThumbnailKey("existing-thumbnail");
        seoul = region("11000");
        when(trips.findOwnedActiveForUpdate(7L, 1L)).thenReturn(Optional.of(trip));
        when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L)).thenReturn(List.of(seoul));
        when(catalog.getRequired("11000")).thenReturn(reference("11000"));
    }

    @Test
    void 이름만_수정하면_날짜와_지역과_기존_상태를_유지한다() {
        var result = service.updateTrip(7L, 1L,
                new TripUpdateRequest("변경 여행", null, null, null));

        assertThat(result.tripId()).isEqualTo(7L);
        assertThat(result.tripName()).isEqualTo("변경 여행");
        assertThat(result.startDate()).isEqualTo(start);
        assertThat(result.endDate()).isEqualTo(end);
        assertThat(result.regions()).extracting(r -> r.regionCode()).containsExactly("11000");
        assertThat(trip.getFavorite()).isTrue();
        assertThat(trip.getThumbnailKey()).isEqualTo("existing-thumbnail");
        assertThat(trip.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(seoul.getDeletedAt()).isNull();
        verify(regions, never()).saveAll(any());
        verifyNoInteractions(attachments, storage);
    }

    @Test
    void 시작일만_수정해도_기존_종료일과_합쳐서_검증한다() {
        assertThatThrownBy(() -> service.updateTrip(7L, 1L,
                new TripUpdateRequest(null, end.plusDays(1), null, null)))
                .isInstanceOf(InvalidTripRequestException.class);
        assertThat(trip.getStartDate()).isEqualTo(start);
    }

    @Test
    void 다른_회원이거나_없는_여행이면_수정하지_않는다() {
        when(trips.findOwnedActiveForUpdate(7L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateTrip(7L, 2L,
                new TripUpdateRequest("변경", null, null, null)))
                .isInstanceOf(TripNotFoundException.class);
        verifyNoInteractions(regions);
    }

    @Test
    void 처리중과_실패_상태에서는_수정을_거부한다() {
        for (ProcessingStatus status : List.of(ProcessingStatus.PROCESSING, ProcessingStatus.FAILED)) {
            ReflectionTestUtils.setField(trip, "processingStatus", status);
            assertThatThrownBy(() -> service.updateTrip(7L, 1L,
                    new TripUpdateRequest("변경", null, null, null)))
                    .isInstanceOf(TripUpdateNotAllowedException.class);
        }
        verifyNoInteractions(regions);
    }

    @Test
    void 이름_중복_검사에서_현재_여행을_제외한다() {
        when(trips.existsByUserIdAndTripNameAndDeletedAtIsNullAndIdNot(1L, "중복", 7L))
                .thenReturn(true);
        assertThatThrownBy(() -> service.updateTrip(7L, 1L,
                new TripUpdateRequest("중복", null, null, null)))
                .isInstanceOf(TripNameDuplicatedException.class);
        assertThat(trip.getTripName()).isEqualTo("기존 여행");
        verify(trips).existsByUserIdAndTripNameAndDeletedAtIsNullAndIdNot(1L, "중복", 7L);
    }

    @Test
    void 없는_지역_코드는_정보를_변경하기_전에_거부한다() {
        when(catalog.getRequired("99999")).thenThrow(new InvalidTripRequestException());
        assertThatThrownBy(() -> service.updateTrip(7L, 1L,
                new TripUpdateRequest("변경", null, null, List.of("99999"))))
                .isInstanceOf(InvalidTripRequestException.class);
        assertThat(trip.getTripName()).isEqualTo("기존 여행");
        assertThat(seoul.getDeletedAt()).isNull();
    }

    @Test
    void 유지할_지역은_보존하고_제거할_지역만_삭제하며_새_지역을_추가한다() {
        TripRegion busan = region("26000");
        when(catalog.getRequired("50000")).thenReturn(reference("50000"));
        when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L))
                .thenReturn(List.of(seoul, busan));
        when(regions.saveAll(any())).thenAnswer(invocation -> {
            List<TripRegion> added = invocation.getArgument(0);
            assertThat(added).extracting(TripRegion::getRegionCode).containsExactly("50000");
            when(regions.findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(7L))
                    .thenReturn(List.of(seoul, added.getFirst()));
            return added;
        });
        var result = service.updateTrip(7L, 1L,
                new TripUpdateRequest(null, null, null, List.of("11000", "50000")));
        assertThat(seoul.getDeletedAt()).isNull();
        assertThat(busan.getDeletedAt()).isNotNull();
        assertThat(result.regions()).extracting(r -> r.regionCode())
                .containsExactly("11000", "50000");
    }

    private RegionCatalog.Region reference(String code) {
        return new RegionCatalog.Region(code, "지역", BigDecimal.ONE, BigDecimal.ONE);
    }

    private TripRegion region(String code) {
        return new TripRegion(trip, code, "지역", BigDecimal.ONE, BigDecimal.ONE);
    }
}
