package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.trip.service.TripService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationTripApplyService {
    private final MigrationTripResultRepository results;
    private final TripService trips;

    public MigrationTripApplyService(MigrationTripResultRepository results, TripService trips) {
        this.results = results;
        this.trips = trips;
    }

    @Transactional
    public boolean apply(MigrationTripCommand command) {
        var diagnostic=MigrationTripDiagnostics.CURRENT.get();
        if(diagnostic!=null) diagnostic.bodyEntryNs=System.nanoTime();
        var existing = results.find(command.requestId());
        if (existing.isPresent()) {
            ensureSameCommand(existing.get(), command);
            if (!"COMPLETED".equals(existing.get().status())
                    && !"FAILED".equals(existing.get().status())) {
                throw new IllegalStateException("Migration trip request has unfinished status");
            }
            return false;
        }

        Long tripId = trips.createTrip(command.userId(), command.tripRequest()).tripId();
        // APPLYING was never externally visible: both former journal writes shared
        // this transaction. One final insert avoids an extra SQL and CDC event.
        // The request_id PK still rejects concurrent duplicate commits; that
        // transaction (including its trip) rolls back and can be redelivered.
        results.insertCompleted(command, tripId);
        return true;
    }

    @Transactional
    public void recordFailure(MigrationTripCommand command, String errorCode) {
        var existing = results.find(command.requestId());
        if (existing.isPresent()) {
            ensureSameCommand(existing.get(), command);
            return;
        }
        results.fail(command, errorCode);
    }

    private void ensureSameCommand(MigrationTripResultRepository.Entry entry, MigrationTripCommand command) {
        if (!entry.userId().equals(command.userId())
                || !entry.payloadHash().equals(command.payloadHash())) {
            throw new IllegalStateException("Migration request ID has conflicting payload");
        }
    }
}
