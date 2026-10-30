package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.repository.TripEditCommandRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripEditCommandResultCleanupServiceTest {
    private final TripEditCommandRecordRepository records = mock(TripEditCommandRecordRepository.class);
    private final TripEditCommandResultCleanupService service = new TripEditCommandResultCleanupService(records);

    @Test
    void 실행당_200개만_정리하고_60초_뒤_다시_실행한다() throws Exception {
        LocalDateTime before = LocalDateTime.now();
        service.clearExpiredResults();

        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(records).clearExpiredResultBodies(now.capture(), eq(200));
        assertThat(now.getValue()).isBetween(before, LocalDateTime.now());
        assertThat(TripEditCommandResultCleanupService.class.getMethod("clearExpiredResults")
                .getAnnotation(Scheduled.class).fixedDelay()).isEqualTo(60_000);
    }

    @Test
    void DB_장애가_발생해도_다음_실행에서_다시_정리한다() {
        when(records.clearExpiredResultBodies(any(), eq(200)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable")).thenReturn(1);

        assertThatCode(service::clearExpiredResults).doesNotThrowAnyException();
        assertThatCode(service::clearExpiredResults).doesNotThrowAnyException();
        verify(records, times(2)).clearExpiredResultBodies(any(), eq(200));
    }
}
