package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.repository.StoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryContentMigrationStartupTest {
    @TestConfiguration(proxyBeanMethods = false)
    @Import(StoryContentMigrationRunner.class)
    static class StartupConfig {
        @Bean
        ApplicationAvailabilityBean availability() {
            return new ApplicationAvailabilityBean();
        }
    }

    @Test
    void readinessRemainsRefusingUntilRunnerCompletes() {
        var stories = mock(StoryRepository.class);
        var migration = mock(StoryContentMigrationService.class);
        when(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100)))
                .thenReturn(List.of(10L));
        var context = new AtomicReference<ConfigurableApplicationContext>();
        var readyEvent = new AtomicBoolean();
        var observed = new AtomicReference<ReadinessState>();
        var app = application(stories, migration, context, readyEvent);
        doAnswer(invocation -> {
            observed.set(context.get().getBean(ApplicationAvailability.class).getReadinessState());
            assertThat(readyEvent).isFalse();
            return null;
        }).when(migration).migrateOne(10L);

        try (var started = app.run(arguments())) {
            assertThat(observed.get()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
            assertThat(readyEvent).isTrue();
            assertThat(started.getBean(ApplicationAvailability.class).getReadinessState())
                    .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
            verify(migration).migrateOne(10L);
        }
    }

    @Test
    void migrationFailurePreventsApplicationReadyEvent() {
        var stories = mock(StoryRepository.class);
        var migration = mock(StoryContentMigrationService.class);
        when(stories.findContentMigrationTargetIds(0L, PageRequest.of(0, 100)))
                .thenReturn(List.of(10L));
        var context = new AtomicReference<ConfigurableApplicationContext>();
        var readyEvent = new AtomicBoolean();
        var failure = new IllegalStateException("이관 실패");
        var app = application(stories, migration, context, readyEvent);
        doAnswer(invocation -> {
            assertThat(context.get().getBean(ApplicationAvailability.class).getReadinessState())
                    .isEqualTo(ReadinessState.REFUSING_TRAFFIC);
            throw failure;
        }).when(migration).migrateOne(10L);

        assertThatThrownBy(() -> app.run(arguments())).isSameAs(failure);
        assertThat(readyEvent).isFalse();
    }

    private SpringApplication application(
            StoryRepository stories,
            StoryContentMigrationService migration,
            AtomicReference<ConfigurableApplicationContext> context,
            AtomicBoolean readyEvent
    ) {
        var app = new SpringApplication(StartupConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        app.setLogStartupInfo(false);
        app.addInitializers(applicationContext -> {
            context.set(applicationContext);
            applicationContext.getBeanFactory().registerSingleton("storyRepository", stories);
            applicationContext.getBeanFactory().registerSingleton("migrationService", migration);
        });
        app.addListeners((ApplicationListener<ApplicationReadyEvent>) event -> readyEvent.set(true));
        return app;
    }

    private String[] arguments() {
        return new String[] {
                "--spring.config.name=story-migration-startup-test",
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR"
        };
    }
}
