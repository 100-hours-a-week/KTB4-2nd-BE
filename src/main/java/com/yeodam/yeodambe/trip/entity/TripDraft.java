package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "trip_drafts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripDraft {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "draft_id")
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "trip_name", length = 10)
    private String tripName;

    @Column(name = "region_codes", nullable = false, length = 128)
    private String regionCodesJson;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "submitted_trip_id")
    private Long submittedTripId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public TripDraft(Long userId, String tripName, String regionCodesJson, LocalDate startDate, LocalDate endDate) {
        this.userId = userId;
        this.tripName = tripName;
        this.regionCodesJson = regionCodesJson;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public boolean replace(String tripName, String regionCodesJson, LocalDate startDate, LocalDate endDate) {
        if (Objects.equals(this.tripName, tripName) && Objects.equals(this.regionCodesJson, regionCodesJson)
                && Objects.equals(this.startDate, startDate) && Objects.equals(this.endDate, endDate)) return false;
        this.tripName = tripName;
        this.regionCodesJson = regionCodesJson;
        this.startDate = startDate;
        this.endDate = endDate;
        return true;
    }

    public void attach(Long tripId) {
        this.submittedTripId = tripId;
    }
}
