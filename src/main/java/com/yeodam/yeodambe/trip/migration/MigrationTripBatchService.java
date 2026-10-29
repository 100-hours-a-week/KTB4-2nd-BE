package com.yeodam.yeodambe.trip.migration;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One bounded partition batch, including its dedup journal, in one DB transaction. */
@Service
public class MigrationTripBatchService {
    public static final int MAX_RECORDS = 10;
    private final MigrationTripApplyService apply;

    public MigrationTripBatchService(MigrationTripApplyService apply) {
        this.apply = apply;
    }

    @Transactional
    public List<MigrationTripCommand> applyBatch(List<MigrationTripCommand> commands) {
        if (commands.isEmpty() || commands.size() > MAX_RECORDS) {
            throw new IllegalArgumentException("Invalid migration transaction batch size");
        }
        var created = new ArrayList<MigrationTripCommand>();
        for (var command : commands) {
            // The existing REQUIRED service joins this transaction; no per-record COMMIT.
            if (apply.apply(command)) created.add(command);
        }
        return List.copyOf(created);
    }
}
