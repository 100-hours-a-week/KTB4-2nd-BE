package com.yeodam.yeodambe.trip.mock;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.RegionOrigin;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.RegionCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Profile("local")
@Service
@RequiredArgsConstructor
public class LocalTripMapMockService {
    private static final String ASSET_KEY_PREFIX = "local-map-mock/";

    private final TripRepository tripRepository;
    private final TripRegionRepository tripRegionRepository;
    private final TripDetailPlaceRepository tripDetailPlaceRepository;
    private final StoredFileRepository storedFileRepository;
    private final TripAttachmentRepository tripAttachmentRepository;
    private final RegionCatalog regionCatalog;

    @Transactional
    public void createIfMissing(Long userId) {
        createIfMissing(userId, new MockTrip(
                "목업서울1",
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 3),
                List.of("11000"),
                "seoul.png",
                List.of("경복궁", "북촌한옥마을", "남산서울타워"),
                true
        ));
        createIfMissing(userId, new MockTrip(
                "목업서울2",
                LocalDate.of(2026, 4, 10),
                LocalDate.of(2026, 4, 12),
                List.of("11000"),
                "seoul.png",
                List.of("성수동"),
                false
        ));
        createIfMissing(userId, new MockTrip(
                "목업광주",
                LocalDate.of(2026, 5, 5),
                LocalDate.of(2026, 5, 7),
                List.of("12000"),
                "seoul.png",
                List.of(),
                false
        ));
        createIfMissing(userId, new MockTrip(
                "목업부산",
                LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 6, 18),
                List.of("26000"),
                "busan.png",
                List.of("해운대", "감천문화마을"),
                true
        ));
        createIfMissing(userId, new MockTrip(
                "목업서울부산",
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 24),
                List.of("11000", "26000"),
                "busan.png",
                List.of("광화문", "익선동", "해운대", "광안리"),
                false
        ));
        createIfMissing(userId, new MockTrip(
                "목업대구",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 2),
                List.of("27000"),
                "seoul.png",
                List.of("서문시장"),
                false
        ));
        createIfMissing(userId, new MockTrip(
                "목업인천",
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12),
                List.of("28000"),
                "busan.png",
                List.of("차이나타운", "송도센트럴파크"),
                false
        ));
        createIfMissing(userId, new MockTrip(
                "목업전국여행",
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 24),
                List.of("11000", "26000", "27000"),
                "seoul.png",
                List.of("서울숲", "해운대", "동성로"),
                false
        ));
    }

    private void createIfMissing(Long userId, MockTrip mockTrip) {
        if (tripRepository.existsByUserIdAndTripNameAndDeletedAtIsNull(
                userId,
                mockTrip.tripName()
        )) {
            return;
        }

        String assetKey = ASSET_KEY_PREFIX + mockTrip.thumbnailFileName();
        Trip trip = Trip.localMock(
                userId,
                mockTrip.tripName(),
                mockTrip.startDate(),
                mockTrip.endDate(),
                assetKey
        );
        trip.changeFavorite(mockTrip.favorite());
        tripRepository.save(trip);

        List<TripRegion> regions = mockTrip.regionCodes().stream()
                .map(regionCatalog::getRequired)
                .map(region -> new TripRegion(
                        trip,
                        region.code(),
                        region.name(),
                        region.latitude(),
                        region.longitude()
                ))
                .toList();

        tripRegionRepository.saveAll(regions);
        createPlaceFolders(userId, trip, regions, assetKey, mockTrip.placeNames());

        tripRepository.finishInitialUpload(
                trip.getId(),
                userId,
                ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED
        );
    }

    private void createPlaceFolders(
            Long userId,
            Trip trip,
            List<TripRegion> regions,
            String assetKey,
            List<String> placeNames
    ) {
        List<TripAttachment> attachments = new ArrayList<>();
        TripRegion region = regions.getFirst();

        for (int index = 1; index <= placeNames.size(); index++) {
            var takenAt = trip.getStartDate().atTime(9 + index, 0);
            TripDetailPlace place = tripDetailPlaceRepository.save(TripDetailPlace.localMock(
                    trip.getId(),
                    index,
                    placeNames.get(index - 1),
                    region.getLatitude(),
                    region.getLongitude(),
                    takenAt,
                    assetKey
            ));
            StoredFile file = storedFileRepository.save(StoredFile.uploaded(
                    userId,
                    "map-mock-" + trip.getId() + "-" + index + ".png",
                    assetKey,
                    "image/png"
            ));

            TripAttachment attachment = TripAttachment.initial(
                    trip.getId(),
                    file.getId(),
                    assetKey,
                    assetKey
            );
            attachment.classify(
                    place.getId(),
                    RegionOrigin.EXIF,
                    takenAt,
                    region.getLatitude(),
                    region.getLongitude(),
                    100
            );
            attachments.add(attachment);
        }

        tripAttachmentRepository.saveAll(attachments);
    }

    private record MockTrip(
            String tripName,
            LocalDate startDate,
            LocalDate endDate,
            List<String> regionCodes,
            String thumbnailFileName,
            List<String> placeNames,
            boolean favorite
    ) {
    }
}
