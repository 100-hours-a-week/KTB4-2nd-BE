package com.yeodam.yeodambe.trip.config;

import com.yeodam.yeodambe.trip.client.AttachmentConversionKafkaListener;
import com.yeodam.yeodambe.trip.client.AttachmentConversionReceiptListener;
import com.yeodam.yeodambe.trip.service.AttachmentConversionRecoveryService;
import com.yeodam.yeodambe.trip.service.AttachmentConversionTransactionService;
import com.yeodam.yeodambe.trip.service.AttachmentConversionWorker;
import com.yeodam.yeodambe.trip.service.TripAttachmentDerivativeService;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AttachmentConversionActivationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class, AttachmentConversionKafkaConfiguration.class,
                    AttachmentConversionKafkaListener.class, AttachmentConversionReceiptListener.class,
                    AttachmentConversionWorker.class, AttachmentConversionRecoveryService.class)
            .withBean("conversionService", ConversionService.class, ApplicationConversionService::new)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(KafkaListenerEndpointRegistry.class, KafkaListenerEndpointRegistry::new)
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(AttachmentConversionTransactionService.class, () -> mock(AttachmentConversionTransactionService.class))
            .withBean(TripAttachmentDerivativeService.class, () -> mock(TripAttachmentDerivativeService.class))
            .withBean(ConsumerFactory.class, () -> new DefaultKafkaConsumerFactory<>(Map.of(
                    "bootstrap.servers", "localhost:9092",
                    "key.deserializer", StringDeserializer.class,
                    "value.deserializer", StringDeserializer.class)))
            .withBean(ConcurrentKafkaListenerContainerFactoryConfigurer.class,
                    () -> new ConcurrentKafkaListenerContainerFactoryConfigurer() {
                        @Override
                        public void configure(
                                ConcurrentKafkaListenerContainerFactory<Object, Object> factory,
                                ConsumerFactory<Object, Object> consumers
                        ) {
                            factory.setConsumerFactory(consumers);
                        }
                    })
            .withPropertyValues(
                    "app.attachment-conversion.retry.technical-backoff=100ms",
                    "app.attachment-conversion.kafka.fetch-max-bytes=1048576",
                    "app.attachment-conversion.kafka.partition-fetch-max-bytes=524288",
                    "app.attachment-conversion.kafka.max-message-bytes=262144",
                    "app.attachment-conversion.admission.max-unfinished-photos.LIGHT=100",
                    "app.attachment-conversion.admission.max-unfinished-photos.HEIC=100",
                    "app.attachment-conversion.admission.max-unfinished-bytes.LIGHT=100000",
                    "app.attachment-conversion.admission.max-unfinished-bytes.HEIC=100000",
                    "app.attachment-conversion.admission.max-unfinished-age=1h",
                    "app.attachment-conversion.admission.snapshot-max-age=30s",
                    "app.attachment-conversion.admission.sample-interval=10s",
                    "app.attachment-conversion.admission.reopen-ratio=0.5",
                    "app.attachment-conversion.recovery.interval=5m",
                    "app.attachment-conversion.recovery.page-size=10",
                    "app.attachment-conversion.recovery.max-pages-per-run=2",
                    "app.attachment-conversion.recovery.max-reissues-per-run=10");

    @ParameterizedTest
    @CsvSource({
            "KAFKA,true,true,1,1,1", "KAFKA,true,false,1,0,1",
            "KAFKA,false,true,0,1,1", "KAFKA,false,false,0,0,0",
            "HOLD,true,true,0,1,1", "HOLD,true,false,0,0,0",
            "HOLD,false,true,0,1,1", "HOLD,false,false,0,0,0",
            "DB,true,true,0,0,0", "DB,true,false,0,0,0",
            "DB,false,true,0,0,0", "DB,false,false,0,0,0"
    })
    void flagsControlRealListenersWorkersAndFactoriesIndependently(
            String mode,
            boolean consumer,
            boolean receipt,
            int workCount,
            int receiptCount,
            int schedulerCount
    ) {
        context.withPropertyValues(
                "app.attachment-conversion.mode=" + mode,
                "app.attachment-conversion.consumer-enabled=" + consumer,
                "app.attachment-conversion.receipt-listener-enabled=" + receipt
        ).run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBeansOfType(AttachmentConversionKafkaListener.class)).hasSize(workCount);
            assertThat(result.getBeansOfType(AttachmentConversionWorker.class)).hasSize(workCount);
            assertThat(result.containsBean("attachmentConversionKafkaListenerContainerFactory")).isEqualTo(workCount == 1);
            assertThat(result.getBeansOfType(AttachmentConversionReceiptListener.class)).hasSize(receiptCount);
            assertThat(result.containsBean("attachmentConversionReceiptKafkaListenerContainerFactory"))
                    .isEqualTo(receiptCount == 1);
            assertThat(result.getBeansOfType(AttachmentConversionKafkaConfiguration.class)).hasSize(schedulerCount);
            assertThat(result.containsBean("attachmentConversionKafkaScheduler")).isEqualTo(schedulerCount == 1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"DB", "HOLD", "KAFKA"})
    void operationsDisableAllConversionConsumers(String mode) {
        context.withPropertyValues("app.attachment-conversion.mode=" + mode,
                "app.attachment-conversion.operations.enabled=true").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(AttachmentConversionKafkaListener.class)
                    .doesNotHaveBean(AttachmentConversionWorker.class)
                    .doesNotHaveBean(AttachmentConversionReceiptListener.class)
                    .doesNotHaveBean(AttachmentConversionKafkaConfiguration.class);
        });
    }

    @Test
    void recoveryOffDoesNotRegisterOrQueryAndKeepsKafkaAdmission() {
        context.withPropertyValues("app.attachment-conversion.mode=KAFKA",
                "app.attachment-conversion.consumer-enabled=false",
                "app.attachment-conversion.receipt-listener-enabled=false",
                "app.attachment-conversion.recovery-enabled=false").run(result -> {
            assertThat(result).hasNotFailed();
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            AttachmentConversionRecoveryService recovery = result.getBean(AttachmentConversionRecoveryService.class);
            recovery.configureTasks(registrar);
            recovery.reconcilePendingRequests();
            assertThat(registrar.getFixedDelayTaskList()).isEmpty();
            assertThat(result.getBean(AttachmentConversionProperties.class).kafkaEnabled()).isTrue();
            verifyNoInteractions(result.getBean(AttachmentConversionTransactionService.class));
        });
    }

    @Test
    void missingFlagsKeepExistingConsumersAndRecovery() {
        context.withPropertyValues("app.attachment-conversion.mode=KAFKA").run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(AttachmentConversionWorker.class)
                    .hasSingleBean(AttachmentConversionReceiptListener.class);
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            result.getBean(AttachmentConversionRecoveryService.class).configureTasks(registrar);
            assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
        });
    }

    @Test
    void recoveryOnlyDoesNotRequireKafkaFactoriesOrSizing() {
        context.withPropertyValues("app.attachment-conversion.mode=KAFKA",
                "app.attachment-conversion.consumer-enabled=false",
                "app.attachment-conversion.receipt-listener-enabled=false",
                "app.attachment-conversion.kafka.fetch-max-bytes=0",
                "app.attachment-conversion.retry.technical-backoff=").run(result -> {
            assertThat(result).hasNotFailed().doesNotHaveBean(AttachmentConversionKafkaConfiguration.class);
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            result.getBean(AttachmentConversionRecoveryService.class).configureTasks(registrar);
            assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
        });
    }

    @Test
    void receiptOnlyDoesNotRequireWorkerFetchLimits() {
        context.withPropertyValues("app.attachment-conversion.mode=KAFKA",
                "app.attachment-conversion.consumer-enabled=false",
                "app.attachment-conversion.kafka.fetch-max-bytes=0").run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(AttachmentConversionReceiptListener.class)
                    .doesNotHaveBean(AttachmentConversionWorker.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"admission.reopen-ratio=0", "recovery.page-size=0", "consumer-enabled=invalid"})
    void disabledExecutionDoesNotBypassKafkaConfigurationValidation(String setting) {
        context.withPropertyValues("app.attachment-conversion.mode=KAFKA",
                "app.attachment-conversion.consumer-enabled=false",
                "app.attachment-conversion.receipt-listener-enabled=false",
                "app.attachment-conversion.recovery-enabled=false",
                "app.attachment-conversion." + setting).run(result -> assertThat(result).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AttachmentConversionProperties.class)
    static class PropertiesConfiguration {
    }
}
