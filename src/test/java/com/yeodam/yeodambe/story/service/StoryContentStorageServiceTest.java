package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.document.StoryContentDocument;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StoryContentStorageServiceTest {
    private final StoryContentRepository repository = mock(StoryContentRepository.class);
    private final StoryContentStorageService service = new StoryContentStorageService(repository);

    @Test
    void 없는_문서를_삽입한다() {
        var document = document();

        assertThat(service.insertIfAbsent(document)).isTrue();

        verify(repository).existsById(10L);
        verify(repository).insert(document);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 기존_문서는_변경하지_않는다() {
        when(repository.existsById(10L)).thenReturn(true);

        assertThat(service.insertIfAbsent(document())).isFalse();

        verify(repository).existsById(10L);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 중복_오류는_문서가_실제로_존재할_때만_허용한다() {
        var document = document();
        when(repository.existsById(10L)).thenReturn(false, true);
        when(repository.insert(document)).thenThrow(new DuplicateKeyException("duplicate"));

        assertThat(service.insertIfAbsent(document)).isFalse();

        verify(repository, times(2)).existsById(10L);
        verify(repository).insert(document);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 중복_오류_후에도_문서가_없으면_오류를_전파한다() {
        var document = document();
        var failure = new DuplicateKeyException("duplicate");
        when(repository.insert(document)).thenThrow(failure);

        assertThatThrownBy(() -> service.insertIfAbsent(document)).isSameAs(failure);
    }

    @Test
    void 저장소_연결_실패를_전파한다() {
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
