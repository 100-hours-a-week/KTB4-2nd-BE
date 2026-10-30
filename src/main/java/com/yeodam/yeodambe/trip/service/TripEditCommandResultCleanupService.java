package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.repository.TripEditCommandRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripEditCommandResultCleanupService {
    private static final int BATCH_SIZE = 200;
    private final TripEditCommandRecordRepository records;

    @Scheduled(fixedDelay = 60_000)
    public void clearExpiredResults() {
        try {
            records.clearExpiredResultBodies(LocalDateTime.now(), BATCH_SIZE);
        } catch (DataAccessException exception) {
            log.warn("공유 편집 만료 결과 본문 정리 실패: failure={}", exception.getClass().getSimpleName());
        }
    }
}
