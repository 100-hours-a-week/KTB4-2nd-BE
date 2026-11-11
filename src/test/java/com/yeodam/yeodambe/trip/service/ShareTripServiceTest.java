package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.InvalidCursorException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.fixture.TripMutationTestFixture;
import com.yeodam.yeodambe.trip.repository.TripShareUserRepository;
import com.yeodam.yeodambe.trip.service.request.cursor.TripShareCursor;
import com.yeodam.yeodambe.trip.service.response.TripShareListResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.service.ShareUserSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShareTripServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 15, 0);
    private final TripRepository trips = mock(TripRepository.class);
    private final TripShareUserRepository shares = mock(TripShareUserRepository.class);
    private final ShareUserSearchService users = mock(ShareUserSearchService.class);
    private final TripAccessService access = mock(TripAccessService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T06:00:00Z"), ZoneOffset.UTC);
    private final ShareTripService service = new ShareTripService(
            trips, shares, users, clock, access, objectMapper, TripMutationTestFixture.direct()
    );

    @BeforeEach
    void prepare() {
        Trip trip = new Trip(1L, "공유여행", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2));
        ReflectionTestUtils.setField(trip, "id", 7L);
        when(access.requireOwnedTrip(7L, 1L)).thenReturn(trip);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 5, 6})
    void 여섯_개를_조회하면_다섯_개와_마지막_반환_ID의_커서를_반환한다(int count) {
        List<TripShareUser> fetched = IntStream.rangeClosed(1, count)
                .mapToObj(this::share)
                .toList();
        when(shares.findActiveSharesWithGrantee(7L, 1L, NOW, 0L, PageRequest.of(0, 6)))
                .thenReturn(fetched);

        TripShareListResponse response = service.findShares(7L, 1L, null);

        verify(shares).findActiveSharesWithGrantee(7L, 1L, NOW, 0L, PageRequest.of(0, 6));
        assertThat(response.items()).hasSize(Math.min(count, 5));
        assertThat(response.items()).extracting(TripShareListResponse.Item::tripShareId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, Math.min(count, 5))
                        .mapToObj(Long::valueOf).toList());
        assertThat(response.hasNext()).isEqualTo(count == 6);
        if (count == 6) {
            assertThat(TripShareCursor.decode(response.nextCursor(), 7L, objectMapper))
                    .isEqualTo(new TripShareCursor(7L, 5L));
        } else {
            assertThat(response.nextCursor()).isNull();
        }
        verifyNoInteractions(trips, users);
    }

    @Test
    void 다음_커서와_서울_시각으로_조회하고_대상자_정보를_매핑한다() {
        String cursor = new TripShareCursor(7L, 5L).encode(objectMapper);
        when(shares.findActiveSharesWithGrantee(7L, 1L, NOW, 5L, PageRequest.of(0, 6)))
                .thenReturn(List.of(share(6)));

        TripShareListResponse response = service.findShares(7L, 1L, cursor);

        assertThat(response.items()).containsExactly(new TripShareListResponse.Item(
                6L, 106L, "friend6@test.com", TripSharePermission.WRITE,
                OffsetDateTime.parse("2026-11-03T15:00:00+09:00")));
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 소유권_검증_실패는_커서_검증과_목록_조회보다_우선한다() {
        when(access.requireOwnedTrip(7L, 2L)).thenThrow(new TripNotFoundException());

        assertThatThrownBy(() -> service.findShares(7L, 2L, "broken!"))
                .isInstanceOf(TripNotFoundException.class);

        verifyNoInteractions(shares);
    }

    @Test
    void 잘못된_커서로_목록을_조회하지_않는다() {
        assertThatThrownBy(() -> service.findShares(7L, 1L, "broken!"))
                .isInstanceOf(InvalidCursorException.class);

        verifyNoInteractions(shares);
    }

    private TripShareUser share(int index) {
        TripSharePermission permission = index % 2 == 0 ? TripSharePermission.WRITE : TripSharePermission.READ;
        TripShareUser share = new TripShareUser(7L, 1L, 100L + index, permission, NOW.plusDays(1), NOW);
        ReflectionTestUtils.setField(share, "id", (long) index);
        ReflectionTestUtils.setField(share, "grantee", new User("friend" + index + "@test.com", "친구"));
        return share;
    }
}
