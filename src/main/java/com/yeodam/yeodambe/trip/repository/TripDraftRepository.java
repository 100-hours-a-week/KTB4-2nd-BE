package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripDraft;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TripDraftRepository extends JpaRepository<TripDraft, Long> {
    Optional<TripDraft> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select draft from TripDraft draft where draft.id = :id and draft.userId = :userId")
    Optional<TripDraft> findByIdAndUserIdForUpdate(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select draft from TripDraft draft where draft.userId = :userId")
    Optional<TripDraft> findByUserIdForUpdate(Long userId);

    @Modifying
    @Query("delete from TripDraft draft where draft.submittedTripId = :tripId and draft.userId = :userId")
    int deleteBySubmittedTripIdAndUserId(Long tripId, Long userId);

    @Modifying
    @Query("update TripDraft draft set draft.submittedTripId = null where draft.submittedTripId = :tripId and draft.userId = :userId")
    int clearSubmittedTripId(Long tripId, Long userId);

    @Modifying
    @Query("delete from TripDraft draft where draft.userId = :userId")
    int deleteByUserId(Long userId);
}
