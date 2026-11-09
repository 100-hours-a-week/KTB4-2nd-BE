package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "trip_photo_deletion_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripPhotoDeletionReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    @Column(name = "trip_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long tripId;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Column(name = "actor_id", nullable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long actorId;

    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Column(name = "related_addition_ids", nullable = false, columnDefinition = "LONGTEXT")
    private String relatedAdditionIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "requested_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    public TripPhotoDeletionReservation(
            Long tripId,
            Long attachmentId,
            Long actorId,
            String requestId,
            String relatedAdditionIds
    ) {
        this.tripId = tripId;
        this.attachmentId = attachmentId;
        this.actorId = actorId;
        this.requestId = requestId;
        this.relatedAdditionIds = relatedAdditionIds;
    }

    public enum Status {
        PENDING,
        APPLIED
    }
}
