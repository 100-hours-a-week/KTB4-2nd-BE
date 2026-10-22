package com.yeodam.yeodambe.trip.migration;

import com.yeodam.yeodambe.common.exception.InvalidTripRequestException;
import com.yeodam.yeodambe.common.exception.TripNameDuplicatedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class MigrationTripConsumer {
    @org.springframework.beans.factory.annotation.Value("${migration.trip.apply-role:unknown}")
    private String applyRole;
    private final ObjectMapper mapper;
    private final MigrationTripAdmissionService admission;
    private final MigrationTripApplyService applyService;
    private final MigrationTripWorkScheduler scheduler;

    public MigrationTripConsumer(
            ObjectMapper mapper,
            MigrationTripAdmissionService admission,
            MigrationTripApplyService applyService, MigrationTripWorkScheduler scheduler
    ) {
        this.mapper = mapper;
        this.admission = admission;
        this.applyService = applyService;
        this.scheduler = scheduler;
    }

    @KafkaListener(
            id = "migrationTripApply",
            topics = "${migration.trip.topic}",
            groupId = "${MIGRATION_TRIP_CONSUMER_GROUP:yeodam-migration-trip-apply}",
            containerFactory = "migrationTripListenerFactory",
            properties = {"max.poll.records=32", "enable.auto.commit=false"},
            autoStartup = "${migration.trip.consumer-enabled:false}"
    )
    public void consumeBatch(java.util.List<org.apache.kafka.clients.consumer.ConsumerRecord<String,String>> records,
                             org.apache.kafka.clients.consumer.Consumer<String,String> consumer) {
        long batchStartedNs=System.nanoTime();
        if(MigrationTripDiagnostics.enabled()) {
            var counts=new java.util.TreeMap<Integer,Integer>();
            var first=new java.util.TreeMap<Integer,Long>();
            var last=new java.util.TreeMap<Integer,Long>();
            for(var r:records) {counts.merge(r.partition(),1,Integer::sum);first.merge(r.partition(),r.offset(),Math::min);last.merge(r.partition(),r.offset(),Math::max);}
            log.info("migration_trip_batch origin={} at_ms={} thread={} assigned={} counts={} first={} last={} active={} queued={}",
                    applyRole,System.currentTimeMillis(),Thread.currentThread().getName(),consumer.assignment(),counts,first,last,scheduler.active(),scheduler.queued());
        }
        var jobs=scheduler.submit(records,r->consume(r.value(),r.key(),r.partition()));
        // KafkaConsumer is used only on its poll thread. Workers only apply transactions.
        // Wait even when interrupted: a Source stop callback must never precede its last commit.
        for(var job:jobs) job.completed().join();
        var commits=new java.util.HashMap<org.apache.kafka.common.TopicPartition,org.apache.kafka.clients.consumer.OffsetAndMetadata>();
        var unfinished=new java.util.HashMap<org.apache.kafka.common.TopicPartition,Long>();
        for(var job:jobs) {
            var r=job.record();var partition=new org.apache.kafka.common.TopicPartition(r.topic(),r.partition());
            if(unfinished.containsKey(partition)) continue;
            if(job.completed().join()) commits.put(partition,new org.apache.kafka.clients.consumer.OffsetAndMetadata(r.offset()+1));
            else unfinished.put(partition,r.offset());
        }
        if(MigrationTripDiagnostics.enabled()) log.info("migration_trip_batch_done origin={} at_ms={} thread={} wait_us={} unfinished={} active={} queued={}",
                applyRole,System.currentTimeMillis(),Thread.currentThread().getName(),(System.nanoTime()-batchStartedNs)/1000,unfinished.keySet(),scheduler.active(),scheduler.queued());
        if(!commits.isEmpty()) consumer.commitSync(commits);
        unfinished.forEach(consumer::seek);
        if(!unfinished.isEmpty() && !scheduler.fencing()) {
            try {Thread.sleep(1000);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        }
    }

    public void consume(String json, String key, int partition) {
        long receivedAtMs = System.currentTimeMillis();
        MigrationTripCommand command = mapper.readValue(json, MigrationTripCommand.class);
        if (!Long.toString(command.userId()).equals(key)
                || !admission.hashPayload(command.userId(), command.tripRequest())
                .equals(command.payloadHash())) {
            // Invalid records stay uncommitted for investigation; silently skipping one would
            // make a broker-acknowledged HTTP request disappear from the target.
            throw new IllegalStateException("Migration trip record failed integrity check");
        }
        try {
            long applyStartedNs = System.nanoTime();
            var diagnostic=MigrationTripDiagnostics.CURRENT.get();
            if(diagnostic!=null) diagnostic.applyStartNs=applyStartedNs;
            boolean created = applyService.apply(command);
            long applyElapsedUs = (System.nanoTime() - applyStartedNs) / 1_000;
            // The transactional service proxy has committed before this observation.
            // Queue wait includes producer delivery/polling; it is not broker-only latency.
            if (created) log.info("migration_trip_completed request_id={} origin={} queued_at_ms={} committed_at_ms={} consumer_started_at_ms={} apply_elapsed_us={} partition={} diagnostic={}",
                    command.requestId(), applyRole, command.queuedAtEpochMs(), System.currentTimeMillis(),
                    receivedAtMs, applyElapsedUs, partition, diagnostic!=null ? diagnostic.fields() : "disabled");
            else log.info("migration_trip_replayed request_id={}", command.requestId());
        } catch (TripNameDuplicatedException duplicate) {
            applyService.recordFailure(command, "TRIP_NAME_DUPLICATED");
        } catch (InvalidTripRequestException invalid) {
            applyService.recordFailure(command, "INVALID_TRIP_REQUEST");
        }
    }
}
