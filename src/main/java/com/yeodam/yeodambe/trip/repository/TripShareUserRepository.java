package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripSharePermission;
import com.yeodam.yeodambe.trip.entity.TripShareUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripShareUserRepository extends JpaRepository<TripShareUser, Long> {
    @Query("""
            select share.granteeUserId as granteeUserId, share.permission as permission, share.expiresAt as expiresAt
            from TripShareUser share
            where share.tripId = :tripId
              and share.userId = :ownerId
              and share.deletedAt is null
              and share.expiresAt > :checkedAt
            """)
    List<ReadGrantRow> findReadableGrantsByTripIdAndOwnerId(
            @Param("tripId") Long tripId,
            @Param("ownerId") Long ownerId,
            @Param("checkedAt") LocalDateTime checkedAt
    );

    interface ReadGrantRow {
        Long getGranteeUserId();

        TripSharePermission getPermission();

        LocalDateTime getExpiresAt();
    }

    Optional<TripShareUser> findByTripIdAndGranteeUserId(Long tripId, Long granteeUserId);

    @Query("""
            select share.userId as ownerId, share.permission as permission,
                   share.expiresAt as expiresAt, share.deletedAt as deletedAt
            from TripShareUser share
            where share.tripId = :tripId and share.granteeUserId = :actorId
            """)
    Optional<GrantSnapshot> findGrantSnapshot(@Param("tripId") Long tripId, @Param("actorId") Long actorId);

    interface GrantSnapshot {
        Long getOwnerId();

        TripSharePermission getPermission();

        LocalDateTime getExpiresAt();

        LocalDateTime getDeletedAt();
    }

    @Query("""
            select share
            from TripShareUser share
            join fetch share.trip trip
            where share.granteeUserId = :granteeUserId
              and share.deletedAt is null
              and share.expiresAt > :checkedAt
              and trip.deletedAt is null
              and share.userId = trip.userId
              and trip.userId <> :granteeUserId
              and (:startDate is null
                   or trip.startDate < :startDate
                   or (trip.startDate = :startDate and trip.id < :tripId))
            order by trip.startDate desc, trip.id desc
            """)
    List<TripShareUser> findInvitedPage(
            @Param("granteeUserId") Long granteeUserId,
            @Param("checkedAt") LocalDateTime checkedAt,
            @Param("startDate") LocalDate startDate,
            @Param("tripId") Long tripId,
            Pageable pageable
    );

    @Query("""
            select count(share)
            from TripShareUser share
            join share.trip trip
            where share.granteeUserId = :granteeUserId
              and share.deletedAt is null
              and share.expiresAt > :checkedAt
              and trip.deletedAt is null
              and share.userId = trip.userId
              and trip.userId <> :granteeUserId
            """)
    long countInvitedTrips(
            @Param("granteeUserId") Long granteeUserId,
            @Param("checkedAt") LocalDateTime checkedAt
    );

    @Query("""
            select share
            from TripShareUser share
            join fetch share.grantee grantee
            where share.tripId = :tripId
              and share.userId = :ownerId
              and share.granteeUserId <> :ownerId
              and share.deletedAt is null
              and share.expiresAt > :now
              and grantee.deletedAt is null
              and share.id > :lastTripShareId
            order by share.id asc
            """)
    List<TripShareUser> findActiveSharesWithGrantee(
            @Param("tripId") Long tripId,
            @Param("ownerId") Long ownerId,
            @Param("now") LocalDateTime now,
            @Param("lastTripShareId") Long lastTripShareId,
            Pageable pageable
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update TripShareUser share
            set share.deletedAt = :withdrawnAt,
                share.updatedAt = :withdrawnAt
            where share.deletedAt is null
              and (share.userId = :userId or share.granteeUserId = :userId)
            """)
    int softDeleteByUserId(
            @Param("userId") Long userId,
            @Param("withdrawnAt") LocalDateTime withdrawnAt
    );
}
