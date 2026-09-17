package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.PlaceClient;
import com.yeodam.yeodambe.trip.service.request.PlaceSearchRequest;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidateResponse;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidatesResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlaceService {
    private static final int PAGE_SIZE = 10;

    private final PlaceClient placeClient;


    public PlaceCandidatesResponse search( PlaceSearchRequest request) {
        LinkedHashMap<String, PlaceCandidateResponse> candidates = new LinkedHashMap<>();

        int pageNo = request.requestPageNo();

        while (true) {
            PlaceClient.ProviderPage page = placeClient.search(request.query(), pageNo);

            page.rows().stream()
                    .filter(this::isSigungu)
                    .map(this::toResponse)
                    .forEach(
                            candidate -> candidates
                            .putIfAbsent(
                                    candidate.regionCode(),
                                    candidate
                                    )
                    );
            int lastPage = (page.totalCount() + PAGE_SIZE - 1) / PAGE_SIZE;

            if (pageNo >= lastPage) {
                break;
            }
            pageNo++;
        }
        return new PlaceCandidatesResponse(List.copyOf(candidates.values()));
    }

    private boolean isSigungu(PlaceClient.ProviderRegion region) {
        return isNumericCode(region.sidoCd(), 2)
                && isNumericCode(region.sggCd(), 3)
                && !"000".equals(region.sggCd())
                && "000".equals(region.umdCd())
                && "00".equals(region.riCd())
                && region.regionName() != null
                && !region.regionName().isBlank();
    }

    private boolean isNumericCode(String value, int length) {
        return value != null
                && value.length() == length
                && value.chars().allMatch(Character::isDigit);
    }

    private PlaceCandidateResponse toResponse(PlaceClient.ProviderRegion region) {
        return new PlaceCandidateResponse(
                region.sidoCd() + region.sggCd(),
                region.regionName()
        );
    }
}
