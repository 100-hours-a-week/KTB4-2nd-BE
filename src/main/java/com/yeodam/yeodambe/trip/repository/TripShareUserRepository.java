package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripShareUser;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripShareUserRepository extends JpaRepository<TripShareUser, Long> {
    Optional<TripShareUser> findByTripIdAndGranteeUserId(Long tripId, Long granteeUserId);

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
              and (:lastTripShareId is null or share.id > :lastTripShareId)
            order by share.id asc
            """)
    List<TripShareUser> findActiveSharesWithGrantee(
            @Param("tripId") Long tripId,
            @Param("ownerId") Long ownerId,
            @Param("now") LocalDateTime now,
            @Param("lastTripShareId") Long lastTripShareId,
            Pageable pageable
    );
}
