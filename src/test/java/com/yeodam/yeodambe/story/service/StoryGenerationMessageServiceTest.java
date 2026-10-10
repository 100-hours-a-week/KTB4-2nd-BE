package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.InvalidStoryRequestException;
import com.yeodam.yeodambe.common.exception.StoryGenerationForbiddenException;
import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.entity.StoryGenerationPlace;
import com.yeodam.yeodambe.story.repository.StoryGenerationPlaceRepository;
import com.yeodam.yeodambe.trip.service.TripStorySourceService;
import com.yeodam.yeodambe.trip.service.response.TripStorySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryGenerationMessageServiceTest {
    private final StoryGenerationPlaceRepository placeRepository = mock(StoryGenerationPlaceRepository.class);
    private final TripStorySourceService sourceService = mock(TripStorySourceService.class);
    private final StoryGenerationMessageService service =
            new StoryGenerationMessageService(placeRepository, sourceService);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StoryGenerationJob job = savedJob();

    @Test
    void assemblesSavedJobAndSelectedPhotosIntoAiContractJson() {
        prepareSource();

        var message = service.assemble(job);
        var json = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(json).isEqualTo(objectMapper.readTree("""
                {
                  "type": "story",
                  "trip_id": 100,
                  "execution_id": "execution-saved",
                  "trip_name": "가을 여행",
                  "period": {"start_date": "2026-10-10", "end_date": "2026-10-11"},
                  "mood": "EMOTIONAL",
                  "places": [
                    {
                      "trip_place_id": 20,
                      "place_name": "장소20",
                      "attachments": [{
                        "trip_attachment_id": 3,
                        "analyze_storage_key": "analysis/3.jpg",
                        "taken_at": "2026-10-10T09:00:00+09:00",
                        "evaluation": null
                      }]
                    },
                    {
                      "trip_place_id": 10,
                      "place_name": "장소10",
                      "attachments": [{
                        "trip_attachment_id": 1,
                        "analyze_storage_key": "analysis/1.jpg",
                        "taken_at": "2026-10-10T10:00:00+09:00",
                        "evaluation": 80
                      }]
                    }
                  ]
                }
                """));
        verify(placeRepository).findAllByGenerationIdOrderByOrderNumberAsc(7L);
        verify(sourceService).read(2L, 100L, List.of(20L, 10L));
    }

    @Test
    void reassemblingUnchangedSourceKeepsSavedExecutionIdAndPayload() {
        prepareSource();

        var first = service.assemble(job);
        var second = service.assemble(job);

        assertThat(second).isEqualTo(first);
        assertThat(second.executionId()).isEqualTo("execution-saved");
    }

    @ParameterizedTest
    @MethodSource("sourceFailures")
    void propagatesSourceRejectionInsteadOfReturningPartialMessage(RuntimeException rejection) {
        prepareSelection();
        when(sourceService.read(2L, 100L, List.of(20L, 10L))).thenThrow(rejection);

        assertThatThrownBy(() -> service.assemble(job)).isSameAs(rejection);
    }

    static Stream<RuntimeException> sourceFailures() {
        return Stream.of(new InvalidStoryRequestException(),
                new StoryGenerationForbiddenException(), new TripNotFoundException());
    }

    private void prepareSelection() {
        var selection = List.of(new StoryGenerationPlace(7L, 20L, 1),
                new StoryGenerationPlace(7L, 10L, 2));
        when(placeRepository.findAllByGenerationIdOrderByOrderNumberAsc(7L)).thenReturn(selection);
    }

    private void prepareSource() {
        prepareSelection();
        var source = new TripStorySource(100L, "가을 여행",
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11),
                List.of(new TripStorySource.Place(20L, "장소20", List.of(
                                new TripStorySource.Photo(3L, "analysis/3.jpg",
                                        LocalDateTime.of(2026, 10, 10, 9, 0), null))),
                        new TripStorySource.Place(10L, "장소10", List.of(
                                new TripStorySource.Photo(1L, "analysis/1.jpg",
                                        LocalDateTime.of(2026, 10, 10, 10, 0), 80)))));
        when(sourceService.read(2L, 100L, List.of(20L, 10L))).thenReturn(source);
    }

    private StoryGenerationJob savedJob() {
        var saved = mock(StoryGenerationJob.class);
        when(saved.getId()).thenReturn(7L);
        when(saved.getTripId()).thenReturn(100L);
        when(saved.getUserId()).thenReturn(2L);
        when(saved.getExecutionId()).thenReturn("execution-saved");
        when(saved.getMood()).thenReturn(Story.Mood.EMOTIONAL);
        return saved;
    }
}
