package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.InitialUploadExecutionRegistry;
import com.yeodam.yeodambe.trip.service.TripAnalysisResultService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripAnalysisResultServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final InitialUploadExecutionRegistry executions = new InitialUploadExecutionRegistry();
    private final TripAnalysisResultService service = new TripAnalysisResultService(
            trips, attachments, places, executions);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 모든_사진이_결과에_있고_실행_ID가_일치해야_완료한다() {
        String executionId = executions.reserve(7L);
        TripAttachment photo = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(photo, "id", 30L);
        when(trips.finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED)).thenReturn(1);
        var result = json.readTree("""
                {"places":[],"unclassified":[{"trip_attachment_id":30,"issue":"BLURRY",
                "region_origin":"UNKNOWN","taken_at":null,"latitude":null,
                "longitude":null,"evaluation":31}]}
                """);

        service.saveCompleted(7L, 1L, executionId, List.of(photo), result);

        assertEquals(AttachmentIssue.BLURRY, photo.getIssue());
        verify(attachments).saveAll(List.of(photo));
        verify(trips).finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED);
    }

    @Test
    void 사진이_빠진_AI_결과는_완료하지_않는다() {
        String executionId = executions.reserve(7L);
        TripAttachment photo = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(photo, "id", 30L);
        var result = json.readTree("""
                {"places":[],"unclassified":[]}
                """);

        assertThrows(IllegalStateException.class,
                () -> service.saveCompleted(7L, 1L, executionId, List.of(photo), result));
        verifyNoInteractions(trips, attachments, places);
    }

    @Test
    void 분류된_사진과_장소를_같은_완료_트랜잭션에서_저장한다() {
        String executionId = executions.reserve(7L);
        TripAttachment photo = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(photo, "id", 30L);
        when(places.save(any(TripDetailPlace.class))).thenAnswer(call -> {
            TripDetailPlace place = call.getArgument(0);
            ReflectionTestUtils.setField(place, "id", 40L);
            return place;
        });
        when(trips.finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED)).thenReturn(1);
        var result = json.readTree("""
                {"places":[{"place_id":"p1","latitude":33.45,"longitude":126.94,
                "first_taken_at":null,"last_taken_at":null,"representative_attachment_id":30,
                "attachments":[{"trip_attachment_id":30,"taken_at":null,"latitude":33.45,
                "longitude":126.94,"region_origin":"EXIF","evaluation":91}]}],
                "unclassified":[]}
                """);

        service.saveCompleted(7L, 1L, executionId, List.of(photo), result);

        assertEquals(40L, photo.getTripPlaceId());
        assertEquals(ClassificationStatus.ACTIVE, photo.getClassificationStatus());
        verify(places).save(any(TripDetailPlace.class));
        verify(attachments).saveAll(List.of(photo));
    }

    @Test
    void 메모리의_현재_실행과_다른_AI_결과는_저장하지_않는다() {
        executions.reserve(7L);

        assertThrows(IllegalStateException.class,
                () -> service.saveCompleted(7L, 1L, "old-run", List.of(), json.readTree(
                        "{\"places\":[],\"unclassified\":[]}")));

        verifyNoInteractions(trips, attachments, places);
    }
}
