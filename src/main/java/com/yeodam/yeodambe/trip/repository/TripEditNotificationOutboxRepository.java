package com.yeodam.yeodambe.trip.repository;

import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox;
import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TripEditNotificationOutboxRepository extends JpaRepository<TripEditNotificationOutbox, Long> {

    @Query("""
            select notification.id from TripEditNotificationOutbox notification
            where notification.status = :status and notification.nextAttemptAt <= :now
            order by notification.nextAttemptAt, notification.id
            """)
    List<Long> findDueIds(@Param("status") Status status, @Param("now") LocalDateTime now, Pageable pageable);

    Optional<TripEditNotificationOutbox> findByIdAndStatusAndNextAttemptAtLessThanEqual(
            Long id,
            Status status,
            LocalDateTime now
    );

    @Modifying
    @Query("""
            update TripEditNotificationOutbox notification
            set notification.status = :published, notification.attempts = notification.attempts + 1,
                notification.publishedAt = :publishedAt
            where notification.id = :id and notification.status = :pending
            """)
    int markPublished(
            @Param("id") Long id,
            @Param("publishedAt") LocalDateTime publishedAt,
            @Param("pending") Status pending,
            @Param("published") Status published
    );

    @Modifying
    @Query("""
            update TripEditNotificationOutbox notification
            set notification.attempts = notification.attempts + 1, notification.nextAttemptAt = :nextAttemptAt
            where notification.id = :id and notification.status = :pending
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("pending") Status pending
    );
}
