package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.repository.*;
import com.yeodam.yeodambe.trip.entity.Trip;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryContentMigrationServiceTest {
    private final StoryRepository stories = mock(StoryRepository.class);
    private final StoryBlockRepository blocks = mock(StoryBlockRepository.class);
    private final StorySelectedPlaceRepository places = mock(StorySelectedPlaceRepository.class);
    private final StoryContentRepository contents = mock(StoryContentRepository.class);
    private final StoryContentStorageService storage = mock(StoryContentStorageService.class);
    private final MongoConverter converter = mock(MongoConverter.class);
    private final StoryContentMigrationService service = new StoryContentMigrationService(
            stories, blocks, places, contents, new StoryContentDocumentFactory(), storage, converter);
    private Story story;
    private Trip trip;

    @BeforeEach
    void prepare() {
        trip = new Trip(1L, "여행", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        ReflectionTestUtils.setField(trip, "id", 7L);
        trip.changeCurrentStory(10L);
        story = new Story(7L, Story.Mood.PLAIN, "이관 본문");
        ReflectionTestUtils.setField(story, "id", 10L);
        ReflectionTestUtils.setField(story, "trip", trip);
        story.complete();
        when(stories.findById(10L)).thenReturn(Optional.of(story));
        doAnswer(invocation -> {
            StoryContentDocument source = invocation.getArgument(0);
            Document target = invocation.getArgument(1);
            target.put("summary", source.getStorySummary());
            return null;
        }).when(converter).write(any(StoryContentDocument.class), any(Document.class));
    }

    @Test
    void existingEditedMongoContentIsSkippedBeforeReadingMysql() {
        var edited = document("Mongo 최신 본문");
        ReflectionTestUtils.setField(edited, "version", 1L);
        when(contents.findById(10L)).thenReturn(Optional.of(edited));
        service.migrateOne(10L);
        verifyNoInteractions(stories, blocks, places, storage, converter);
        verify(contents).findById(10L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted-story", "processing", "deleted-trip", "history"})
    void changedTargetIsNotMigrated(String condition) {
        switch (condition) {
            case "deleted-story" -> story.softDelete(LocalDateTime.now());
            case "processing" -> ReflectionTestUtils.setField(story, "processingStatus", Story.Status.PROCESSING);
            case "deleted-trip" -> trip.softDelete(LocalDateTime.now());
            case "history" -> trip.changeCurrentStory(11L);
        }
        service.migrateOne(10L);
        verifyNoInteractions(blocks, places, storage, converter);
    }

    @Test
    void anotherBackendInitialInsertionIsStillVerified() {
        var saved = document("이관 본문");
        when(contents.findById(10L)).thenReturn(Optional.empty(), Optional.of(saved));
        when(storage.insertIfAbsent(any())).thenReturn(false);
        service.migrateOne(10L);
        verify(storage).insertIfAbsent(any());
        verify(contents, times(2)).findById(10L);
        verify(converter, times(2)).write(any(StoryContentDocument.class), any(Document.class));
    }

    @Test
    void insertionFailureIsPropagatedForStartupFailure() {
        var failure = new DataAccessResourceFailureException("Mongo unavailable");
        when(storage.insertIfAbsent(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.migrateOne(10L)).isSameAs(failure);
        verifyNoInteractions(converter);
    }

    @Test
    void missingDocumentAfterInsertionIsRejected() {
        when(storage.insertIfAbsent(any())).thenReturn(true);
        when(contents.findById(10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.migrateOne(10L))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.CONTENT_DOCUMENT_MISSING.message());
    }

    @Test
    void mismatchedInitialDocumentIsRejected() {
        when(storage.insertIfAbsent(any())).thenReturn(true);
        when(contents.findById(10L)).thenReturn(Optional.of(document("잘못된 본문")));
        doAnswer(invocation -> {
            StoryContentDocument source = invocation.getArgument(0);
            Document target = invocation.getArgument(1);
            target.put("summary", source.getStorySummary());
            return null;
        }).when(converter).write(any(StoryContentDocument.class), any(Document.class));

        assertThatThrownBy(() -> service.migrateOne(10L))
                .isInstanceOf(StoryDataIntegrityException.class)
                .hasMessage(StoryGenerationInternalErrorMessage.MIGRATION_CONTENT_MISMATCH.message());
    }

    @Test
    void laterEditedMongoContentIsNotComparedWithOldMysql() {
        var edited = document("정상적으로 수정한 본문");
        ReflectionTestUtils.setField(edited, "version", 1L);
        when(storage.insertIfAbsent(any())).thenReturn(true);
        when(contents.findById(10L)).thenReturn(Optional.empty(), Optional.of(edited));
        assertThatCode(() -> service.migrateOne(10L)).doesNotThrowAnyException();
        verifyNoInteractions(converter);
    }

    @Test
    void initialMismatchIsRejectedAgainOnRetry() {
        when(contents.findById(10L)).thenReturn(Optional.of(document("잘못된 본문")));
        when(storage.insertIfAbsent(any())).thenReturn(false);

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.migrateOne(10L))
                    .isInstanceOf(StoryDataIntegrityException.class)
                    .hasMessage(StoryGenerationInternalErrorMessage.MIGRATION_CONTENT_MISMATCH.message());
        }

        verify(storage, times(2)).insertIfAbsent(any());
        verify(contents, times(4)).findById(10L);
    }

    private StoryContentDocument document(String summary) {
        var document = new StoryContentDocument(10L, Story.Mood.PLAIN, summary, List.of(), List.of(), 1L);
        ReflectionTestUtils.setField(document, "version", 0L);
        return document;
    }
}
