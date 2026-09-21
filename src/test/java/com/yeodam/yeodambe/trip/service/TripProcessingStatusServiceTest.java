package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.integration.service.response.TripPhotoAnalysisStatusResponse;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.ProcessingStatus;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.trip.repository.TripDetailPlaceRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TripProcessingStatusServiceTest {
    private TripRepository trips;
    private TripDetailPlaceRepository places;
    private TripAttachmentRepository attachments;
    private TripPhotoAnalysisService analysis;
    private InitialUploadExecutionRegistry executions;
    private TripProcessingStatusService service;

    @BeforeEach
    void setUp() {
        trips = mock(TripRepository.class);
        places = mock(TripDetailPlaceRepository.class);
        attachments = mock(TripAttachmentRepository.class);
        analysis = mock(TripPhotoAnalysisService.class);
        executions = mock(InitialUploadExecutionRegistry.class);
        service = new TripProcessingStatusService(trips, places, attachments, analysis, executions);
    }

    @Test
    void 소유하지_않은_여행은_없는_여행과_같이_처리한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L)).thenReturn(Optional.empty());

        assertThrows(TripNotFoundException.class, () -> service.findStatus(7L, 1L));
        verifyNoInteractions(analysis);
    }

    @Test
    void 완료된_여행은_AI를_조회하지_않고_DB를_집계한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.COMPLETED)));
        when(places.countByTripIdAndDeletedAtIsNull(7L)).thenReturn(4L);
        when(attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                7L, ClassificationStatus.ACTIVE)).thenReturn(11L);
        when(attachments.countByTripIdAndDeletedAtIsNullAndClassificationStatus(
                7L, ClassificationStatus.UNCLASSIFIED)).thenReturn(2L);

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(response.progress().done()).isEqualTo(13);
        assertThat(response.result().placeFolderCount()).isEqualTo(4);
        assertThat(response.result().classifiedAttachmentCount()).isEqualTo(11);
        assertThat(response.result().unclassifiedAttachmentCount()).isEqualTo(2);
        verifyNoInteractions(analysis);
    }

    @Test
    void AI_분석_시작_전에는_진행_수치를_만들지_않는다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(response.progress()).isNull();
        assertThat(response.currentStep()).isNull();
        verifyNoInteractions(analysis);
    }

    @Test
    void AI가_처리중이면_검증된_진행_정보를_전달한다() {
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(trip(ProcessingStatus.PROCESSING)));
        when(executions.isAnalysisStarted(7L)).thenReturn(true);
        when(analysis.findStatus(7L)).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L,
                TripPhotoAnalysisStatusResponse.Status.PROCESSING,
                new TripPhotoAnalysisStatusResponse.Progress(2, 5),
                "EMBEDDING",
                null,
                null
        ));

        var response = service.findStatus(7L, 1L);

        assertThat(response.progress().done()).isEqualTo(2);
        assertThat(response.progress().total()).isEqualTo(5);
        assertThat(response.currentStep()).isEqualTo("EMBEDDING");
    }

    @Test
    void AI가_끝나도_DB가_처리중이면_완료를_노출하지_않는다() {
        Trip processing = trip(ProcessingStatus.PROCESSING);
        when(trips.findByIdAndUserIdAndDeletedAtIsNull(7L, 1L))
                .thenReturn(Optional.of(processing));
        when(executions.isAnalysisStarted(7L)).thenReturn(true);
        when(analysis.findStatus(7L)).thenReturn(new TripPhotoAnalysisStatusResponse(
                7L,
                TripPhotoAnalysisStatusResponse.Status.COMPLETED,
                new TripPhotoAnalysisStatusResponse.Progress(5, 5),
                null,
                new ObjectMapper().createObjectNode(),
                null
        ));

        var response = service.findStatus(7L, 1L);

        assertThat(response.status()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(response.progress()).isNull();
        assertThat(response.result()).isNull();
        verify(trips, times(2)).findByIdAndUserIdAndDeletedAtIsNull(7L, 1L);
    }

    private Trip trip(ProcessingStatus status) {
        Trip trip = new Trip(1L, "여행", LocalDate.now(), LocalDate.now());
        ReflectionTestUtils.setField(trip, "id", 7L);
        ReflectionTestUtils.setField(trip, "processingStatus", status);
        return trip;
    }
}
