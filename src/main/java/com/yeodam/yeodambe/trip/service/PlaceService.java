package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.service.request.PlaceSearchRequest;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidateResponse;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidatesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;

@Service
@RequiredArgsConstructor
public class PlaceService {
    private static final int PAGE_SIZE = 10;
    private final PlaceSearchCatalog catalog;
    private final RegionCatalog regionCatalog;

    public PlaceCandidatesResponse search(PlaceSearchRequest request) {
        var items = catalog.entries().stream()
                .filter(entry -> entry.searchName().contains(request.query()))
                .map(PlaceSearchCatalog.Entry::regionCode)
                .distinct()
                .map(regionCatalog::getRequired)
                .sorted(Comparator.comparing(RegionCatalog.Region::name)
                        .thenComparing(RegionCatalog.Region::code))
                .skip((long) (request.requestPageNo() - 1) * PAGE_SIZE)
                .limit(PAGE_SIZE)
                .map(region -> new PlaceCandidateResponse(region.code(), region.name()))
                .toList();
        return new PlaceCandidatesResponse(items);
    }
}
