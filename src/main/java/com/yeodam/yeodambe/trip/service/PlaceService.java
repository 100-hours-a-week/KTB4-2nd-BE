package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.client.PlaceClient;
import com.yeodam.yeodambe.trip.service.request.PlaceSearchRequest;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidateResponse;
import com.yeodam.yeodambe.trip.service.response.PlaceCandidatesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlaceService {
    private static final int PAGE_SIZE = 10;

    private final PlaceClient placeClient;
    private final RegionCatalog regionCatalog;


    public PlaceCandidatesResponse search( PlaceSearchRequest request) {
        LinkedHashMap<String, PlaceCandidateResponse> candidates = new LinkedHashMap<>();

        int pageNo = request.requestPageNo();

        while (true) {
            PlaceClient.ProviderPage page = placeClient.search(request.query(), pageNo);

            for (PlaceClient.ProviderRegion row : page.rows()) {
                if (!isRegionRow(row)) {
                    continue;
                }

                String name = row.regionName();

                if (name.endsWith("구")) {
                    int lastSpace = name.lastIndexOf(' ');
                    if (lastSpace < 0) {
                        continue;
                    }

                    name = name.substring(0, lastSpace);
                }

                RegionCatalog.Region region = regionCatalog.findByName(name);

                if (
                        region != null && region.code().startsWith(row.sidoCd())
                        && (row.regionName().endsWith("구")
                        || region.code().equals(row.sidoCd() + row.sggCd()))
                ) {
                    candidates.putIfAbsent(
                            region.code(),
                            new PlaceCandidateResponse(region.code(), region.name())
                    );
                }
            }
            int lastPage = (page.totalCount() + PAGE_SIZE - 1) / PAGE_SIZE;

            if (pageNo >= lastPage) {
                break;
            }
            pageNo++;
        }
        return new PlaceCandidatesResponse(List.copyOf(candidates.values()));
    }

    private boolean isRegionRow(PlaceClient.ProviderRegion region) {
        return isNumericCode(region.sidoCd(), 2)
                && isNumericCode(region.sggCd(), 3)
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

}
