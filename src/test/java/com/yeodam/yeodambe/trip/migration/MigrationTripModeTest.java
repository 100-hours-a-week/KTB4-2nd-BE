package com.yeodam.yeodambe.trip.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationTripModeTest {
    @TempDir Path directory;

    @Test
    void marker를_원자적으로_바꾼_뒤_다음_요청부터_queue를_선택한다() throws Exception {
        Path marker = directory.resolve("mode");
        Files.writeString(marker, "direct\n");
        MigrationTripMode mode = new MigrationTripMode(marker.toString());

        assertThat(mode.queueEnabled()).isFalse();
        Files.writeString(marker, "queue\n");
        assertThat(mode.queueEnabled()).isTrue();
    }

    @Test
    void 설정된_marker가_없거나_알수없는_값이면_옛_writer로_돌아가지_않는다() throws Exception {
        Path marker = directory.resolve("mode");
        MigrationTripMode mode = new MigrationTripMode(marker.toString());

        assertThatThrownBy(mode::queueEnabled)
                .isInstanceOf(MigrationTripAdmissionException.class);
        Files.writeString(marker, "unknown");
        assertThatThrownBy(mode::queueEnabled)
                .isInstanceOf(MigrationTripAdmissionException.class);
    }
    @Test
    void source_queue는_여행만_수락하고_인증은_직접_처리한다() throws Exception {
        Path marker=directory.resolve("mode");Files.writeString(marker,"source-queue");
        var mode=new MigrationTripMode(marker.toString());
        assertThat(mode.trip(()->"direct",()->"queued")).isEqualTo("queued");
        assertThat(mode.auth(()->{assertThat(mode.activeDirect()).isEqualTo(1);return "direct";},()->"queued")).isEqualTo("direct");
        assertThat(mode.activeDirect()).isZero();
    }
    @Test
    void 전환은_기존_요청을_등록한_채_새_direct_진입을_막는다() throws Exception {
        var mode=new MigrationTripMode("");
        var entered=new java.util.concurrent.CountDownLatch(1);
        var finish=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var old=workers.submit(()->mode.auth(()->{
                entered.countDown();
                try {finish.await();} catch(InterruptedException e) {throw new RuntimeException(e);}
                return "old completed";
            },()->"unexpected"));
            assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(mode.beginFence()).isEqualTo(1);
            assertThat(mode.auth(()->"unsafe direct",()->"queued")).isEqualTo("queued");
            assertThat(mode.activeDirect()).isEqualTo(1);
            finish.countDown();assertThat(old.get(2,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("old completed");
            assertThat(mode.activeDirect()).isZero();
        } finally {finish.countDown();}
    }
    @Test
    void 바깥_트랜잭션이_있으면_완료_콜백까지_진행상태를_유지한다() {
        var mode=new MigrationTripMode("");
        var manager=org.springframework.transaction.support.TransactionSynchronizationManager.class;
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            mode.auth(()->"done",()->"queued");assertThat(mode.activeDirect()).isEqualTo(1);
            for(var callback:org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations())
                callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
            assertThat(mode.activeDirect()).isZero();
        } finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
    }
    @Test
    void 직접_요청이_예외로_끝나도_진행_등록을_해제한다() {
        var mode=new MigrationTripMode("");
        assertThatThrownBy(()->mode.auth(()->{throw new IllegalStateException("rollback");},()->"queued")).isInstanceOf(IllegalStateException.class);
        assertThat(mode.activeDirect()).isZero();
    }
}
