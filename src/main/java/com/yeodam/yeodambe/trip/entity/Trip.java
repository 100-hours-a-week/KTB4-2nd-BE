package com.yeodam.yeodambe.trip.entity;

import com.yeodam.yeodambe.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "trips")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trip_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, insertable = false, updatable = false)
    private User user;

    @Column(name = "trip_name", nullable = false, length = 10)
    private String tripName;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "is_favorite", nullable = false)
    private Boolean favorite;

    @Column(name = "thumbnail_key", length = 500)
    private String thumbnailKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private ProcessingStatus processingStatus;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Trip(Long userId, String tripName, LocalDate startDate, LocalDate endDate) {
        this.userId = userId;
        this.tripName = tripName;
        this.startDate = startDate;
        this.endDate = endDate;
        this.favorite = false;
        this.processingStatus = ProcessingStatus.PROCESSING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public static Trip localMock(
            Long userId,
            String tripName,
            LocalDate startDate,
            LocalDate endDate,
            String thumbnailKey
    ) {
        Trip trip = new Trip(userId, tripName, startDate, endDate);
        trip.thumbnailKey = thumbnailKey;
        return trip;
    }
}
