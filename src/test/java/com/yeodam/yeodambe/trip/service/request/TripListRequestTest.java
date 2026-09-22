package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidTripListFilterException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TripListRequestTest {

    @Test
    void 생략한_필터는_최신순과_즐겨찾기_우선_미사용으로_처리한다() {
        TripListRequest request = TripListRequest.from(null, null, null);

        assertThat(request.sort()).isEqualTo(TripSort.LATEST);
        assertThat(request.favorite()).isFalse();
        assertThat(request.cursor()).isNull();
    }

    @Test
    void 허용하지_않은_정렬과_boolean_문자열은_거부한다() {
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(null, "NEWEST", null));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(null, null, "yes"));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(null, null, "TRUE"));
    }

    @Test
    void 커서는_목록_재개에_필요한_값을_보존한다() {
        TripListCursor cursor = new TripListCursor(
                TripSort.OLDEST,
                true,
                false,
                LocalDateTime.of(2026, 9, 22, 12, 30, 45, 123_000_000),
                17L
        );

        TripListCursor decoded = TripListCursor.decode(cursor.encode());

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void 비어있거나_깨진_커서와_현재_필터가_다른_커서는_거부한다() {
        String latestCursor = new TripListCursor(
                TripSort.LATEST,
                false,
                true,
                LocalDateTime.of(2026, 9, 22, 12, 30),
                17L
        ).encode();

        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from("", null, null));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from("not-a-cursor", null, null));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(latestCursor, "OLDEST", "false"));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(latestCursor, "LATEST", "true"));
    }
}
