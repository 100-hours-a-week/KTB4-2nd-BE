package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.ClassificationStatus;
import com.yeodam.yeodambe.trip.entity.TripAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

public interface TripAttachmentRepository extends JpaRepository<TripAttachment, Long> {
    @Query("""
            select new com.yeodam.yeodambe.trip.repository.PlaceFolderAttachmentCount(
                    attachment.tripPlaceId,
                    count(attachment)
            )
            from TripAttachment attachment
            join attachment.file file
            where attachment.tripPlaceId in :tripPlaceIds
              and attachment.classificationStatus = com.yeodam.yeodambe.trip.entity.ClassificationStatus.ACTIVE
              and attachment.deletedAt is null
              and file.deletedAt is null
            group by attachment.tripPlaceId
            """)
    List<PlaceFolderAttachmentCount> countActiveByTripPlaceIds(
            @Param("tripPlaceIds") Collection<Long> tripPlaceIds
    );

    List<TripAttachment> findAllByTripId(Long tripId);

    List<TripAttachment> findAllByTripIdAndDeletedAtIsNull(Long tripId);

    @Modifying
    @Query("""
            update TripAttachment attachment set attachment.deletedAt = :deletedAt
            where attachment.tripId = :tripId and attachment.deletedAt is null
            """)
    int softDeleteByTripId(Long tripId, LocalDateTime deletedAt);

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

    @Modifying
    @Query("""
            update TripAttachment attachment
            set attachment.deletedAt = :deletedAt
            where attachment.tripId in (
                select trip.id
                from Trip trip
                where trip.userId = :userId
            )
              and attachment.deletedAt is null
            """)
    int softDeleteByUserId(Long userId, LocalDateTime deletedAt);

    @Query("""
        select attachment
        from TripAttachment attachment
        where attachment.tripId = :tripId
          and attachment.tripPlaceId = :tripPlaceId
          and attachment.classificationStatus = :classificationStatus
          and attachment.deletedAt is null
          and (
                :cursorCreatedAt is null
                or attachment.createdAt < :cursorCreatedAt
                or (
                    attachment.createdAt = :cursorCreatedAt
                    and attachment.id < :cursorId
                )
          )
        order by attachment.createdAt desc, attachment.id desc
        """)
    List<TripAttachment> findByPlaceFolderWithCursor(
            @Param("tripId") Long tripId,
            @Param("tripPlaceId") Long tripPlaceId,
            @Param("classificationStatus") ClassificationStatus classificationStatus,
            @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @Query("""
        select attachment
        from TripAttachment attachment
        join fetch attachment.file file
        join attachment.trip trip
        where attachment.id = :tripAttachmentId
          and trip.userId = :userId
          and attachment.deletedAt is null
          and trip.deletedAt is null
          and file.deletedAt is null
        """)
    Optional<TripAttachment> findAccessibleById(
            @Param("tripAttachmentId") Long tripAttachmentId,
            @Param("userId") Long userId
    );

    @Query("""
        select attachment
        from TripAttachment attachment
        join fetch attachment.file file
        join fetch attachment.trip trip
        where attachment.id in :tripAttachmentIds
          and attachment.deletedAt is null
          and trip.deletedAt is null
          and file.deletedAt is null
        """)
    List<TripAttachment> findAllActiveWithTripAndFileByIds(
            @Param("tripAttachmentIds") Collection<Long> tripAttachmentIds
    );

    @Query("""
        select attachment
        from TripAttachment attachment
        join attachment.file file
        where attachment.tripPlaceId = :tripPlaceId
          and attachment.classificationStatus = :classificationStatus
          and attachment.deletedAt is null
          and file.deletedAt is null
        """)
    List<TripAttachment> findAllActiveByTripPlaceId(
            @Param("tripPlaceId") Long tripPlaceId,
            @Param("classificationStatus") ClassificationStatus classificationStatus
    );
}
