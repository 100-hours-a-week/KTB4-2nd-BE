package com.yeodam.yeodambe.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowLoggingTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(WorkflowLog.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    WorkflowLoggingTest() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void cleanUp() {
        logger.detachAppender(events);
        MDC.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void nestedAndExceptionalScopesRestoreThePreviousContext() {
        MDC.put("request_id", "request-1");
        try (var outer = WorkflowMdcScope.job("trip_creation", 42L, "job-a")) {
            assertThatThrownBy(() -> {
                try (var inner = WorkflowMdcScope.open(Map.of("job_id", "job-b"))) {
                    assertThat(MDC.get("request_id")).isNull();
                    throw new IllegalStateException();
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(MDC.get("job_id")).isEqualTo("job-a");
        }
        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("request_id", "request-1"));
    }

    @Test
    void committedStageCapturesContextAndDoesNotLogBeforeCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try (var scope = WorkflowMdcScope.job("trip_creation", 42L, "job-a")) {
            WorkflowLog.stage("outbox_persistence").committed("ai_request_enqueued");
        }
        assertThat(events.list).isEmpty();
        TransactionSynchronization callback = TransactionSynchronizationManager.getSynchronizations().getFirst();
        callback.afterCommit();
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getMDCPropertyMap()).containsEntry("job_id", "job-a");
        assertThat(MDC.get("job_id")).isNull();
    }

    @Test
    void rolledBackStageIsFailureAndNeverSuccess() {
        TransactionSynchronizationManager.initSynchronization();
        WorkflowLog.stage("outbox_persistence").committed("ai_request_enqueued");
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getKeyValuePairs())
                .anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("result");
                    assertThat(pair.value).isEqualTo("failure");
                });
    }

    @Test
    void batchSummaryIncludesStagesEvenWhenDetailedDebugLoggingIsDisabled() {
        var batch = new WorkflowLog.BatchTimings();
        WorkflowLog.collect(batch, () -> {
            try (var timing = WorkflowLog.stage("image_conversion").debug()) {
                timing.success();
            }
            return null;
        });
        batch.log();
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getKeyValuePairs()).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("sample_count");
            assertThat(pair.value).isEqualTo(1L);
        });
        batch.log();
        assertThat(events.list).hasSize(1);
    }

    @Test
    void databaseClockReferenceDoesNotDependOnJvmLocalTime() {
        TransactionSynchronizationManager.initSynchronization();
        LocalDateTime databaseNow = LocalDateTime.of(2026, 1, 1, 0, 0);
        WorkflowLog.finishedFromDatabase("trip_reanalysis", 42L, "job-a",
                databaseNow.minusSeconds(5), databaseNow, "success", null);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        assertThat(events.list.getFirst().getKeyValuePairs()).anySatisfy(pair -> {
            assertThat(pair.key).isEqualTo("duration_ms");
            assertThat((Long) pair.value).isBetween(5000L, 5999L);
        });
    }

    @Test
    void realTransactionRollbackDoesNotPublishTerminalSuccess() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:workflow-log");
        var template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        template.executeWithoutResult(status -> {
            WorkflowLog.finished("trip_creation", 42L, "job-a", LocalDateTime.now(), "success", null);
            assertThat(events.list).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(events.list).isEmpty();
        template.executeWithoutResult(status -> {
            WorkflowLog.finished("trip_creation", 42L, "job-a", LocalDateTime.now(), "success", null);
            assertThat(events.list).isEmpty();
        });
        assertThat(events.list).hasSize(1);
    }

    @Test
    void databaseCommitFailureDoesNotPublishTerminalSuccess() throws Exception {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:workflow-commit-failure");
        try (var realConnection = dataSource.getConnection()) {
            var connection = org.mockito.Mockito.spy(realConnection);
            org.mockito.Mockito.doThrow(new java.sql.SQLException("injected commit failure")).when(connection).commit();
            var singleConnection = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
            var template = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(singleConnection));
            assertThatThrownBy(() -> template.executeWithoutResult(status -> {
                WorkflowLog.finished("trip_creation", 42L, "job-a", LocalDateTime.now(), "success", null);
                WorkflowLog.stage("outbox_persistence").committed("ai_request_enqueued");
            })).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
            assertThat(events.list).hasSize(1).allSatisfy(event -> assertThat(event.getKeyValuePairs())
                    .noneSatisfy(pair -> {
                        assertThat(pair.key).isEqualTo("result");
                        assertThat(pair.value).isEqualTo("success");
                    }));
        }
    }

    @Test
    void terminalEventUsesPersistedStartAndRestoresWorkerContext() {
        TransactionSynchronizationManager.initSynchronization();
        WorkflowLog.finished("story_generation", 42L, "job-a", LocalDateTime.now().minusSeconds(5), "success", null);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getMDCPropertyMap()).containsEntry("workflow", "story_generation");
        assertThat(events.list.getFirst().getKeyValuePairs())
                .anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("duration_ms");
                    assertThat((Long) pair.value).isGreaterThanOrEqualTo(5000L);
                });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
