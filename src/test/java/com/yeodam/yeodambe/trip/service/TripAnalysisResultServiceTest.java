package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.InitialUploadExecutionRegistry;
import com.yeodam.yeodambe.trip.service.TripAnalysisResultService;
import com.yeodam.yeodambe.user.service.UserStatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TripAnalysisResultServiceTest {
    private final TripRepository trips = mock(TripRepository.class);
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final TripDetailPlaceRepository places = mock(TripDetailPlaceRepository.class);
    private final InitialUploadExecutionRegistry executions = new InitialUploadExecutionRegistry();
    private final UserStatsService userStats = mock(UserStatsService.class);
    private final TripAnalysisResultService service = new TripAnalysisResultService(
            trips, attachments, places, executions, userStats, mock(com.yeodam.yeodambe.trip.repository.InitialAttachmentUploadBatchRepository.class));
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 모든_사진이_결과에_있고_실행_ID가_일치해야_완료한다() {
        String executionId = executions.reserve(7L);
        TripAttachment photo = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(photo, "id", 30L);
        when(trips.finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED)).thenReturn(1);
        when(trips.updateThumbnailKey(7L, 1L, ProcessingStatus.COMPLETED, null))
                .thenReturn(1);
        var result = json.readTree("""
                {"places":[],"unclassified":[{"trip_attachment_id":30,"issue":"BLURRY",
                "region_origin":"UNKNOWN","taken_at":null,"latitude":null,
                "longitude":null,"evaluation":31}]}
                """);

        service.saveCompleted(7L, 1L, executionId, List.of(photo), result, Map.of());

        assertEquals(AttachmentIssue.BLURRY, photo.getIssue());
        verify(attachments).saveAll(List.of(photo));
        verify(trips).finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED);
        verify(userStats).refreshFromActiveTrips(1L);
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
                () -> service.saveCompleted(7L, 1L, executionId, List.of(photo), result, Map.of()));
        verifyNoInteractions(trips, attachments, places);
    }

    @Test
    void AI_분류_결과를_장소와_첨부_저장소에_전달한다() {
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
        when(trips.updateThumbnailKey(7L, 1L, ProcessingStatus.COMPLETED,
                "preview")).thenReturn(1);
        var result = json.readTree("""
                {"places":[{"place_id":"p1","latitude":33.45,"longitude":126.94,
                "first_taken_at":null,"last_taken_at":null,"representative_attachment_id":30,
                "attachments":[{"trip_attachment_id":30,"taken_at":null,"latitude":33.45,
                "longitude":126.94,"region_origin":"EXIF","evaluation":91}]}],
                "unclassified":[]}
                """);

        service.saveCompleted(7L, 1L, executionId, List.of(photo), result,
                Map.of("p1", "성산일출봉"));

        assertEquals(40L, photo.getTripPlaceId());
        assertEquals(ClassificationStatus.ACTIVE, photo.getClassificationStatus());
        var writes = inOrder(trips, places, attachments);
        writes.verify(trips).finishInitialUpload(
                7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED);
        writes.verify(places).save(any(TripDetailPlace.class));
        writes.verify(attachments).saveAll(List.of(photo));
        ArgumentCaptor<TripDetailPlace> placeCaptor = ArgumentCaptor.forClass(TripDetailPlace.class);
        verify(places).save(placeCaptor.capture());
        assertEquals("성산일출봉", placeCaptor.getValue().getPlaceName());
    }

    @Test
    void 대표사진은_evaluation이_높고_ID가_작은_첨부로_정한다() {
        String executionId = executions.reserve(7L);
        TripAttachment low = TripAttachment.initial(7L, 20L, "analyze-low", "preview-low");
        TripAttachment highLater = TripAttachment.initial(7L, 21L, "analyze-high-later", "preview-high-later");
        TripAttachment highFirst = TripAttachment.initial(7L, 22L, "analyze-high-first", "preview-high-first");
        ReflectionTestUtils.setField(low, "id", 30L);
        ReflectionTestUtils.setField(highLater, "id", 32L);
        ReflectionTestUtils.setField(highFirst, "id", 31L);
        when(places.save(any(TripDetailPlace.class))).thenAnswer(call -> {
            TripDetailPlace place = call.getArgument(0);
            ReflectionTestUtils.setField(place, "id", 40L);
            return place;
        });
        when(trips.finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED)).thenReturn(1);
        when(trips.updateThumbnailKey(7L, 1L, ProcessingStatus.COMPLETED,
                "preview-high-first")).thenReturn(1);
        var result = json.readTree("""
                {"places":[{"place_id":"p1","latitude":33.45,"longitude":126.94,
                "first_taken_at":null,"last_taken_at":null,"representative_attachment_id":30,
                "attachments":[
                  {"trip_attachment_id":30,"taken_at":null,"latitude":33.45,"longitude":126.94,"region_origin":"EXIF","evaluation":10},
                  {"trip_attachment_id":32,"taken_at":null,"latitude":33.45,"longitude":126.94,"region_origin":"EXIF","evaluation":90},
                  {"trip_attachment_id":31,"taken_at":null,"latitude":33.45,"longitude":126.94,"region_origin":"EXIF","evaluation":90}
                ]}],"unclassified":[]}
                """);

        service.saveCompleted(7L, 1L, executionId, List.of(low, highLater, highFirst), result,
                Map.of("p1", "성산일출봉"));

        ArgumentCaptor<TripDetailPlace> captor = ArgumentCaptor.forClass(TripDetailPlace.class);
        verify(places).save(captor.capture());
        assertEquals("preview-high-first", captor.getValue().getThumbnailKey());
        verify(trips).updateThumbnailKey(
                7L, 1L, ProcessingStatus.COMPLETED, "preview-high-first");
    }

    @Test
    void 메모리의_현재_실행과_다른_AI_결과는_저장하지_않는다() {
        executions.reserve(7L);

        assertThrows(IllegalStateException.class,
                () -> service.saveCompleted(7L, 1L, "old-run", List.of(), json.readTree(
                        "{\"places\":[],\"unclassified\":[]}"), Map.of()));

        verifyNoInteractions(trips, attachments, places);
    }

    @Test
    void 취소가_먼저_상태를_변경하면_늦은_AI_결과를_저장하지_않는다() {
        String executionId = executions.reserve(7L);
        TripAttachment photo = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(photo, "id", 30L);
        var result = json.readTree("""
                {"places":[],"unclassified":[{"trip_attachment_id":30,"issue":"BLURRY",
                "region_origin":"UNKNOWN","taken_at":null,"latitude":null,
                "longitude":null,"evaluation":31}]}
                """);

        assertThrows(IllegalStateException.class,
                () -> service.saveCompleted(7L, 1L, executionId, List.of(photo), result, Map.of()));

        verify(trips).finishInitialUpload(
                7L, 1L, ProcessingStatus.PROCESSING, ProcessingStatus.COMPLETED);
        verifyNoInteractions(attachments, places);
    }

    @Test
    void 장소명_매핑이_AI_장소와_정확히_일치하지_않으면_완료하지_않는다() {
        String executionId = executions.reserve(7L);
        TripAttachment photo = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(photo, "id", 30L);
        var result = json.readTree("""
                {"places":[{"place_id":"p1","latitude":33.45,"longitude":126.94,
                "first_taken_at":null,"last_taken_at":null,"representative_attachment_id":30,
                "attachments":[{"trip_attachment_id":30,"taken_at":null,"latitude":33.45,
                "longitude":126.94,"region_origin":"EXIF","evaluation":91}]}],
                "unclassified":[]}
                """);

        for (Map<String, String> names : List.of(
                Map.<String, String>of(),
                Map.of("p1", " "),
                Map.of("p1", "가".repeat(51)),
                Map.of("p1", "제주", "p2", "서울")
        )) {
            assertThrows(IllegalStateException.class,
                    () -> service.saveCompleted(7L, 1L, executionId, List.of(photo), result, names));
        }

        verifyNoInteractions(trips, attachments, places);
    }

    @Test
    void 미분류_사진의_장소_ID를_같은_결과의_저장_PK로_매핑한다() {
        String executionId = executions.reserve(7L);
        TripAttachment classified = photo(30L);
        TripAttachment unclassified = photo(31L);
        stubCompletion();

        service.saveCompleted(7L, 1L, executionId, List.of(classified, unclassified),
                resultWithUnclassifiedPlace("\"p1\""), Map.of("p1", "성산일출봉"));

        assertThat(classified.getTripPlaceId()).isEqualTo(40L);
        assertThat(unclassified.getTripPlaceId()).isEqualTo(40L);
        assertThat(unclassified.getClassificationStatus()).isEqualTo(ClassificationStatus.UNCLASSIFIED);
        assertThat(unclassified.getIssue()).isEqualTo(AttachmentIssue.BLURRY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null"})
    void 미분류_사진의_장소_ID가_누락되거나_null이면_null을_저장한다(String placeId) {
        String executionId = executions.reserve(7L);
        TripAttachment classified = photo(30L);
        TripAttachment unclassified = photo(31L);
        unclassified.classify(99L, RegionOrigin.EXIF, null, null, null, 80);
        stubCompletion();

        service.saveCompleted(7L, 1L, executionId, List.of(classified, unclassified),
                resultWithUnclassifiedPlace(placeId), Map.of("p1", "성산일출봉"));

        assertThat(unclassified.getTripPlaceId()).isNull();
        assertThat(unclassified.getClassificationStatus()).isEqualTo(ClassificationStatus.UNCLASSIFIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"p999\"", "\"\"", "\" \"", "1", "true", "{}", "[]"})
    void 미분류_사진의_잘못된_장소_참조는_완료_전에_거부한다(String placeId) {
        String executionId = executions.reserve(7L);
        TripAttachment classified = photo(30L);
        TripAttachment unclassified = photo(31L);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.saveCompleted(7L, 1L, executionId, List.of(classified, unclassified),
                        resultWithUnclassifiedPlace(placeId), Map.of("p1", "성산일출봉")));

        assertThat(error).hasMessage("미분류 사진의 장소 참조가 올바르지 않습니다.");
        assertThat(unclassified.getTripPlaceId()).isNull();
        verifyNoInteractions(trips, attachments, places);
    }

    private TripAttachment photo(Long id) {
        TripAttachment photo = TripAttachment.initial(7L, 20L, "analyze", "preview");
        ReflectionTestUtils.setField(photo, "id", id);
        return photo;
    }

    private void stubCompletion() {
        when(places.save(any(TripDetailPlace.class))).thenAnswer(call -> {
            TripDetailPlace place = call.getArgument(0);
            ReflectionTestUtils.setField(place, "id", 40L);
            return place;
        });
        when(trips.finishInitialUpload(7L, 1L, ProcessingStatus.PROCESSING,
                ProcessingStatus.COMPLETED)).thenReturn(1);
        when(trips.updateThumbnailKey(7L, 1L, ProcessingStatus.COMPLETED, "preview"))
                .thenReturn(1);
    }

    private JsonNode resultWithUnclassifiedPlace(String placeId) {
        String reference = placeId.equals("missing") ? "" : "\"place_id\":" + placeId + ",";
        return json.readTree("""
                {"places":[{"place_id":"p1","latitude":33.45,"longitude":126.94,
                "first_taken_at":null,"last_taken_at":null,"representative_attachment_id":30,
                "attachments":[{"trip_attachment_id":30,"taken_at":null,"latitude":33.45,
                "longitude":126.94,"region_origin":"EXIF","evaluation":91}]}],
                "unclassified":[{%s"trip_attachment_id":31,"issue":"BLURRY",
                "region_origin":"UNKNOWN","taken_at":null,"latitude":null,
                "longitude":null,"evaluation":31}]}
                """.formatted(reference));
    }
}
