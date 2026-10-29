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
@Import({StoryGenerationProgressService.class, StoryGenerationStatusEventHandler.class,
        StoryGenerationStatusEventHandlerMysqlTest.MysqlConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StoryGenerationStatusEventHandlerMysqlTest {
    @Autowired private StoryGenerationProgressService progress;
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
