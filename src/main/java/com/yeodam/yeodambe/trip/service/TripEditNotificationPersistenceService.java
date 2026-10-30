package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox;
import com.yeodam.yeodambe.trip.entity.TripEditNotificationOutbox.Status;
import com.yeodam.yeodambe.trip.repository.TripEditNotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class TripEditNotificationPersistenceService {

    private final TripEditNotificationOutboxRepository records;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<Long> findDueIds(LocalDateTime now) {
        return records.findDueIds(Status.PENDING, now, PageRequest.of(0, 50));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<TripEditNotificationOutbox> findDue(Long id, LocalDateTime now) {
        return records.findByIdAndStatusAndNextAttemptAtLessThanEqual(id, Status.PENDING, now);
    }

    public void markPublished(Long id, LocalDateTime publishedAt) {
        records.markPublished(id, publishedAt, Status.PENDING, Status.PUBLISHED);
    }

    public void markFailed(TripEditNotificationOutbox notification, LocalDateTime failedAt) {
        long delaySeconds = Math.min(30L, 1L << Math.min(notification.getAttempts(), 5));
        records.markFailed(notification.getId(), failedAt.plusSeconds(delaySeconds), Status.PENDING);
    }
}
