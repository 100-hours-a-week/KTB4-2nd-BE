package com.yeodam.yeodambe.trip.event.handler;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.common.websocket.service.WebSocketEventPublisher;
import com.yeodam.yeodambe.common.websocket.service.request.*;
import com.yeodam.yeodambe.trip.entity.*;
import com.yeodam.yeodambe.trip.repository.*;
import com.yeodam.yeodambe.trip.service.TripProcessingStatusSyncService;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusNotification;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TripProcessingStatusSyncService.class, TripProcessingStatusEventHandler.class,
        TripProcessingStatusEventHandlerMysqlTest.MysqlConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripProcessingStatusEventHandlerMysqlTest {
    @Autowired private TripProcessingStatusSyncService syncService;
    @Autowired private TripCreationExecutionRepository creations;
    @Autowired private AdditionalAttachmentExecutionRepository additions;
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void publishesOnlyAfterCommitAndDoesNotRepeatAnUnchangedState(boolean creation) {
        var fixture = fixture(creation);
        var transaction = new TransactionTemplate(transactionManager);
        var snapshot = transaction.execute(status -> {
            var changed = synchronize(creation, fixture.executionId(), 1);
            synchronize(creation, fixture.executionId(), 2);
            verifyNoInteractions(publisher);
            return changed;
        });

        var captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(publisher).publish(captor.capture());
        verifyNoMoreInteractions(publisher);
        var event = captor.getValue();
        var notification = (TripProcessingStatusNotification) event.payload().data();
        assertThat(event.type()).isEqualTo(WebSocketEventType.AI_TASK);
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.target()).isEqualTo(new WebSocketEventTarget(WebSocketEventTargetType.USER, fixture.userId()));
        assertThat(event.payload().message()).isEqualTo("TRIP_PROCESSING_STATUS_FOUND");
        assertThat(notification.eventId()).isEqualTo(event.eventId());
        assertThat(notification.occurredAt()).isEqualTo(event.occurredAt());
        assertThat(notification.eventId()).isNotNull();
        assertThat(notification.occurredAt()).isNotNull();
        assertThat(notification.tripId()).isEqualTo(fixture.tripId());
        assertThat(notification.executionId()).isEqualTo(fixture.executionId());
        assertThat(notification.statusVersion()).isEqualTo(snapshot.statusVersion());
        assertThat(notification.status()).isEqualTo(TripProcessingStatusResponse.Status.PROCESSING);
        assertThat(notification.currentStep()).isEqualTo("CLUSTERING");
        assertThat(notification.result()).isNull();
        assertThat(notification.error()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void doesNotPublishWhenTheTransactionRollsBack(boolean creation) {
        var fixture = fixture(creation);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            synchronize(creation, fixture.executionId(), 1);
            status.setRollbackOnly();
        });

        verifyNoInteractions(publisher);
        assertThat(storedVersion(creation, fixture.executionId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void publicationFailureDoesNotUndoTheCommittedStateOrFailTheCaller(boolean creation) {
        var fixture = fixture(creation);
        doThrow(new IllegalStateException("redis unavailable")).when(publisher).publish(any());

        assertThatCode(() -> synchronize(creation, fixture.executionId(), 1)).doesNotThrowAnyException();

        verify(publisher).publish(any());
        assertThat(storedVersion(creation, fixture.executionId())).isEqualTo(2);
        String step = creation
                ? creations.findById(fixture.executionId()).orElseThrow().getCurrentStep()
                : additions.findById(fixture.executionId()).orElseThrow().getCurrentStep();
        assertThat(step).isEqualTo("CLUSTERING");
    }

    private TripProcessingStateSnapshot synchronize(boolean creation, String id, long version) {
        return creation
                ? syncService.synchronizeCreation(id, version, TripDisplayStatus.PROCESSING, "CLUSTERING")
                : syncService.synchronizeAddition(id, version, TripDisplayStatus.PROCESSING, "CLUSTERING");
    }

    private long storedVersion(boolean creation, String id) {
        return creation
                ? creations.findById(id).orElseThrow().getStatusVersion()
                : additions.findById(id).orElseThrow().getStatusVersion();
    }

    private Fixture fixture(boolean creation) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var user = users.save(new User(UUID.randomUUID() + "@notification.test", "알림검증"));
            var trip = trips.save(new Trip(user.getUserId(), "알림 검증", LocalDate.now(), LocalDate.now()));
            String executionId = UUID.randomUUID().toString();
            if (creation) creations.save(new TripCreationExecution(executionId, trip.getId(), user.getUserId(), 2));
            else additions.save(new AdditionalAttachmentExecution(
                    executionId, trip.getId(), user.getUserId(), 2, LocalDateTime.now()));
            return new Fixture(user.getUserId(), trip.getId(), executionId);
        });
    }

    private record Fixture(Long userId, Long tripId, String executionId) {
    }
}
