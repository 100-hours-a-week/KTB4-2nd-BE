package com.yeodam.yeodambe.trip.service.request;

import java.time.LocalDate;
import java.util.List;

public record TripSearchCondition(
        LocalDate dateFrom,
        LocalDate dateTo,
        List<String> regionNames
) {
}
