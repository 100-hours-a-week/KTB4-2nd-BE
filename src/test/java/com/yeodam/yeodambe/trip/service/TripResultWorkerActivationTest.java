package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.TripPhotoAnalysisService;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TripResultWorkerActivationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, TripAnalysisResultWorker.class,
                    AdditionalAttachmentResultWorker.class, TripUploadTimeoutService.class)
            .withBean(TaskScheduler.class, () -> mock(TaskScheduler.class))
            .withBean(TripAnalysisResultService.class, () -> mock(TripAnalysisResultService.class))
            .withBean(AdditionalAttachmentKafkaResultService.class, () -> mock(AdditionalAttachmentKafkaResultService.class))
            .withBean(TripPlaceNameService.class, () -> mock(TripPlaceNameService.class))
            .withBean(TripCreationExecutionRepository.class, () -> mock(TripCreationExecutionRepository.class))
            .withBean(TripRepository.class, () -> mock(TripRepository.class))
            .withBean(InitialAttachmentUploadTransactionService.class, () -> mock(InitialAttachmentUploadTransactionService.class))
            .withBean(TripPhotoAnalysisService.class, () -> mock(TripPhotoAnalysisService.class));

    @Test
    void 결과_워커를_끄면_결과와_만료_워커_및_스케줄이_생성되지_않는다() {
        context.withPropertyValues("app.trip.result-worker-enabled=false").run(result -> {
            assertThat(result).hasNotFailed().doesNotHaveBean(TripAnalysisResultWorker.class)
                    .doesNotHaveBean(AdditionalAttachmentResultWorker.class)
                    .doesNotHaveBean(TripUploadTimeoutService.class);
            assertThat(result.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
            verifyNoInteractions(result.getBean(TripAnalysisResultService.class),
                    result.getBean(AdditionalAttachmentKafkaResultService.class),
                    result.getBean(TripPlaceNameService.class),
                    result.getBean(TripCreationExecutionRepository.class),
                    result.getBean(TripRepository.class),
                    result.getBean(InitialAttachmentUploadTransactionService.class),
                    result.getBean(TripPhotoAnalysisService.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.trip.result-worker-enabled=true", "unrelated.setting=true"})
    void 결과_워커를_켜거나_설정하지_않으면_세_워커의_스케줄을_유지한다(String property) {
        context.withPropertyValues(property).run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(TripAnalysisResultWorker.class)
                    .hasSingleBean(AdditionalAttachmentResultWorker.class)
                    .hasSingleBean(TripUploadTimeoutService.class);
            assertThat(result.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(3);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingConfiguration {
    }
}
