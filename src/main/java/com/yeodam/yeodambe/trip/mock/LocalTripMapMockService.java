package com.yeodam.yeodambe.trip.mock;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripRegion;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
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
                3
        ));
        createIfMissing(userId, new MockTrip(
                "목업서울2",
                LocalDate.of(2026, 4, 10),
                LocalDate.of(2026, 4, 12),
                List.of("11000"),
                "seoul.png",
                1
        ));
        createIfMissing(userId, new MockTrip(
                "목업광주",
                LocalDate.of(2026, 5, 5),
                LocalDate.of(2026, 5, 7),
                List.of("12000"),
                "seoul.png",
                0
        ));
        createIfMissing(userId, new MockTrip(
                "목업부산",
                LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 6, 18),
                List.of("26000"),
                "busan.png",
                2
        ));
        createIfMissing(userId, new MockTrip(
                "목업서울부산",
                LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 24),
                List.of("11000", "26000"),
                "busan.png",
                4
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
        Trip trip = tripRepository.save(Trip.localMock(
                userId,
                mockTrip.tripName(),
                mockTrip.startDate(),
                mockTrip.endDate(),
                assetKey
        ));

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
        createAttachments(userId, trip.getId(), assetKey, mockTrip.attachmentCount());

        tripRepository.finishInitialUpload(
                trip.getId(),
                userId,
                ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED
        );
    }

    private void createAttachments(
            Long userId,
            Long tripId,
            String assetKey,
            int attachmentCount
    ) {
        List<TripAttachment> attachments = new ArrayList<>();

        for (int index = 1; index <= attachmentCount; index++) {
            StoredFile file = storedFileRepository.save(StoredFile.uploaded(
                    userId,
                    "map-mock-" + tripId + "-" + index + ".png",
                    assetKey,
                    "image/png"
            ));

            attachments.add(TripAttachment.initial(
                    tripId,
                    file.getId(),
                    assetKey,
                    assetKey
            ));
        }

        tripAttachmentRepository.saveAll(attachments);
    }

    private record MockTrip(
            String tripName,
            LocalDate startDate,
            LocalDate endDate,
            List<String> regionCodes,
            String thumbnailFileName,
            int attachmentCount
    ) {
    }
}
