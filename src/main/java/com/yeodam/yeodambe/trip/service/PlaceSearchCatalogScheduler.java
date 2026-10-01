package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.PlaceQueryProviderUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceSearchCatalogScheduler {
    private final PlaceSearchCatalog catalog;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!catalog.isReady()) {
            refresh();
        }
    }

    @Scheduled(cron = "${place.catalog.refresh-cron}", zone = "${place.catalog.refresh-zone}")
    public void refresh() {
        try {
            catalog.refresh();
        } catch (RuntimeException e) {
            if (e instanceof PlaceQueryProviderUnavailableException) {
                // 외부 호출 예외의 URL에는 서비스 키가 포함될 수 있어 원문을 기록하지 않는다.
                log.error("여행지 검색 외부 데이터 수집에 실패했습니다. 기존 데이터를 유지합니다.");
            } else {
                log.error("여행지 검색 로컬 데이터 갱신에 실패했습니다. 기존 데이터를 유지합니다.", e);
            }
        }
    }
}
