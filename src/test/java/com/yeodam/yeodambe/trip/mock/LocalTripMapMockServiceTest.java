package com.yeodam.yeodambe.trip.mock;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.AttachmentIssue;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.TripDetailPlace;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.RegionCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LocalTripMapMockServiceTest {
    private TripRepository tripRepository;
    private TripRegionRepository tripRegionRepository;
    private TripDetailPlaceRepository tripDetailPlaceRepository;
    private StoredFileRepository storedFileRepository;
    private TripAttachmentRepository tripAttachmentRepository;
    private RegionCatalog regionCatalog;
    private LocalTripMapMockService service;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        tripRegionRepository = mock(TripRegionRepository.class);
        tripDetailPlaceRepository = mock(TripDetailPlaceRepository.class);
        storedFileRepository = mock(StoredFileRepository.class);
        tripAttachmentRepository = mock(TripAttachmentRepository.class);
        regionCatalog = mock(RegionCatalog.class);
        service = new LocalTripMapMockService(
                tripRepository,
                tripRegionRepository,
                tripDetailPlaceRepository,
                storedFileRepository,
                tripAttachmentRepository,
                regionCatalog
        );

        when(regionCatalog.getRequired(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            return new RegionCatalog.Region(
                    code,
                    "목업 지역 " + code,
                    new BigDecimal("35.00000000"),
                    new BigDecimal("127.00000000")
            );
        });
        when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> {
            Trip trip = invocation.getArgument(0);
            ReflectionTestUtils.setField(trip, "id", 100L);
            return trip;
        });
        when(storedFileRepository.save(any(StoredFile.class))).thenAnswer(invocation -> {
            StoredFile file = invocation.getArgument(0);
            ReflectionTestUtils.setField(file, "id", 200L);
            return file;
        });
        AtomicLong placeId = new AtomicLong();
        when(tripDetailPlaceRepository.save(any())).thenAnswer(invocation -> {
            var place = invocation.getArgument(0);
            ReflectionTestUtils.setField(place, "id", placeId.incrementAndGet());
            return place;
        });
    }

    @Test
    void 목록_페이지네이션과_즐겨찾기_정렬을_확인할_목업을_만든다() {
        List<Trip> savedTrips = new ArrayList<>();
        when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> {
            Trip trip = invocation.getArgument(0);
            ReflectionTestUtils.setField(trip, "id", 100L);
            savedTrips.add(trip);
            return trip;
        });

        service.createIfMissing(1L);

        assertEquals(9, savedTrips.size());
        assertEquals(2, savedTrips.stream().filter(Trip::getFavorite).count());
        verify(tripRegionRepository, times(9)).saveAll(anyList());
        verify(storedFileRepository, times(41)).save(any(StoredFile.class));
        verify(tripRepository, times(9)).finishInitialUpload(
                anyLong(),
                eq(1L),
                eq(ProcessingStatus.PROCESSING),
                eq(ProcessingStatus.COMPLETED)
        );

        ArgumentCaptor<TripDetailPlace> places = ArgumentCaptor.forClass(TripDetailPlace.class);
        verify(tripDetailPlaceRepository, times(17)).save(places.capture());
        assertEquals(
                List.of(
                        "경복궁", "북촌한옥마을", "남산서울타워", "성수동",
                        "해운대", "감천문화마을", "광화문", "익선동",
                        "해운대", "광안리", "서문시장", "차이나타운",
                        "송도센트럴파크", "서울숲", "해운대", "동성로", "경복궁"
                ),
                places.getAllValues().stream().map(TripDetailPlace::getPlaceName).toList()
        );

        ArgumentCaptor<List<TripAttachment>> attachments = ArgumentCaptor.forClass(List.class);
        verify(tripAttachmentRepository, times(10)).saveAll(attachments.capture());
        List<TripAttachment> savedAttachments = attachments.getAllValues().stream()
                .flatMap(List::stream)
                .toList();
        assertEquals(41, savedAttachments.size());
        assertEquals(17, savedAttachments.stream()
                .filter(attachment -> attachment.getClassificationStatus() == ClassificationStatus.ACTIVE)
                .filter(attachment -> attachment.getTripPlaceId() != null)
                .count());
    }

    @Test
    void 기존_목업이_있어도_미분류_여행을_추가하고_사유별_사진과_복구_장소를_저장한다() {
        when(tripRepository.existsByUserIdAndTripNameAndDeletedAtIsNull(
                eq(1L),
                anyString()
        )).thenAnswer(invocation -> !"목업미분류".equals(invocation.getArgument(1)));

        service.createIfMissing(1L);

        ArgumentCaptor<Trip> tripCaptor = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository).save(tripCaptor.capture());
        Trip trip = tripCaptor.getValue();
        assertEquals("목업미분류", trip.getTripName());
        assertEquals(1L, trip.getUserId());

        ArgumentCaptor<TripDetailPlace> placeCaptor = ArgumentCaptor.forClass(TripDetailPlace.class);
        verify(tripDetailPlaceRepository).save(placeCaptor.capture());
        Long restorePlaceId = placeCaptor.getValue().getId();

        ArgumentCaptor<List<TripAttachment>> attachmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(tripAttachmentRepository, times(2)).saveAll(attachmentCaptor.capture());
        List<TripAttachment> unclassified = attachmentCaptor.getAllValues().stream()
                .flatMap(List::stream)
                .filter(attachment -> attachment.getClassificationStatus() == ClassificationStatus.UNCLASSIFIED)
                .toList();
        assertEquals(24, unclassified.size());
        assertTrue(unclassified.stream().allMatch(attachment ->
                trip.getId().equals(attachment.getTripId())
                        && "local-map-mock/seoul.png".equals(attachment.getPreviewStorageKey())));

        List<TripAttachment> unclear = unclassified.stream()
                .filter(attachment -> attachment.getIssue() == AttachmentIssue.UNCLEAR_LOCATION)
                .toList();
        assertEquals(19, unclear.size());
        assertTrue(unclear.stream().allMatch(attachment -> attachment.getTripPlaceId() == null));
        assertEquals(3, unclassified.stream()
                .filter(attachment -> attachment.getIssue() == AttachmentIssue.BLURRY)
                .count());
        assertEquals(2, unclassified.stream()
                .filter(attachment -> attachment.getIssue() == AttachmentIssue.DUPLICATED)
                .count());
        assertTrue(unclassified.stream()
                .filter(attachment -> attachment.getIssue() != AttachmentIssue.UNCLEAR_LOCATION)
                .allMatch(attachment -> restorePlaceId.equals(attachment.getTripPlaceId())));

        ArgumentCaptor<StoredFile> fileCaptor = ArgumentCaptor.forClass(StoredFile.class);
        verify(storedFileRepository, times(25)).save(fileCaptor.capture());
        assertTrue(fileCaptor.getAllValues().stream().allMatch(file ->
                "local-map-mock/seoul.png".equals(file.getObjectKey())));
        assertEquals(25, fileCaptor.getAllValues().stream()
                .map(StoredFile::getOriginalFileName)
                .distinct()
                .count());
    }

    @Test
    void 같은_이름의_목업_여행이_이미_있으면_다시_저장하지_않는다() {
        when(tripRepository.existsByUserIdAndTripNameAndDeletedAtIsNull(
                eq(1L),
                anyString()
        )).thenReturn(true);

        service.createIfMissing(1L);

        verify(tripRepository, never()).save(any(Trip.class));
        verifyNoInteractions(
                tripRegionRepository,
                tripDetailPlaceRepository,
                storedFileRepository,
                tripAttachmentRepository
        );
        verify(tripRepository, never()).finishInitialUpload(
                anyLong(),
                anyLong(),
                any(ProcessingStatus.class),
                any(ProcessingStatus.class)
        );
    }
}
