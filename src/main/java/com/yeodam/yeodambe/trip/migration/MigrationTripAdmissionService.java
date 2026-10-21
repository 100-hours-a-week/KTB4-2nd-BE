package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.trip.service.RegionCatalog;
import com.yeodam.yeodambe.trip.service.TripService;
import com.yeodam.yeodambe.trip.service.request.TripCreateRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class MigrationTripAdmissionService {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final TripService tripService;
    private final RegionCatalog regionCatalog;
    private final String topic;
    private final Duration ackTimeout;

    public MigrationTripAdmissionService(
            KafkaTemplate<String, String> kafka,
            ObjectMapper mapper,
            TripService tripService,
            RegionCatalog regionCatalog,
            @Value("${migration.trip.topic}") String topic,
            @Value("${migration.trip.ack-timeout}") Duration ackTimeout
    ) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.tripService = tripService;
        this.regionCatalog = regionCatalog;
        this.topic = topic;
        this.ackTimeout = ackTimeout;
    }

    public MigrationTripAcceptance accept(Long userId, TripCreateRequest request, String requestId) {
        requireRequestId(requestId);
        tripService.validateTrip(request);
        request.regionCodes().forEach(regionCatalog::getRequired);

        String hash = hashPayload(userId, request);
        MigrationTripCommand command = new MigrationTripCommand(
                requestId, userId, hash, request.tripName(), request.startDate(),
                request.endDate(), request.regionCodes(), System.currentTimeMillis());
        try {
            kafka.send(topic, Long.toString(userId), mapper.writeValueAsString(command))
                    .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("migration_trip_accepted request_id={}", requestId);
            return new MigrationTripAcceptance(requestId);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable(requestId);
        } catch (ExecutionException | TimeoutException | KafkaException failure) {
            // Timeout is ambiguous: the broker may have recorded the request. Retrying the same
            // request ID is safe because the target journal commits with the trip transaction.
            throw unavailable(requestId);
        }
    }

    public String hashPayload(Long userId, TripCreateRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(mapper.writeValueAsBytes(new Payload(userId, request)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void requireRequestId(String requestId) {
        try {
            if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId)) {
                throw invalidId();
            }
        } catch (IllegalArgumentException invalid) {
            throw invalidId();
        }
    }

    private MigrationTripAdmissionException invalidId() {
        return new MigrationTripAdmissionException(
                HttpStatus.BAD_REQUEST, ErrorMessage.MIGRATION_TRIP_REQUEST_ID_REQUIRED);
    }

    private MigrationTripAdmissionException unavailable(String requestId) {
        log.warn("migration_trip_ack_unconfirmed request_id={}", requestId);
        return new MigrationTripAdmissionException(
                HttpStatus.SERVICE_UNAVAILABLE, ErrorMessage.MIGRATION_TRIP_ACK_UNCONFIRMED);
    }

    private record Payload(Long userId, TripCreateRequest request) {
    }
}
