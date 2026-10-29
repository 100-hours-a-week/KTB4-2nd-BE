package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class MigrationTripConsumer {
    @org.springframework.beans.factory.annotation.Value("${migration.trip.apply-role:unknown}")
    private String applyRole;
    private final ObjectMapper mapper;
    private final MigrationTripAdmissionService admission;
    private final MigrationTripApplyService applyService;
    private final MigrationTripBatchService batches;

    public MigrationTripConsumer(
            ObjectMapper mapper,
            MigrationTripAdmissionService admission,
            MigrationTripApplyService applyService,
            MigrationTripBatchService batches
    ) {
        this.mapper = mapper;
        this.admission = admission;
        this.applyService = applyService;
        this.batches = batches;
    }

    @KafkaListener(
            id = "migrationTripApply",
            topics = "${migration.trip.topic}",
            groupId = "${MIGRATION_TRIP_CONSUMER_GROUP:yeodam-migration-trip-apply}",
            containerFactory = "migrationTripListenerFactory",
            autoStartup = "${migration.trip.consumer-enabled:false}"
    )
    public void consumeBatch(List<ConsumerRecord<String, String>> records) {
        if (records.isEmpty()) return;
        if (records.size() > MigrationTripBatchService.MAX_RECORDS) {
            throw new IllegalStateException("Migration poll exceeded transaction bound");
        }
        var partitions = new LinkedHashMap<TopicPartition, List<MigrationTripCommand>>();
        var lastOffsets = new LinkedHashMap<TopicPartition, Long>();
        // Validate the whole delivery before any DB effect. Invalid records are never skipped.
        for (var record : records) {
            var partition = new TopicPartition(record.topic(), record.partition());
            Long previous = lastOffsets.put(partition, record.offset());
            if (previous != null && previous >= record.offset()) {
                throw new IllegalStateException("Migration partition offset order differs");
            }
            partitions.computeIfAbsent(partition, ignored -> new ArrayList<>())
                    .add(decode(record.value(), record.key()));
        }
        for (var commands : partitions.values()) {
            try {
                var created = batches.applyBatch(commands);
                // The batch service proxy has committed before any completion observation.
                for (var command : created) observeCompletion(command);
            } catch (TripNameDuplicatedException | InvalidTripRequestException businessFailure) {
                // The batch TX has fully rolled back. Reapply in original partition order,
                // retaining the existing durable FAILED result for permanent business errors.
                for (var command : commands) applyOne(command);
            }
        }
        // BATCH ack happens only after every partition returns. DB-committed work can be
        // replayed after an offset failure/crash and is deduplicated by the same journal.
    }

    public void consume(String json, @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        applyOne(decode(json, key));
    }

    private MigrationTripCommand decode(String json, String key) {
        MigrationTripCommand command = mapper.readValue(json, MigrationTripCommand.class);
        if (!Long.toString(command.userId()).equals(key)
                || !admission.hashPayload(command.userId(), command.tripRequest())
                .equals(command.payloadHash())) {
            // Invalid records stay uncommitted for investigation; silently skipping one would
            // make a broker-acknowledged HTTP request disappear from the target.
            throw new IllegalStateException("Migration trip record failed integrity check");
        }
        return command;
    }

    private void observeCompletion(MigrationTripCommand command) {
        log.info("migration_trip_completed request_id={} origin={} queued_at_ms={} committed_at_ms={}",
                command.requestId(), applyRole, command.queuedAtEpochMs(), System.currentTimeMillis());
    }

    private void applyOne(MigrationTripCommand command) {
        try {
            boolean created = applyService.apply(command);
            // The transactional service proxy has committed before this observation.
            if (created) observeCompletion(command);
            else log.info("migration_trip_replayed request_id={}", command.requestId());
        } catch (TripNameDuplicatedException duplicate) {
            applyService.recordFailure(command, "TRIP_NAME_DUPLICATED");
        } catch (InvalidTripRequestException invalid) {
            applyService.recordFailure(command, "INVALID_TRIP_REQUEST");
        }
    }
}
