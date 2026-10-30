package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trip_edit_command_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_trip_edit_command_user_request", columnNames = {"user_id", "request_id"}
), indexes = @Index(name = "idx_trip_edit_command_expiry", columnList = "result_expires_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripEditCommandRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "command_record_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long userId;

    @Column(name = "trip_id", nullable = false, updatable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long tripId;

    @Column(name = "request_id", nullable = false, updatable = false, length = 128)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, updatable = false, length = 30)
    private CommandType commandType;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(name = "completed_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime completedAt;

    @Column(name = "result_expires_at", nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime resultExpiresAt;

    public TripEditCommandRecord(
            Long userId,
            Long tripId,
            String requestId,
            CommandType commandType,
            String payloadHash,
            String resultJson,
            LocalDateTime completedAt
    ) {
        this.userId = userId;
        this.tripId = tripId;
        this.requestId = requestId;
        this.commandType = commandType;
        this.payloadHash = payloadHash;
        this.resultJson = resultJson;
        this.completedAt = completedAt;
        this.resultExpiresAt = completedAt.plusHours(24);
    }

    public enum CommandType {
        UPDATE_TRIP,
        DELETE_ATTACHMENTS
    }
}
