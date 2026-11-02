package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.trip.client.TripAttachmentStorageClient;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripAttachmentCount;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionName;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.InvitedTripCursor;
import com.yeodam.yeodambe.trip.service.response.InvitedTripListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class InvitedTripListServiceTest {
    private static final LocalDate START_DATE = LocalDate.of(2026, 11, 20);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 12, 0);
    private final TripShareUserRepository shares = mock(TripShareUserRepository.class);
    private final TripRegionRepository regions = mock(TripRegionRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripAttachmentStorageClient storage = mock(TripAttachmentStorageClient.class);
    private final Clock clock = mock(Clock.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InvitedTripListService service = new InvitedTripListService(
            shares, regions, attachments, storage, clock, objectMapper);

    @BeforeEach
    void fixedTime() {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-02T03:00:00Z"));
    }

    @Test
    void 여덟개를_읽으면_일곱개와_마지막_반환_항목의_커서를_반환한다() {
        givenPage(8);

        InvitedTripListResponse response = service.findInvitedTrips(42L, null);

        assertThat(response.totalCount()).isEqualTo(12);
        assertThat(response.items()).extracting(InvitedTripListResponse.Item::tripId)
                .containsExactly(99L, 98L, 97L, 96L, 95L, 94L, 93L);
        assertThat(response.hasNext()).isTrue();
        assertThat(InvitedTripCursor.decode(response.nextCursor(), objectMapper))
                .isEqualTo(new InvitedTripCursor(START_DATE, 93L));
        verify(clock).instant();
    }

    @Test
    void 일곱개면_마지막_페이지이고_다음_커서는_없다() {
        givenPage(7);

        InvitedTripListResponse response = service.findInvitedTrips(42L, null);

        assertThat(response.items()).hasSize(7);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 빈_페이지는_전체_개수를_유지하고_부가정보를_조회하지_않는다() {
        String cursor = new InvitedTripCursor(START_DATE, 50L).encode(objectMapper);
        when(shares.countInvitedTrips(42L, NOW)).thenReturn(12L);
        when(shares.findInvitedPage(42L, NOW, START_DATE, 50L, PageRequest.of(0, 8)))
                .thenReturn(List.of());

        InvitedTripListResponse response = service.findInvitedTrips(42L, cursor);

        assertThat(response).isEqualTo(new InvitedTripListResponse(12L, List.of(), false, null));
        verify(shares).findInvitedPage(42L, NOW, START_DATE, 50L, PageRequest.of(0, 8));
        verifyNoInteractions(regions, attachments, storage);
    }

    @Test
    void 초대가_없으면_전체_개수도_0인_빈_목록이다() {
        when(shares.findInvitedPage(42L, NOW, null, null, PageRequest.of(0, 8))).thenReturn(List.of());

        assertThat(service.findInvitedTrips(42L, null))
                .isEqualTo(new InvitedTripListResponse(0L, List.of(), false, null));
        verifyNoInteractions(regions, attachments, storage);
    }

    @Test
    void 권한과_지역_요약과_사진_수와_썸네일을_조립한다() {
        givenPage(8);
        when(regions.findNamesByTripIds(anyList())).thenReturn(List.of(
                new TripRegionName(99L, "파리"), new TripRegionName(99L, "런던"),
                new TripRegionName(99L, "로마"), new TripRegionName(99L, "베를린")));
        when(attachments.countNotDeletedByTripIds(anyList()))
                .thenReturn(List.of(new TripAttachmentCount(99L, 341L)));
        when(storage.createReadUrl("thumbnail-99")).thenReturn("https://example.test/99");

        InvitedTripListResponse response = service.findInvitedTrips(42L, null);

        assertThat(response.items().getFirst()).isEqualTo(new InvitedTripListResponse.Item(
                99L, "공유여행", START_DATE, START_DATE.plusDays(7), "파리, 런던, 로마",
                341L, "https://example.test/99", TripSharePermission.READ));
        assertThat(response.items().get(1)).isEqualTo(new InvitedTripListResponse.Item(
                98L, "공유여행", START_DATE, START_DATE.plusDays(7), "",
                0L, null, TripSharePermission.WRITE));
        verify(storage, never()).createReadUrl("thumbnail-92");
        verify(storage, never()).createReadUrl(" ");
    }

    @Test
    void 잘못된_커서는_DB_조회_전에_거절한다() {
        assertThatThrownBy(() -> service.findInvitedTrips(42L, "invalid!"))
                .isInstanceOf(InvalidCursorException.class);
        verifyNoInteractions(shares, regions, attachments, storage);
    }

    @Test
    void 서명_URL_오류를_빈_목록으로_숨기지_않는다() {
        givenPage(1);
        IllegalStateException failure = new IllegalStateException("signing failed");
        when(storage.createReadUrl("thumbnail-99")).thenThrow(failure);

        assertThatThrownBy(() -> service.findInvitedTrips(42L, null)).isSameAs(failure);
    }

    private void givenPage(int count) {
        List<TripShareUser> page = LongStream.rangeClosed(1L, count)
                .mapToObj((long index) -> share(100L - index))
                .toList();
        when(shares.countInvitedTrips(42L, NOW)).thenReturn(12L);
        when(shares.findInvitedPage(42L, NOW, null, null, PageRequest.of(0, 8))).thenReturn(page);
    }

    private TripShareUser share(long tripId) {
        Trip trip = new Trip(1L, "공유여행", START_DATE, START_DATE.plusDays(7));
        ReflectionTestUtils.setField(trip, "id", tripId);
        trip.changeThumbnailKey(tripId == 98L ? " " : "thumbnail-" + tripId);
        TripSharePermission permission = tripId == 99L ? TripSharePermission.READ : TripSharePermission.WRITE;
        TripShareUser share = new TripShareUser(tripId, 1L, 42L, permission, NOW.plusHours(1), NOW);
        ReflectionTestUtils.setField(share, "trip", trip);
        return share;
    }
}
