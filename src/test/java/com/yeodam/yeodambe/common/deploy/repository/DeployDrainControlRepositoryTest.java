package com.yeodam.yeodambe.common.deploy.repository;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DeployDrainControlRepositoryTest {
    @Autowired private DeployDrainControlRepository controls;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    @BeforeEach
    @AfterEach
    void resetControl() {
        jdbc.update("UPDATE deploy_drain_control SET draining = FALSE WHERE control_id = 1");
    }

    @Test
    void migrationSeedsOneControlAndRejectsAnotherId() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(controls.findAll()).singleElement().satisfies(control -> {
            assertThat(control.getId()).isEqualTo(1L);
            assertThat(control.isDraining()).isFalse();
            assertThat(control.getUpdatedAt()).isNotNull();
        });
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO deploy_drain_control (control_id, draining) VALUES (2, FALSE)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_deploy_drain_control_singleton")
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void persistsBlockingAndUnblockingInSeparateTransactions() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> controls.findForUpdate().orElseThrow()
                .changeDraining(true));
        assertThat(controls.findById(1L).orElseThrow().isDraining()).isTrue();

        transaction.executeWithoutResult(status -> controls.findForUpdate().orElseThrow()
                .changeDraining(false));
        assertThat(controls.findById(1L).orElseThrow().isDraining()).isFalse();
    }

    @Test
    void competingTransactionCannotAcquireControlUntilLockIsReleased() throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                controls.findForUpdate().orElseThrow().changeDraining(true);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test lock release timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> {
                try {
                    return new TransactionTemplate(transactionManager).execute(status -> {
                        entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 1")
                                .executeUpdate();
                        try {
                            controls.findForUpdate().orElseThrow();
                            return false;
                        } catch (PessimisticLockingFailureException failure) {
                            status.setRollbackOnly();
                            return true;
                        } finally {
                            entityManager.createNativeQuery("SET SESSION innodb_lock_wait_timeout = 50")
                                    .executeUpdate();
                        }
                    });
                } catch (RuntimeException failure) {
                    throw failure;
                }
            });
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);

            Boolean draining = transaction.execute(
                    status -> controls.findForUpdate().orElseThrow().isDraining());
            assertThat(draining).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
