package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
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

    public MigrationTripConsumer(
            ObjectMapper mapper,
            MigrationTripAdmissionService admission,
            MigrationTripApplyService applyService
    ) {
        this.mapper = mapper;
        this.admission = admission;
        this.applyService = applyService;
    }

    @KafkaListener(
            id = "migrationTripApply",
            topics = "${migration.trip.topic}",
            groupId = "${MIGRATION_TRIP_CONSUMER_GROUP:yeodam-migration-trip-apply}",
            containerFactory = "migrationTripListenerFactory",
            autoStartup = "${migration.trip.consumer-enabled:false}"
    )
    public void consume(String json, @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        MigrationTripCommand command = mapper.readValue(json, MigrationTripCommand.class);
        if (!Long.toString(command.userId()).equals(key)
                || !admission.hashPayload(command.userId(), command.tripRequest())
                .equals(command.payloadHash())) {
            // Invalid records stay uncommitted for investigation; silently skipping one would
            // make a broker-acknowledged HTTP request disappear from the target.
            throw new IllegalStateException("Migration trip record failed integrity check");
        }
        try {
            boolean created = applyService.apply(command);
            // The transactional service proxy has committed before this observation.
            if (created) log.info("migration_trip_completed request_id={} origin={} queued_at_ms={} committed_at_ms={}",
                    command.requestId(), applyRole, command.queuedAtEpochMs(), System.currentTimeMillis());
            else log.info("migration_trip_replayed request_id={}", command.requestId());
        } catch (TripNameDuplicatedException duplicate) {
            applyService.recordFailure(command, "TRIP_NAME_DUPLICATED");
        } catch (InvalidTripRequestException invalid) {
            applyService.recordFailure(command, "INVALID_TRIP_REQUEST");
        }
    }
}
