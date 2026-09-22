package com.yeodam.yeodambe.trip.service.request;

import com.yeodam.yeodambe.common.exception.InvalidTripListFilterException;

public record TripListRequest(
        TripListCursor cursor,
        TripSort sort,
        boolean favorite
) {
    public static TripListRequest from(String cursor, String sort, String favorite) {
        TripSort requestedSort = parseSort(sort);
        boolean requestedFavorite = parseFavorite(favorite);
        TripListCursor decodedCursor = cursor == null ? null : TripListCursor.decode(cursor);

        if (decodedCursor != null
                && (decodedCursor.sort() != requestedSort
                || decodedCursor.favorite() != requestedFavorite)) {
            throw new InvalidTripListFilterException();
        }
        return new TripListRequest(decodedCursor, requestedSort, requestedFavorite);
    }

    private static TripSort parseSort(String value) {
        if (value == null) {
            return TripSort.LATEST;
        }
        try {
            return TripSort.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidTripListFilterException();
        }
    }

    private static boolean parseFavorite(String value) {
        if (value == null) {
            return false;
        }
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new InvalidTripListFilterException();
        }
        return Boolean.parseBoolean(value);
    }
}
