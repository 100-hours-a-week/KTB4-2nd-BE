package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StoryContentStorageServiceTest {
    private final StoryContentRepository repository = mock(StoryContentRepository.class);
    private final StoryContentStorageService service = new StoryContentStorageService(repository);

    @Test
    void insertsMissingDocument() {
        var document = document();

        service.insertIfAbsent(document);

        verify(repository).existsById(10L);
        verify(repository).insert(document);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void leavesExistingDocumentUntouched() {
        when(repository.existsById(10L)).thenReturn(true);

        service.insertIfAbsent(document());

        verify(repository).existsById(10L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void acceptsDuplicateOnlyWhenDocumentNowExists() {
        var document = document();
        when(repository.existsById(10L)).thenReturn(false, true);
        when(repository.insert(document)).thenThrow(new DuplicateKeyException("duplicate"));

        service.insertIfAbsent(document);

        verify(repository, times(2)).existsById(10L);
        verify(repository).insert(document);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void propagatesDuplicateWhenExpectedDocumentIsStillMissing() {
        var document = document();
        var failure = new DuplicateKeyException("duplicate");
        when(repository.insert(document)).thenThrow(failure);

        assertThatThrownBy(() -> service.insertIfAbsent(document)).isSameAs(failure);
    }

    @Test
    void propagatesStorageConnectionFailure() {
        var document = document();
        var failure = new DataAccessResourceFailureException("unavailable");
        when(repository.insert(document)).thenThrow(failure);

        assertThatThrownBy(() -> service.insertIfAbsent(document)).isSameAs(failure);
        verify(repository).existsById(10L);
        verify(repository).insert(document);
        verifyNoMoreInteractions(repository);
    }

    private StoryContentDocument document() {
        return new StoryContentDocument(10L, Story.Mood.PLAIN, "여행 요약",
                List.of(), List.of(), 1L);
    }
}
