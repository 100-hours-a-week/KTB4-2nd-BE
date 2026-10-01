package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.PlaceQueryProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaceSearchCatalogSchedulerTest {
    private final PlaceSearchCatalog catalog = mock(PlaceSearchCatalog.class);
    private final PlaceSearchCatalogScheduler scheduler = new PlaceSearchCatalogScheduler(catalog);

    @Test
    void 정상_파일이_있으면_시작시_재수집하지_않는다() {
        when(catalog.isReady()).thenReturn(true);
        scheduler.initialize();
        verify(catalog, never()).refresh();
    }

    @Test
    void 정상_파일이_없으면_시작시_최초_적재한다() {
        scheduler.initialize();
        verify(catalog).refresh();
    }

    @Test
    void 초기_수집과_정기_갱신_실패를_호출자에게_전파하지_않는다() {
        doThrow(new PlaceQueryProviderUnavailableException("장애")).when(catalog).refresh();
        assertThatCode(scheduler::initialize).doesNotThrowAnyException();
        assertThatCode(scheduler::refresh).doesNotThrowAnyException();
        verify(catalog, times(2)).refresh();

        doThrow(new UncheckedIOException("저장 실패", new IOException("쓰기 실패")))
                .when(catalog).refresh();
        assertThatCode(scheduler::refresh).doesNotThrowAnyException();
    }

    @Test
    void 기본_일정은_한국시간_1월과_7월이며_설정으로_변경할_수_있다() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml")).getFirst();
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(source);
        var scheduled = PlaceSearchCatalogScheduler.class.getDeclaredMethod("refresh")
                .getAnnotation(Scheduled.class);
        String cron = environment.resolveRequiredPlaceholders(scheduled.cron());
        String zone = environment.resolveRequiredPlaceholders(scheduled.zone());
        assertThat(zone).isEqualTo("Asia/Seoul");
        var start = ZonedDateTime.parse("2026-10-01T00:00:00+09:00[Asia/Seoul]");
        var first = CronExpression.parse(cron).next(start);
        assertThat(first).isEqualTo(ZonedDateTime.parse("2027-01-01T03:00:00+09:00[Asia/Seoul]"));
        assertThat(CronExpression.parse(cron).next(first))
                .isEqualTo(ZonedDateTime.parse("2027-07-01T03:00:00+09:00[Asia/Seoul]"));
        environment.setProperty("PLACE_CATALOG_REFRESH_CRON", "0 0 4 1 2,8 *");
        assertThat(environment.resolveRequiredPlaceholders(scheduled.cron())).isEqualTo("0 0 4 1 2,8 *");
        environment.setProperty("PLACE_CATALOG_PATH", "/tmp/custom-place-search.json");
        assertThat(environment.getProperty("place.catalog.path")).isEqualTo("/tmp/custom-place-search.json");
    }
}
