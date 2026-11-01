package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "attachment_conversion_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttachmentConversionOutbox {

    @Id
    @Column(name = "outbox_id", length = 36)
    private String id;

    @Column(name = "task_id", nullable = false, updatable = false, length = 36)
    private String taskId;

    @Column(name = "delivery_no", nullable = false, updatable = false)
    private long deliveryNo;

    @Column(name = "trip_id", nullable = false, updatable = false, columnDefinition = "BIGINT UNSIGNED")
    private Long tripId;

    @Column(nullable = false, updatable = false)
    private String topic;

    @Column(nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "request_id", nullable = false, updatable = false, length = 128)
    private String requestId;

    @Column(name = "event_source", nullable = false, updatable = false)
    private String eventSource = "attachment_conversion_outbox";

    @Column(name = "cdc_managed", nullable = false, updatable = false)
    private boolean cdcManaged = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "topic_id")
    private String topicId;

    @Column(name = "record_partition")
    private Integer recordPartition;

    @Column(name = "record_offset")
    private Long recordOffset;

    @Column(name = "broker_timestamp")
    private Instant brokerTimestamp;

    @Column(name = "leader_epoch")
    private Integer leaderEpoch;

    @Column(name = "publication_version", nullable = false)
    private long publicationVersion;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public AttachmentConversionOutbox(
            String id,
            String taskId,
            long deliveryNo,
            Long tripId,
            String topic,
            String payload,
            String requestId
    ) {
        this.id = id;
        this.taskId = taskId;
        this.deliveryNo = deliveryNo;
        this.tripId = tripId;
        this.topic = topic;
        this.payload = payload;
        this.requestId = requestId;
    }

    public enum Status {
        PENDING,
        PUBLISHED
    }
}
