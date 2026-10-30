package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.SharedTestMysql;
import com.yeodam.yeodambe.trip.entity.AdditionalAttachmentExecution;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.entity.TripDisplayStatus;
import com.yeodam.yeodambe.trip.repository.AdditionalAttachmentExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStateSnapshot;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TripProcessingStatusSyncService.class, TripProcessingStatusSyncMysqlTest.MysqlConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TripProcessingStatusSyncMysqlTest {
    @Autowired private TripProcessingStatusSyncService service;
    @Autowired private TripCreationExecutionRepository creations;
    @Autowired private AdditionalAttachmentExecutionRepository additions;
    @Autowired private TripRepository trips;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @TestConfiguration(proxyBeanMethods = false)
    static class MysqlConfig {
        @Bean
        JdbcConnectionDetails mysql() throws SQLException {
            return SharedTestMysql.createDatabase();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentResponsesBasedOnTheSameVersionCommitOnlyOneChange(boolean creation) throws Exception {
        String id = fixture(creation);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return synchronize(creation, id, "EMBEDDING");
            });
            var second = pool.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return synchronize(creation, id, "CLUSTERING");
            });
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);

            assertThat(a.statusVersion()).isEqualTo(2);
            assertThat(b).isEqualTo(a);
            assertThat(a.currentStep()).isIn("EMBEDDING", "CLUSTERING");
            assertThat(synchronize(creation, id, "OLD_RESPONSE")).isEqualTo(a);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void refreshSeesCompletionCommittedAfterTheEntityWasAlreadyLoaded(boolean creation) throws Exception {
        String id = fixture(creation);
        var transaction = new TransactionTemplate(transactionManager);
        var entityManager = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(
                entityManagerFactory, new EntityManagerHolder(entityManager));
        try (var pool = Executors.newSingleThreadExecutor()) {
            Object loaded = transaction.execute(status -> creation
                    ? creations.findById(id).orElseThrow()
                    : additions.findById(id).orElseThrow());
            assertThat(entityManager.contains(loaded)).isTrue();

            pool.submit(() -> transaction.executeWithoutResult(ignored -> {
                if (creation) {
                    creations.findForUpdate(id).orElseThrow().finish(
                            TripCreationExecution.Status.COMPLETED, null, LocalDateTime.now());
                } else {
                    additions.findForUpdate(id).orElseThrow().finish(
                            AdditionalAttachmentExecution.Status.COMPLETED, null, LocalDateTime.now());
                }
            })).get(15, TimeUnit.SECONDS);

            TripDisplayStatus staleStatus = creation
                    ? ((TripCreationExecution) loaded).getDisplayStatus()
                    : ((AdditionalAttachmentExecution) loaded).getDisplayStatus();
            assertThat(staleStatus).isEqualTo(TripDisplayStatus.PROCESSING);

            var snapshot = synchronize(creation, id, "EMBEDDING");

            assertThat(snapshot.displayStatus()).isEqualTo(TripDisplayStatus.COMPLETED);
            assertThat(snapshot.statusVersion()).isEqualTo(2);
            assertThat(snapshot.currentStep()).isNull();
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            entityManager.close();
        }
    }

    private TripProcessingStateSnapshot synchronize(boolean creation, String id, String step) {
        return creation
                ? service.synchronizeCreation(id, 1, TripDisplayStatus.PROCESSING, step)
                : service.synchronizeAddition(id, 1, TripDisplayStatus.PROCESSING, step);
    }

    private String fixture(boolean creation) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var owner = users.save(new User(UUID.randomUUID() + "@version.test", "상태검증"));
            var trip = trips.save(new Trip(owner.getUserId(), "상태 검증", LocalDate.now(), LocalDate.now()));
            String id = UUID.randomUUID().toString();
            if (creation) creations.save(new TripCreationExecution(id, trip.getId(), owner.getUserId(), 2));
            else additions.save(new AdditionalAttachmentExecution(
                    id, trip.getId(), owner.getUserId(), 2, LocalDateTime.now()));
            return id;
        });
    }
}
