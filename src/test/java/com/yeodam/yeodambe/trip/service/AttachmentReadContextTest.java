package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentReadRejectedException;
import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.service.response.TripReadAccessSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentReadContextTest {

    @Test
    void 첫_캐시_미스가_두_단계에서_공유할_하나의_마감을_정한다() {
        AtomicLong time = new AtomicLong();
        AttachmentReadContext context = new AttachmentReadContext(time::get);
        context.onCacheMiss();
        time.set(1_500_000_000L);
        context.onCacheMiss();
        assertThat(context.remainingWaitNanos()).isEqualTo(500_000_000L);
        time.set(2_000_000_000L);
        assertThatThrownBy(context::requireWaitBudget).isInstanceOf(AttachmentReadRejectedException.class);
    }

    @Test
    void 캐시_적중은_대기_예산을_시작하지_않는다() {
        AttachmentReadContext context = new AttachmentReadContext(System::nanoTime);
        context.requireWaitBudget();
        assertThatThrownBy(context::remainingWaitNanos).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 단계별_시도_횟수는_독립적이며_초기화되지_않는다() {
        AttachmentReadContext context = new AttachmentReadContext(() -> 0L);
        context.onCacheMiss();
        context.beginAccessRefresh();
        context.beginResponseRefresh();
        context.beginAccessRefresh();
        context.onCacheMiss();
        context.beginResponseRefresh();
        assertThatThrownBy(context::beginAccessRefresh).isInstanceOf(AttachmentReadRejectedException.class)
                .extracting("reason").isEqualTo(AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED);
        assertThatThrownBy(context::beginResponseRefresh).isInstanceOf(AttachmentReadRejectedException.class)
                .extracting("reason").isEqualTo(AttachmentReadRejectedException.Reason.RETRY_EXHAUSTED);
    }

    @Test
    void 스냅샷은_변경_가능한_원본_목록을_보유하지_않는다() {
        List<TripReadAccessSnapshot.Member> source = new ArrayList<>(List.of(
                new TripReadAccessSnapshot.Member(2L, TripSharePermission.READ, Instant.parse("2026-11-05T12:00:00Z")),
                new TripReadAccessSnapshot.Member(3L, TripSharePermission.WRITE, Instant.parse("2026-11-05T12:00:00Z"))));
        TripReadAccessSnapshot snapshot = new TripReadAccessSnapshot(7L, 1L, source);
        source.clear();
        assertThat(snapshot.members()).hasSize(2);
        assertThatThrownBy(() -> snapshot.members().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
