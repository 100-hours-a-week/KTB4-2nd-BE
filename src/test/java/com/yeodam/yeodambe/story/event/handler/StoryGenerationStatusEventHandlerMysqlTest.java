package com.yeodam.yeodambe.story.event.handler;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.*;
import com.yeodam.yeodambe.story.client.response.StoryGenerationAiResponse;
import com.yeodam.yeodambe.story.entity.Story;
import com.yeodam.yeodambe.story.entity.StoryGenerationJob;
import com.yeodam.yeodambe.story.repository.StoryGenerationJobRepository;
import com.yeodam.yeodambe.story.service.StoryGenerationProgressService;
import com.yeodam.yeodambe.story.service.response.StoryGenerationStatusNotification;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.yeodam.yeodambe.story.service.StoryGenerationTerminalService;
import com.yeodam.yeodambe.story.service.StoryGenerationTimeoutService;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import com.yeodam.yeodambe.story.repository.StoryGenerationOutboxRepository;
import java.time.LocalDateTime;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StoryGenerationTimeoutService.class, StoryGenerationTerminalService.class, StoryGenerationProgressService.class, StoryGenerationStatusEventHandler.class,
        StoryGenerationStatusEventHandlerMysqlTest.MysqlConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoryGenerationStatusEventHandlerMysqlTest {
    @Autowired private StoryGenerationProgressService progress;
    @Autowired private StoryGenerationTerminalService terminal;
    @Autowired private StoryGenerationTimeoutService timeout;
    @Autowired private StoryGenerationOutboxRepository outboxes;
    @Autowired private StoryGenerationJobRepository jobs;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private WebSocketEventPublisher publisher;

    @TestConfiguration(proxyBeanMethods = false)
    static class MysqlConfig {
        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }
    }

    @Test
    void publishesOnlyAfterCommitAndDoesNotRepeatPercentageChanges() {
        var job = fixture();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            update(job, 1);
            update(job, 3);
            verifyNoInteractions(publisher);
        });

        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).publish(captor.capture());
        verifyNoMoreInteractions(publisher);
        var event = captor.getValue();
        var notification = (StoryGenerationStatusNotification) event.payload().data();
        assertThat(event.type()).isEqualTo(WebSocketEventType.AI_TASK);
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.target()).isEqualTo(new WebSocketEventTarget(WebSocketEventTargetType.USER, job.getUserId()));
        assertThat(event.payload().message()).isEqualTo("STORY_GENERATION_STATUS_FOUND");
        assertThat(notification.eventId()).isNotNull().isEqualTo(event.eventId());
        assertThat(notification.occurredAt()).isNotNull().isEqualTo(event.occurredAt());
        assertThat(notification.tripId()).isEqualTo(job.getTripId());
        assertThat(notification.generationId()).isEqualTo(job.getId());
        assertThat(notification.statusVersion()).isEqualTo(2);
        assertThat(notification.status()).isEqualTo(StoryGenerationJob.Status.PROCESSING);
        assertThat(notification.storyId()).isNull();
        assertThat(notification.error()).isNull();
        assertThat(jobs.findById(job.getId()).orElseThrow().getProgress()).isEqualTo(75);
    }

    @Test
    void rollbackDoesNotPublishAndPreservesQueuedState() {
        var job = fixture();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            update(job, 1);
            status.setRollbackOnly();
        });

        verifyNoInteractions(publisher);
        var stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(stored.getStatusVersion()).isEqualTo(1);
    }

    @Test
    void redisFailureDoesNotUndoCommittedStateOrFailCaller() {
        var job = fixture();
        doThrow(new IllegalStateException("redis unavailable")).when(publisher).publish(any());

        assertThatCode(() -> update(job, 1)).doesNotThrowAnyException();

        verify(publisher).publish(any());
        var stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(StoryGenerationJob.Status.PROCESSING);
        assertThat(stored.getStatusVersion()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELED", "USER_CANCEL"})
    void terminalChangePublishesAfterCommitAndIgnoresRedelivery(String mode) {
        var job = fixture();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            terminate(job, mode);
            verifyNoInteractions(publisher);
        });
        terminate(job, mode);

        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).publish(captor.capture());
        verifyNoMoreInteractions(publisher);
        var notification = (StoryGenerationStatusNotification) captor.getValue().payload().data();
        assertThat(notification.generationId()).isEqualTo(job.getId());
        assertThat(notification.statusVersion()).isEqualTo(2);
        if (mode.equals("FAILED")) {
            assertThat(notification.status()).isEqualTo(StoryGenerationJob.Status.FAILED);
            assertThat(notification.error().code()).isEqualTo("STORY_GENERATION_FAILED");
            assertThat(notification.error().message()).isEqualTo(
                    com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage.GENERATION_FAILED.message());
            assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode()).isEqualTo("WORKER_DEAD");
        } else {
            assertThat(notification.status()).isEqualTo(StoryGenerationJob.Status.CANCELED);
            assertThat(notification.error()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELED", "USER_CANCEL"})
    void rolledBackTerminalChangeDoesNotPublish(String mode) {
        var job = fixture();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            terminate(job, mode);
            status.setRollbackOnly();
        });
        verifyNoInteractions(publisher);
        var stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(stored.getStatusVersion()).isEqualTo(1);
    }

    @Test
    void timeoutPublishesAfterCommitOnceAndKeepsDiagnosticCodeInDatabase() {
        var job = fixture();
        var publishedAt = LocalDateTime.of(2026, 10, 29, 10, 0);
        prepareTimeout(job, publishedAt);
        assertThat(timeout.expire(job.getId(), publishedAt.plusHours(1).minusSeconds(1))).isFalse();
        verifyNoInteractions(publisher);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(timeout.expire(job.getId(), publishedAt.plusHours(1))).isTrue();
            verifyNoInteractions(publisher);
        });
        assertThat(timeout.expire(job.getId(), publishedAt.plusHours(2))).isTrue();

        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).publish(captor.capture());
        verifyNoMoreInteractions(publisher);
        var notification = (StoryGenerationStatusNotification) captor.getValue().payload().data();
        assertThat(notification.status()).isEqualTo(StoryGenerationJob.Status.FAILED);
        assertThat(notification.generationId()).isEqualTo(job.getId());
        assertThat(notification.statusVersion()).isEqualTo(2);
        assertThat(notification.error().code()).isEqualTo("STORY_GENERATION_FAILED");
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorCode()).isEqualTo("STORY_GENERATION_TIMEOUT");
    }

    @Test
    void timeoutRollbackDoesNotPublishOrPersistFailure() {
        var job = fixture();
        var publishedAt = LocalDateTime.of(2026, 10, 29, 10, 0);
        prepareTimeout(job, publishedAt);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(timeout.expire(job.getId(), publishedAt.plusHours(1))).isTrue();
            status.setRollbackOnly();
        });
        verifyNoInteractions(publisher);
        var stored = jobs.findById(job.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(StoryGenerationJob.Status.QUEUED);
        assertThat(stored.getStatusVersion()).isEqualTo(1);
        assertThat(stored.getErrorCode()).isNull();
    }

    private void prepareTimeout(StoryGenerationJob job, LocalDateTime publishedAt) {
        var outbox = new StoryGenerationOutbox(job.getId(), "{}", "timeout-test");
        outbox.publicationSucceeded(publishedAt);
        outboxes.saveAndFlush(outbox);
    }

    private void terminate(StoryGenerationJob job, String mode) {
        if (mode.equals("USER_CANCEL")) {
            terminal.confirmCancellation(job.getUserId(), job.getId());
            return;
        }
        terminal.apply(job.getId(), new StoryGenerationAiResponse(
                job.getTripId(), job.getExecutionId(), StoryGenerationAiResponse.Status.valueOf(mode),
                null, null, null, mode.equals("FAILED")
                ? new StoryGenerationAiResponse.Failure("WORKER_DEAD", "diagnostic", null) : null));
    }

    private void update(StoryGenerationJob job, int done) {
        progress.updateProcessing(job.getId(), new StoryGenerationAiResponse(
                job.getTripId(), job.getExecutionId(), StoryGenerationAiResponse.Status.PROCESSING,
                new StoryGenerationAiResponse.Progress(done, 4), "WRITING", null, null));
    }

    private StoryGenerationJob fixture() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var user = users.save(new User(UUID.randomUUID() + "@story-notification.test", "알림검증"));
            var trip = trips.save(new Trip(user.getUserId(), "알림 검증", LocalDate.now(), LocalDate.now()));
            return jobs.saveAndFlush(new StoryGenerationJob(
                    UUID.randomUUID().toString(), trip.getId(), user.getUserId(), Story.Mood.PLAIN));
        });
    }
}
