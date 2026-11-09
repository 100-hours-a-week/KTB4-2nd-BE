package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.AttachmentConversionUnavailableException;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties.Admission;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionBacklogSnapshot;
import com.yeodam.yeodambe.trip.service.response.AttachmentConversionBacklogSnapshot.Backlog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

@Service
@Slf4j
@EnableConfigurationProperties(AttachmentConversionProperties.class)
public class AttachmentConversionAdmissionService implements SchedulingConfigurer {

    private final AttachmentConversionProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LongSupplier nanoTime;
    private AttachmentConversionBacklogSnapshot snapshot;
    private boolean closed = true;

    @Autowired
    public AttachmentConversionAdmissionService(
            AttachmentConversionProperties properties,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager
    ) {
        this(properties, jdbc, transactionManager, System::nanoTime);
    }

    AttachmentConversionAdmissionService(
            AttachmentConversionProperties properties,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            LongSupplier nanoTime
    ) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.nanoTime = nanoTime;
        this.transactions = new TransactionTemplate(transactionManager);
        transactions.setReadOnly(true);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public synchronized void assertNewExecutionAllowed() {
        Decision decision = evaluate(snapshot, age());
        if (decision != Decision.ALLOWED) {
            log.atWarn()
                    .addKeyValue("event", "attachment_conversion_admission_rejected")
                    .addKeyValue("reason", decision.name())
                    .log("이미지 변환 신규 접수를 거절했습니다.");
            throw new AttachmentConversionUnavailableException();
        }
    }

    public void sampleBacklog() {
        if (!properties.kafkaEnabled()) {
            return;
        }
        long startedAt = nanoTime.getAsLong();
        Instant sampledAt = Instant.now();
        synchronized (this) {
            evaluate(snapshot, age());
        }
        try {
            AttachmentConversionBacklogSnapshot sampled = transactions.execute(status -> readSnapshot(sampledAt, startedAt));
            synchronized (this) {
                if (snapshot == null || startedAt - snapshot.sampledAtNanos() >= 0) {
                    snapshot = sampled;
                    evaluate(snapshot, age());
                }
            }
        } catch (DataAccessException | IllegalArgumentException failure) {
            log.warn("이미지 변환 적체 관측 실패 stage=sample_backlog cause={}", failure.getClass().getSimpleName());
            synchronized (this) {
                evaluate(snapshot, age());
            }
        }
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (properties.kafkaEnabled()) {
            registrar.addFixedDelayTask(this::sampleBacklog, properties.admission().sampleInterval());
        }
    }

    synchronized Decision evaluate(AttachmentConversionBacklogSnapshot current, Duration age) {
        if (properties.mode() == AttachmentConversionRoute.DB) {
            return Decision.ALLOWED;
        }
        if (properties.mode() == AttachmentConversionRoute.HOLD) {
            return reject(Decision.HOLD);
        }
        if (current == null || age == null || age.isNegative() || current.lanes() == null
                || current.migrationPendingBatches() < 0) {
            return reject(Decision.UNAVAILABLE);
        }
        Admission limits = properties.admission();
        if (age.compareTo(limits.snapshotMaxAge()) >= 0) {
            return reject(Decision.STALE);
        }
        if (current.migrationPendingBatches() > 0) {
            return reject(Decision.MIGRATION_INCOMPLETE);
        }
        boolean belowReopenLimits = true;
        for (Lane lane : Lane.values()) {
            Backlog backlog = current.lanes().get(lane);
            if (backlog == null || backlog.remainingPhotos() < 0 || backlog.remainingBytes() < 0
                    || backlog.oldestUnfinishedAge() == null || backlog.oldestUnfinishedAge().isNegative()) {
                return reject(Decision.UNAVAILABLE);
            }
            if (backlog.remainingPhotos() >= limits.maxUnfinishedPhotos().get(lane)
                    || backlog.remainingBytes() >= limits.maxUnfinishedBytes().get(lane)
                    || backlog.oldestUnfinishedAge().compareTo(limits.maxUnfinishedAge()) >= 0) {
                return reject(Decision.OVERLOADED);
            }
            belowReopenLimits &= belowReopenLimit(BigDecimal.valueOf(backlog.remainingPhotos()),
                    BigDecimal.valueOf(limits.maxUnfinishedPhotos().get(lane)))
                    && belowReopenLimit(BigDecimal.valueOf(backlog.remainingBytes()),
                    BigDecimal.valueOf(limits.maxUnfinishedBytes().get(lane)))
                    && belowReopenLimit(seconds(backlog.oldestUnfinishedAge()), seconds(limits.maxUnfinishedAge()));
        }
        if (closed && !belowReopenLimits) {
            return reject(Decision.OVERLOADED);
        }
        closed = false;
        return Decision.ALLOWED;
    }

    private AttachmentConversionBacklogSnapshot readSnapshot(Instant sampledAt, long startedAt) {
        Map<Lane, Backlog> lanes = new EnumMap<>(Lane.class);
        for (Lane lane : Lane.values()) {
            lanes.put(lane, new Backlog(0, 0, Duration.ZERO));
        }
        jdbc.query("""
                SELECT lane, SUM(remaining_photos) AS remaining_photos, SUM(remaining_bytes) AS remaining_bytes,
                    GREATEST(0, TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6))) AS oldest_age_micros
                FROM attachment_conversion_tasks WHERE status IN ('WAITING', 'RUNNING') GROUP BY lane
                """, (row, index) -> Map.entry(Lane.valueOf(row.getString("lane")),
                new Backlog(row.getLong("remaining_photos"), row.getLong("remaining_bytes"),
                        Duration.of(row.getLong("oldest_age_micros"), ChronoUnit.MICROS))))
                .forEach(entry -> lanes.put(entry.getKey(), entry.getValue()));
        Long migrationPending = jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM initial_attachment_upload_batches batch
                        JOIN trip_creation_executions execution ON execution.execution_id = batch.execution_id
                        WHERE execution.ended_at IS NULL AND batch.conversion_route IN ('DB', 'HOLD')
                            AND batch.status IN ('ACCEPTED', 'CONVERTING'))
                    + (SELECT COUNT(*) FROM additional_attachment_upload_batches batch
                        JOIN additional_attachment_executions execution ON execution.execution_id = batch.addition_id
                        WHERE execution.ended_at IS NULL AND batch.conversion_route IN ('DB', 'HOLD')
                            AND batch.status IN ('VERIFIED', 'CONVERTING'))
                """, Long.class);
        return new AttachmentConversionBacklogSnapshot(sampledAt, startedAt, migrationPending, Map.copyOf(lanes));
    }

    private Duration age() {
        return snapshot == null ? Duration.ZERO : Duration.ofNanos(nanoTime.getAsLong() - snapshot.sampledAtNanos());
    }

    private Decision reject(Decision decision) {
        closed = true;
        return decision;
    }

    private boolean belowReopenLimit(BigDecimal value, BigDecimal limit) {
        return value.compareTo(limit.multiply(BigDecimal.valueOf(properties.admission().reopenRatio()))) < 0;
    }

    private BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    enum Decision {
        ALLOWED,
        OVERLOADED,
        STALE,
        UNAVAILABLE,
        HOLD,
        MIGRATION_INCOMPLETE
    }
}
