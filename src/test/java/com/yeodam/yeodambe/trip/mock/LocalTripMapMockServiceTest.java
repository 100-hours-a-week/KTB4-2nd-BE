package com.yeodam.yeodambe.trip.mock;

import com.yeodam.yeodambe.file.entity.StoredFile;
import com.yeodam.yeodambe.file.repository.StoredFileRepository;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripRegionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.RegionCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LocalTripMapMockServiceTest {
    private TripRepository tripRepository;
    private TripRegionRepository tripRegionRepository;
    private StoredFileRepository storedFileRepository;
    private TripAttachmentRepository tripAttachmentRepository;
    private RegionCatalog regionCatalog;
    private LocalTripMapMockService service;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        tripRegionRepository = mock(TripRegionRepository.class);
        storedFileRepository = mock(StoredFileRepository.class);
        tripAttachmentRepository = mock(TripAttachmentRepository.class);
        regionCatalog = mock(RegionCatalog.class);
        service = new LocalTripMapMockService(
                tripRepository,
                tripRegionRepository,
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
    }

    @Test
    void 목업_여행과_지역과_첨부를_만들고_완료_상태로_변경한다() {
        service.createIfMissing(1L);

        verify(tripRepository, times(5)).save(any(Trip.class));
        verify(tripRegionRepository, times(5)).saveAll(anyList());
        verify(storedFileRepository, times(10)).save(any(StoredFile.class));
        verify(tripAttachmentRepository, times(5)).saveAll(anyList());
        verify(tripRepository, times(5)).finishInitialUpload(
                anyLong(),
                eq(1L),
                eq(ProcessingStatus.PROCESSING),
                eq(ProcessingStatus.COMPLETED)
        );
    }

    @Test
    void 같은_이름의_목업_여행이_이미_있으면_다시_저장하지_않는다() {
        when(tripRepository.existsByUserIdAndTripNameAndDeletedAtIsNull(
                eq(1L),
                anyString()
        )).thenReturn(true);

        service.createIfMissing(1L);

        verify(tripRepository, never()).save(any(Trip.class));
        verifyNoInteractions(tripRegionRepository, storedFileRepository, tripAttachmentRepository);
        verify(tripRepository, never()).finishInitialUpload(
                anyLong(),
                anyLong(),
                any(ProcessingStatus.class),
                any(ProcessingStatus.class)
        );
    }
}
