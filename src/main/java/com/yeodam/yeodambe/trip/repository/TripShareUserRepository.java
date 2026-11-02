package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripShareUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripShareUserRepository extends JpaRepository<TripShareUser, Long> {
    Optional<TripShareUser> findByTripIdAndGranteeUserId(Long tripId, Long granteeUserId);

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
}
