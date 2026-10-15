package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "trip_creation_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripCreationOutbox {
    @Id
    @Column(name = "outbox_id", length = 36)
    private String id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private String executionId;

    @Column(name = "message_type", nullable = false, updatable = false)
    private String messageType;

    @Column(name = "batch_no", nullable = false, updatable = false)
    private int batchNo;

    @Column(name = "trip_id", nullable = false, updatable = false)
    private Long tripId;

    @Column(nullable = false, updatable = false)
    private String topic;

    @Column(name = "event_source", nullable = false, updatable = false)
    private String eventSource = "trip_creation_outbox";

    @Column(name = "cdc_managed", nullable = false, updatable = false)
    private boolean cdcManaged = true;

    @Column(nullable = false, columnDefinition = "LONGTEXT", updatable = false)
    private String payload;

    @Column(name = "request_id", nullable = false, updatable = false)
    private String requestId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public TripCreationOutbox(
            String id,
            String executionId,
            String messageType,
            int batchNo,
            Long tripId,
            String topic,
            String payload
    ) {
        this.id = id;
        this.executionId = executionId;
        this.messageType = messageType;
        this.batchNo = batchNo;
        this.tripId = tripId;
        this.topic = topic;
        this.payload = payload;
        this.requestId = executionId;
    }

    public void recordPublished(LocalDateTime now) {
        if (publishedAt == null) {
            status = Status.PUBLISHED;
            publishedAt = now;
        }
    }

    public enum Status {
        PENDING,
        PUBLISHED
    }
}
