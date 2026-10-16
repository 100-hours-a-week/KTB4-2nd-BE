package com.yeodam.yeodambe.trip.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TripObjectCleanupSchedulerTest {
    private final TripObjectCleanupService cleanup = mock(TripObjectCleanupService.class);
    private final TripObjectCleanupScheduler scheduler = new TripObjectCleanupScheduler(cleanup);

    @Test
    void 정리_대상별_실행을_각각의_서비스_메서드에_위임한다() {
        scheduler.processScheduled();
        scheduler.retryPending();
        scheduler.recoverSchedules();

        verify(cleanup).processScheduled();
        verify(cleanup).retryPending();
        verify(cleanup).recoverSchedules();
        verifyNoMoreInteractions(cleanup);
    }

    @Test
    void 예약과_실패객체는_완료후_육십초_복구는_시작시와_완료후_한시간으로_등록한다() throws Exception {
        assertThat(schedule("processScheduled").fixedDelay()).isEqualTo(60_000L);
        assertThat(schedule("retryPending").fixedDelay()).isEqualTo(60_000L);
        assertThat(schedule("recoverSchedules").initialDelay()).isZero();
        assertThat(schedule("recoverSchedules").fixedDelay()).isEqualTo(3_600_000L);
    }

    private Scheduled schedule(String method) throws NoSuchMethodException {
        return TripObjectCleanupScheduler.class.getDeclaredMethod(method)
                .getAnnotation(Scheduled.class);
    }
}
