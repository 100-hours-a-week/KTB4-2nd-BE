package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import com.yeodam.yeodambe.user.repository.UserStatsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class AttachmentStorageSizeBackfillRunnerTest {
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final AttachmentStorageSizeBackfillService backfill = mock(AttachmentStorageSizeBackfillService.class);
    private final UserStatsRepository userStats = mock(UserStatsRepository.class);
    private final AttachmentStorageSizeBackfillRunner runner =
            new AttachmentStorageSizeBackfillRunner(attachments, backfill, userStats);

    @AfterEach
    void stop() {
        runner.stop();
    }

    @Test
    void 테스트_프로필에서는_자동_실행하지_않는다() {
        context().withPropertyValues("spring.profiles.active=test")
                .run(application -> assertThat(application)
                        .doesNotHaveBean(AttachmentStorageSizeBackfillRunner.class));
    }

    @Test
    void 서버_시작은_S3_보정을_기다리지_않는다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        }).when(attachments).findMissingStorageSizes(0L, PageRequest.of(0, 100));
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(2),
                    () -> runner.run(new DefaultApplicationArguments()));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void 활성화_속성_없이도_러너를_등록한다() {
        context().run(application -> assertThat(application)
                .hasSingleBean(AttachmentStorageSizeBackfillRunner.class));
    }

    @Test
    void 누락된_크기가_없으면_보충_작업_없이_종료한다() {
        var page = PageRequest.of(0, 100);
        when(attachments.findMissingStorageSizes(0L, page)).thenReturn(List.of());

        runner.backfill();

        verify(attachments).findMissingStorageSizes(0L, page);
        verifyNoInteractions(backfill);
    }

    @Test
    void 각_행을_처리하고_남은_행이_없을_때까지_마지막_ID_이후를_조회한다() {
        var first = attachment(10L);
        var second = attachment(20L);
        var third = attachment(30L);
        var page = PageRequest.of(0, 100);
        when(attachments.findMissingStorageSizes(0L, page)).thenReturn(List.of(first, second));
        when(attachments.findMissingStorageSizes(20L, page)).thenReturn(List.of(third));
        when(attachments.findMissingStorageSizes(30L, page)).thenReturn(List.of());

        runner.backfill();

        var order = inOrder(attachments, backfill);
        order.verify(attachments).findMissingStorageSizes(0L, page);
        order.verify(backfill).backfillOne(10L);
        order.verify(backfill).backfillOne(20L);
        order.verify(attachments).findMissingStorageSizes(20L, page);
        order.verify(backfill).backfillOne(30L);
        order.verify(attachments).findMissingStorageSizes(30L, page);
        order.verifyNoMoreInteractions();
    }

    @Test
    void 한_첨부의_S3_조회가_실패해도_다음_첨부를_계속_처리한다() {
        var page = PageRequest.of(0, 100);
        var first = attachment(10L);
        var second = attachment(20L);
        when(attachments.findMissingStorageSizes(0L, page))
                .thenReturn(List.of(first, second));
        when(attachments.findMissingStorageSizes(20L, page)).thenReturn(List.of());
        doThrow(new IllegalStateException("S3 unavailable")).when(backfill).backfillOne(10L);

        org.assertj.core.api.Assertions.assertThatCode(runner::backfill)
                .doesNotThrowAnyException();

        verify(backfill).backfillOne(10L);
        verify(backfill).backfillOne(20L);
        verify(attachments).findMissingStorageSizes(0L, page);
        verify(attachments).findMissingStorageSizes(20L, page);
    }

    @Test
    void 용량이_이미_채워진_사용자도_통계를_보정하고_실패한_사용자는_건너뛴다() {
        when(userStats.findActiveUserIdsAfter(0L, PageRequest.of(0, 100)))
                .thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("missing size")).when(backfill).refreshUserStats(1L);

        runner.backfill();

        verify(backfill).refreshUserStats(1L);
        verify(backfill).refreshUserStats(2L);
        verify(userStats).findActiveUserIdsAfter(2L, PageRequest.of(0, 100));
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withBean(TripAttachmentRepository.class, () -> attachments)
                .withBean(AttachmentStorageSizeBackfillService.class, () -> backfill)
                .withBean(UserStatsRepository.class, () -> userStats)
                .withUserConfiguration(AttachmentStorageSizeBackfillRunner.class);
    }

    private TripAttachment attachment(Long id) {
        TripAttachment attachment = mock(TripAttachment.class);
        when(attachment.getId()).thenReturn(id);
        return attachment;
    }
}
