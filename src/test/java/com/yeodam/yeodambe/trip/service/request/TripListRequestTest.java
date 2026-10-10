package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidTripListFilterException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TripListRequestTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 여행_목록_커서를_JSON으로_발급한다() {
        TripListCursor cursor = new TripListCursor(
                TripSort.LATEST, false, true, LocalDate.of(2026, 9, 22), 17L
        );

        var node = objectMapper.readTree(Base64.getUrlDecoder().decode(cursor.encode(objectMapper)));

        assertThat(node.isObject()).isTrue();
        assertThat(node.get("tripId").asLong()).isEqualTo(17L);
    }

    @Test
    void 생략한_필터는_최신순과_즐겨찾기_우선_미사용으로_처리한다() {
        TripListRequest request = TripListRequest.from(null, null, null, objectMapper);

        assertThat(request.sort()).isEqualTo(TripSort.LATEST);
        assertThat(request.favorite()).isFalse();
        assertThat(request.cursor()).isNull();
    }

    @Test
    void 허용하지_않은_정렬과_boolean_문자열은_거부한다() {
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(null, "NEWEST", null, objectMapper));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(null, null, "yes", objectMapper));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(null, null, "TRUE", objectMapper));
    }

    @Test
    void 커서는_목록_재개에_필요한_값을_보존한다() {
        TripListCursor cursor = new TripListCursor(
                TripSort.OLDEST,
                true,
                false,
                LocalDate.of(2026, 9, 22),
                17L
        );

        TripListCursor decoded = TripListCursor.decode(cursor.encode(objectMapper), objectMapper);

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void 비어있거나_깨진_커서와_현재_필터가_다른_커서는_거부한다() {
        String latestCursor = new TripListCursor(
                TripSort.LATEST,
                false,
                true,
                LocalDate.of(2026, 9, 22),
                17L
        ).encode(objectMapper);

        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from("", null, null, objectMapper));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from("not-a-cursor", null, null, objectMapper));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(latestCursor, "OLDEST", "false", objectMapper));
        assertThrows(InvalidTripListFilterException.class,
                () -> TripListRequest.from(latestCursor, "LATEST", "true", objectMapper));
    }
}
