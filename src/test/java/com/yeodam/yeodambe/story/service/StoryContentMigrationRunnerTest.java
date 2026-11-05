package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
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
    private final StoryContentMigrationRunner runner = new StoryContentMigrationRunner(stories, migration);

    @Test
    void emptyTargetFinishesWithoutMigrationCalls() {
        runner.run(new DefaultApplicationArguments());
        verify(stories).findContentMigrationTargetIds(0L, PageRequest.of(0, 100));
        verifyNoInteractions(migration);
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

        runner.run(new DefaultApplicationArguments());

        var order = inOrder(stories, migration);
        order.verify(stories).findContentMigrationTargetIds(0L, PageRequest.of(0, 100));
        for (long id = 1; id <= 100; id++) order.verify(migration).migrateOne(id);
        order.verify(stories).findContentMigrationTargetIds(100L, PageRequest.of(0, 100));
        order.verify(migration).migrateOne(104L);
        order.verify(migration).migrateOne(109L);
        order.verify(stories).findContentMigrationTargetIds(109L, PageRequest.of(0, 100));
        order.verifyNoMoreInteractions();
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
    void targetQueryFailureIsNotReportedAsSuccess() {
        var failure = new IllegalStateException("조회 실패");
        when(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100)))
                .thenThrow(failure);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).isSameAs(failure);
        verifyNoInteractions(migration);
    }
}
