package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import com.yeodam.yeodambe.story.repository.StoryContentRepository;
import com.yeodam.yeodambe.common.exception.StoryDataIntegrityException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryContentMigrationRunnerTest {
    private final StoryRepository stories = mock(StoryRepository.class);
    private final StoryContentMigrationService migration = mock(StoryContentMigrationService.class);
    private final StoryContentRepository contents = mock(StoryContentRepository.class);
    private final StoryContentMigrationRunner runner = new StoryContentMigrationRunner(stories, migration, contents);

    @Test
    void emptyTargetFinishesWithoutMigrationCalls() {
        runner.run(new DefaultApplicationArguments());
        verify(stories, times(2)).findContentMigrationTargetIds(0L, PageRequest.of(0, 100));
        verifyNoInteractions(migration, contents);
    }

    @Test
    void scansOneHundredAtATimeAndAdvancesUsingLastId() {
        var firstPage = LongStream.rangeClosed(1, 100).boxed().toList();
        when(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100)))
                .thenReturn(firstPage);
        when(stories.findContentMigrationTargetIds(100L, PageRequest.of(0, 100)))
                .thenReturn(List.of(104L, 109L));
        when(stories.findContentMigrationTargetIds(109L, PageRequest.of(0, 100)))
                .thenReturn(List.of());

        when(contents.existsById(anyLong())).thenReturn(true);
        runner.run(new DefaultApplicationArguments());

        var order = inOrder(stories, migration);
        order.verify(stories).findContentMigrationTargetIds(0L, PageRequest.of(0, 100));
        for (long id = 1; id <= 100; id++) order.verify(migration).migrateOne(id);
        order.verify(stories).findContentMigrationTargetIds(100L, PageRequest.of(0, 100));
        order.verify(migration).migrateOne(104L);
        order.verify(migration).migrateOne(109L);
        order.verify(stories).findContentMigrationTargetIds(109L, PageRequest.of(0, 100));
        order.verify(stories).findContentMigrationTargetIds(0L, PageRequest.of(0, 100));
        order.verify(stories).findContentMigrationTargetIds(100L, PageRequest.of(0, 100));
        order.verify(stories).findContentMigrationTargetIds(109L, PageRequest.of(0, 100));
        order.verifyNoMoreInteractions();
        verify(contents, times(102)).existsById(anyLong());
    }

    @Test
    void itemFailureStopsRemainingItemsAndPropagatesOriginalError() {
        var failure = new IllegalStateException("검증 실패");
        when(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100)))
                .thenReturn(List.of(1L, 2L, 3L));
        doThrow(failure).when(migration).migrateOne(2L);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).isSameAs(failure);

        verify(migration).migrateOne(1L);
        verify(migration).migrateOne(2L);
        verify(migration, never()).migrateOne(3L);
        verify(stories).findContentMigrationTargetIds(0L, PageRequest.of(0, 100));
        verifyNoMoreInteractions(stories);
    }

    @Test
    void missingMongoDocumentInFinalCheckFailsStartup() {
        when(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100)))
                .thenReturn(List.of(1L));
        when(stories.findContentMigrationTargetIds(1L, PageRequest.of(0, 100)))
                .thenReturn(List.of());
        when(contents.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(StoryDataIntegrityException.class);

        verify(migration).migrateOne(1L);
        verify(contents).existsById(1L);
    }

    @Test
    void targetQueryFailureIsNotReportedAsSuccess() {
        var failure = new IllegalStateException("조회 실패");
        when(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100)))
                .thenThrow(failure);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).isSameAs(failure);
        verifyNoInteractions(migration);
    }
}
