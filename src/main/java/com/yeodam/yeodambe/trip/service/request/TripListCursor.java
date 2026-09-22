package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidTripListFilterException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

public record TripListCursor(
        TripSort sort,
        boolean favorite,
        boolean favoriteGroup,
        LocalDateTime createdAt,
        long tripId
) {
    public TripListCursor {
        if (sort == null || createdAt == null || tripId <= 0) {
            throw new InvalidTripListFilterException();
        }
    }

    public String encode() {
        String value = String.join(
                "|",
                sort.name(),
                Boolean.toString(favorite),
                Boolean.toString(favoriteGroup),
                createdAt.toString(),
                Long.toString(tripId)
        );
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static TripListCursor decode(String value) {
        try {
            if (value == null || value.isBlank()) {
                throw new InvalidTripListFilterException();
            }
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value),
                    StandardCharsets.UTF_8
            );
            String[] fields = decoded.split("\\|", -1);
            if (fields.length != 5) {
                throw new InvalidTripListFilterException();
            }
            return new TripListCursor(
                    TripSort.valueOf(fields[0]),
                    parseBoolean(fields[1]),
                    parseBoolean(fields[2]),
                    LocalDateTime.parse(fields[3]),
                    Long.parseLong(fields[4])
            );
        } catch (InvalidTripListFilterException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidTripListFilterException();
        }
    }

    private static boolean parseBoolean(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new InvalidTripListFilterException();
        }
        return Boolean.parseBoolean(value);
    }
}
