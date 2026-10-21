package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.trip.service.*;
import com.yeodam.yeodambe.user.security.AuthDataCleanupScheduler;
import com.yeodam.yeodambe.user.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MigrationSchedulerRoleTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TripObjectCleanupScheduler.class, AuthDataCleanupScheduler.class, PlaceSearchCatalogScheduler.class)
            .withBean(TripObjectCleanupService.class, () -> mock(TripObjectCleanupService.class))
            .withBean(PlaceSearchCatalog.class, () -> mock(PlaceSearchCatalog.class))
            .withBean(OAuthStateRepository.class, () -> mock(OAuthStateRepository.class))
            .withBean(LoginTicketRepository.class, () -> mock(LoginTicketRepository.class))
            .withBean(ProfileTokenRepository.class, () -> mock(ProfileTokenRepository.class))
            .withBean(CsrfTokenRepository.class, () -> mock(CsrfTokenRepository.class))
            .withBean(LoginSessionRepository.class, () -> mock(LoginSessionRepository.class));

    @Test
    void 일반_서비스는_기존_주기작업을_유지한다() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TripObjectCleanupScheduler.class);
            assertThat(context).hasSingleBean(AuthDataCleanupScheduler.class);
            assertThat(context).hasSingleBean(PlaceSearchCatalogScheduler.class);
        });
    }

    @Test
    void 전용_요청_실행기는_일반_서비스_주기작업을_중복_실행하지_않는다() {
        runner.withPropertyValues("migration.service-schedulers-enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TripObjectCleanupScheduler.class);
            assertThat(context).doesNotHaveBean(AuthDataCleanupScheduler.class);
            assertThat(context).doesNotHaveBean(PlaceSearchCatalogScheduler.class);
        });
    }
}
