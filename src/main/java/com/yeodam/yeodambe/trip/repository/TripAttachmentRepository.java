package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripAttachment;
import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripAttachmentRepository extends JpaRepository<TripAttachment, Long> {
    List<TripAttachment> findAllByTripId(Long tripId);

    long countByTripIdAndDeletedAtIsNullAndClassificationStatus(
            Long tripId, ClassificationStatus classificationStatus);
}
