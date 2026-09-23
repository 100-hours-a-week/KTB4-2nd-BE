package com.yeodam.yeodambe.trip.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trip_detail_places")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TripDetailPlace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trip_place_id")
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, insertable = false, updatable = false)
    private Trip trip;

    @Column(name = "place_name", nullable = false, length = 50)
    private String placeName;

    @Column(nullable = false, precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at", nullable = false)
    private LocalDateTime endedAt;

    @Column(name = "order_number", nullable = false)
    private int orderNumber;

    @Column(name = "thumbnail_key", length = 500)
    private String thumbnailKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static TripDetailPlace fromAnalysis(Long tripId, int order, BigDecimal latitude,
                                               BigDecimal longitude, LocalDateTime first,
                                               LocalDateTime last, String thumbnailKey) {
        TripDetailPlace place = new TripDetailPlace();
        place.tripId = tripId;
        // ponytail: V1 분석 응답에는 장소명이 없어 임시 이름을 쓴다. 역지오코딩 계약 확정 시 교체한다.
        place.placeName = "장소 " + order;
        place.latitude = latitude;
        place.longitude = longitude;
        place.startedAt = first == null ? LocalDateTime.now() : first;
        place.endedAt = last == null ? place.startedAt : last;
        place.orderNumber = order;
        place.thumbnailKey = thumbnailKey;
        return place;
    }

    public void changeThumbnailKey(String thumbnailKey) {
        this.thumbnailKey = thumbnailKey;
    }
}
