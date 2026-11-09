package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.common.exception.TripNotFoundException;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class TripMutationCoordinator {
    private final TripEditLock editLock;
    private final TripRepository trips;
    private final EntityManager entityManager;
    private final PlatformTransactionManager transactionManager;

    public <T> T execute(Long tripId, Supplier<T> operation) {
        return editLock.execute(tripId, () -> {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            return transaction.execute(status -> {
                Trip trip = trips.findAnyForUpdate(tripId).orElseThrow(TripNotFoundException::new);
                entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
                return operation.get();
            });
        });
    }

    public <T> T executeAll(List<Long> tripIds, Supplier<T> operation) {
        return editLock.executeAll(tripIds, () -> {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            return transaction.execute(status -> {
                for (Long tripId : tripIds.stream().distinct().sorted().toList()) {
                    Trip trip = trips.findAnyForUpdate(tripId).orElseThrow(TripNotFoundException::new);
                    entityManager.refresh(trip, LockModeType.PESSIMISTIC_WRITE);
                }
                return operation.get();
            });
        });
    }

    public void runAll(List<Long> tripIds, Runnable operation) {
        executeAll(tripIds, () -> {
            operation.run();
            return null;
        });
    }

    public void run(Long tripId, Runnable operation) {
        execute(tripId, () -> {
            operation.run();
            return null;
        });
    }
}
