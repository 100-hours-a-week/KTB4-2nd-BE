package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TripAttachmentRepository extends JpaRepository<TripAttachment, Long> {
    List<TripAttachment> findAllByTripId(Long tripId);

    @Query("""
        SELECT new com.yeodam.yeodambe.trip.repository.TripAttachmentCount(
                attachment.tripId,
                COUNT(attachment)
        )
        FROM TripAttachment attachment
        WHERE attachment.tripId IN :tripIds
          AND attachment.deletedAt IS NULL
        GROUP BY attachment.tripId
        """)
    List<TripAttachmentCount> countNotDeletedByTripIds(
            @Param("tripIds") Collection<Long> tripIds
    );

    long countByTripIdAndDeletedAtIsNullAndClassificationStatus(
            Long tripId, ClassificationStatus classificationStatus);
}
