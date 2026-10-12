package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.repository.TripAttachmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class AttachmentStorageSizeBackfillRunnerTest {
    private final TripAttachmentRepository attachments = mock(TripAttachmentRepository.class);
    private final AttachmentStorageSizeBackfillService backfill = mock(AttachmentStorageSizeBackfillService.class);

    @Test
    void 활성화_속성_없이도_러너를_등록한다() {
        context().run(application -> assertThat(application)
                .hasSingleBean(AttachmentStorageSizeBackfillRunner.class));
    }

    @Test
    void 누락된_크기가_없으면_보충_작업_없이_종료한다() {
        var page = PageRequest.of(0, 100);
        when(attachments.findMissingStorageSizes(0L, page)).thenReturn(List.of());

        new AttachmentStorageSizeBackfillRunner(attachments, backfill)
                .run(new DefaultApplicationArguments());

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

        new AttachmentStorageSizeBackfillRunner(attachments, backfill)
                .run(new DefaultApplicationArguments());

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
    void 실패하면_후속_행을_처리하지_않고_중단하며_예외를_전파한다() {
        var page = PageRequest.of(0, 100);
        var first = attachment(10L);
        var second = attachment(20L);
        when(attachments.findMissingStorageSizes(0L, page))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("S3 unavailable")).when(backfill).backfillOne(10L);

        assertThatThrownBy(() -> new AttachmentStorageSizeBackfillRunner(attachments, backfill)
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class).hasMessage("S3 unavailable");

        verify(backfill).backfillOne(10L);
        org.mockito.Mockito.verifyNoMoreInteractions(backfill);
        verify(attachments).findMissingStorageSizes(0L, page);
        org.mockito.Mockito.verifyNoMoreInteractions(attachments);
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withBean(TripAttachmentRepository.class, () -> attachments)
                .withBean(AttachmentStorageSizeBackfillService.class, () -> backfill)
                .withUserConfiguration(AttachmentStorageSizeBackfillRunner.class);
    }

    private TripAttachment attachment(Long id) {
        TripAttachment attachment = mock(TripAttachment.class);
        when(attachment.getId()).thenReturn(id);
        return attachment;
    }
}
