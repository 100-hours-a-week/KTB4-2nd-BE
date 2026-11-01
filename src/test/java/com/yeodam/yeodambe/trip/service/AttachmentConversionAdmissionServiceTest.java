package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentConversionUnavailableException;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Admission;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Operations;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.service.AttachmentConversionAdmissionService.Decision;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionBacklogSnapshot;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionBacklogSnapshot.Backlog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AttachmentConversionAdmissionServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final AtomicLong nanos = new AtomicLong();
    private AttachmentConversionAdmissionService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = service(AttachmentConversionRoute.KAFKA);
    }

    @Test
    void staleOrMissingSnapshotRejectsOnlyNewExecution() {
        assertThat(service.evaluate(null, Duration.ZERO)).isEqualTo(Decision.UNAVAILABLE);
        assertThat(service.evaluate(snapshot(70, 0, Duration.ZERO, 0), Duration.ZERO))
                .isEqualTo(Decision.OVERLOADED);
        assertThat(service.evaluate(snapshot(49, 0, Duration.ZERO, 0), Duration.ZERO))
                .isEqualTo(Decision.ALLOWED);
        assertThat(service.evaluate(snapshot(0, 0, Duration.ZERO, 0), Duration.ofSeconds(10)))
                .isEqualTo(Decision.STALE);
        assertThat(service.evaluate(snapshot(70, 0, Duration.ZERO, 0), Duration.ZERO))
                .isEqualTo(Decision.OVERLOADED);
        assertThat(service.evaluate(snapshot(49, 0, Duration.ZERO, 0), Duration.ZERO))
                .isEqualTo(Decision.ALLOWED);
    }

    @ParameterizedTest
    @CsvSource({"LIGHT,PHOTOS", "HEIC,PHOTOS", "LIGHT,BYTES", "HEIC,BYTES", "LIGHT,AGE", "HEIC,AGE"})
    void eitherLaneClosesAtLimitAndReopensStrictlyBelowEveryLowerLimit(Lane lane, String metric) {
        assertThat(service.evaluate(snapshot(0, 0, Duration.ZERO, 0), Duration.ZERO))
                .isEqualTo(Decision.ALLOWED);
        assertThat(service.evaluate(metricSnapshot(lane, metric, 100), Duration.ZERO))
                .isEqualTo(Decision.OVERLOADED);
        assertThat(service.evaluate(metricSnapshot(lane, metric, 50), Duration.ZERO))
                .isEqualTo(Decision.OVERLOADED);
        assertThat(service.evaluate(metricSnapshot(lane, metric, 49), Duration.ZERO))
                .isEqualTo(Decision.ALLOWED);
    }

    @Test
    void migrationPendingAndInvalidObservationsFailClosed() {
        assertThat(service.evaluate(snapshot(0, 0, Duration.ZERO, 1), Duration.ZERO))
                .isEqualTo(Decision.MIGRATION_INCOMPLETE);
        assertThat(service.evaluate(new AttachmentConversionBacklogSnapshot(Instant.EPOCH, 0, 0,
                Map.of(Lane.LIGHT, new Backlog(0, 0, Duration.ZERO))), Duration.ZERO))
                .isEqualTo(Decision.UNAVAILABLE);
        assertThat(service.evaluate(snapshot(-1, 0, Duration.ZERO, 0), Duration.ZERO))
                .isEqualTo(Decision.UNAVAILABLE);
    }

    @Test
    void legacyDbModeRemainsCompatible() {
        AttachmentConversionAdmissionService db = service(AttachmentConversionRoute.DB);
        assertThatCode(db::assertNewExecutionAllowed).doesNotThrowAnyException();
        db.sampleBacklog();
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        db.configureTasks(registrar);
        assertThat(registrar.getFixedDelayTaskList()).isEmpty();
        verifyNoInteractions(jdbc, transactionManager);
        assertThatThrownBy(service(AttachmentConversionRoute.HOLD)::assertNewExecutionAllowed)
                .isInstanceOf(AttachmentConversionUnavailableException.class);
    }

    @Test
    void snapshotQueryFailureDoesNotRefreshAge() throws Exception {
        observeEmptyBacklog();
        service.sampleBacklog();
        assertThatCode(service::assertNewExecutionAllowed).doesNotThrowAnyException();
        nanos.set(Duration.ofSeconds(9).toNanos());
        when(jdbc.query(anyString(), any(RowMapper.class)))
                .thenThrow(new TransientDataAccessResourceException("unavailable"));
        assertThatCode(service::sampleBacklog).doesNotThrowAnyException();
        assertThatCode(service::assertNewExecutionAllowed).doesNotThrowAnyException();
        nanos.set(Duration.ofSeconds(10).toNanos());
        assertThatThrownBy(service::assertNewExecutionAllowed)
                .isInstanceOf(AttachmentConversionUnavailableException.class);
    }

    @Test
    void samplingDoesNotHideQueryLatency() throws Exception {
        observeEmptyBacklog();
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenAnswer(call -> {
            nanos.set(Duration.ofSeconds(11).toNanos());
            return 0L;
        });
        service.sampleBacklog();
        assertThatThrownBy(service::assertNewExecutionAllowed)
                .isInstanceOf(AttachmentConversionUnavailableException.class);
    }

    @Test
    void configurationDefaultsToDbAndRequiresExplicitKafkaAdmissionLimits() {
        ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);
        context.run(result -> {
            assertThat(result).hasNotFailed();
            AttachmentConversionProperties properties = result.getBean(AttachmentConversionProperties.class);
            assertThat(properties.mode()).isEqualTo(AttachmentConversionRoute.DB);
            assertThat(properties.kafkaEnabled()).isFalse();
            assertThat(properties.receiptEnabled()).isFalse();
        });
        context.withPropertyValues("app.attachment-conversion.mode=KAFKA").run(result ->
                assertThat(result).hasFailed());
        kafkaContext().run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result.getBean(AttachmentConversionProperties.class).kafkaEnabled()).isTrue();
        });
        kafkaContext().withPropertyValues("app.attachment-conversion.operations.enabled=true").run(result -> {
            AttachmentConversionProperties properties = result.getBean(AttachmentConversionProperties.class);
            assertThat(properties.kafkaEnabled()).isFalse();
            assertThat(properties.receiptEnabled()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "max-unfinished-photos.LIGHT=0", "max-unfinished-bytes.HEIC=-1",
            "max-unfinished-age=0s", "snapshot-max-age=0s", "sample-interval=0s",
            "sample-interval=11s", "reopen-ratio=0", "reopen-ratio=1", "reopen-ratio=NaN"
    })
    void rejectsInvalidOrContradictoryKafkaSettings(String setting) {
        kafkaContext().withPropertyValues("app.attachment-conversion.admission." + setting)
                .run(result -> assertThat(result).hasFailed());
    }

    private ApplicationContextRunner kafkaContext() {
        return new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class).withPropertyValues(
                "app.attachment-conversion.mode=KAFKA",
                "app.attachment-conversion.admission.max-unfinished-photos.LIGHT=100",
                "app.attachment-conversion.admission.max-unfinished-photos.HEIC=100",
                "app.attachment-conversion.admission.max-unfinished-bytes.LIGHT=1000",
                "app.attachment-conversion.admission.max-unfinished-bytes.HEIC=1000",
                "app.attachment-conversion.admission.max-unfinished-age=100s",
                "app.attachment-conversion.admission.snapshot-max-age=10s",
                "app.attachment-conversion.admission.sample-interval=1s",
                "app.attachment-conversion.admission.reopen-ratio=0.5");
    }

    private AttachmentConversionAdmissionService service(AttachmentConversionRoute mode) {
        return new AttachmentConversionAdmissionService(new AttachmentConversionProperties(mode,
                new Admission(Map.of(Lane.LIGHT, 100L, Lane.HEIC, 100L),
                        Map.of(Lane.LIGHT, 1000L, Lane.HEIC, 1000L),
                        Duration.ofSeconds(100), Duration.ofSeconds(10), Duration.ofSeconds(1), 0.5),
                new Operations(false)), jdbc, transactionManager, nanos::get);
    }

    private void observeEmptyBacklog() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("lane")).thenReturn("LIGHT");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call ->
                List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)));
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
    }

    private AttachmentConversionBacklogSnapshot snapshot(long photos, long bytes, Duration age, long migration) {
        return new AttachmentConversionBacklogSnapshot(Instant.EPOCH, 0, migration,
                Map.of(Lane.LIGHT, new Backlog(photos, bytes, age),
                        Lane.HEIC, new Backlog(0, 0, Duration.ZERO)));
    }

    private AttachmentConversionBacklogSnapshot metricSnapshot(Lane lane, String metric, long percent) {
        Backlog busy = new Backlog(metric.equals("PHOTOS") ? percent : 0,
                metric.equals("BYTES") ? percent * 10 : 0,
                metric.equals("AGE") ? Duration.ofSeconds(percent) : Duration.ZERO);
        return new AttachmentConversionBacklogSnapshot(Instant.EPOCH, 0, 0,
                Map.of(lane, busy, lane == Lane.LIGHT ? Lane.HEIC : Lane.LIGHT, new Backlog(0, 0, Duration.ZERO)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AttachmentConversionProperties.class)
    static class PropertiesConfig {
    }
}
