package com.yeodam.yeodambe.trip.migration;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded shared workers: one in-flight transaction per user, not per partition. */
@Component
@Slf4j
public class MigrationTripWorkScheduler implements AutoCloseable {
    public record Job(ConsumerRecord<String,String> record, CompletableFuture<Boolean> completed) {}
    private record Work(Job job, Consumer<ConsumerRecord<String,String>> apply) {}
    private static final class Lane {
        final String key;
        final ArrayDeque<Work> work = new ArrayDeque<>();
        boolean running;
        Lane(String key) { this.key=key; }
    }
    private final Object lock=new Object();
    private final Map<String,Lane> lanes=new HashMap<>();
    private final ArrayDeque<Lane> ready=new ArrayDeque<>();
    private final ExecutorService workers;
    private final int capacity;
    private int pending;
    private int active;
    private boolean fenced;
    private boolean closed;

    public MigrationTripWorkScheduler(@Value("${migration.trip.concurrency:1}") int concurrency) {
        if(concurrency<1 || concurrency>8) throw new IllegalArgumentException("Invalid migration worker count");
        capacity=concurrency*32; // At most one bounded poll per Kafka consumer.
        workers=Executors.newFixedThreadPool(concurrency,
                Thread.ofPlatform().daemon().name("migration-user-apply-",0).factory());
        for(int i=0;i<concurrency;i++) workers.submit(this::run);
    }

    public List<Job> submit(List<ConsumerRecord<String,String>> records,
                            Consumer<ConsumerRecord<String,String>> apply) {
        List<Job> jobs=new ArrayList<>(records.size());
        Set<String> blocked=new HashSet<>();
        synchronized(lock) {
            for(var record:records) {
                var job=new Job(record,new CompletableFuture<>());jobs.add(job);
                if(fenced || closed || pending>=capacity || blocked.contains(record.key())) {
                    blocked.add(record.key());job.completed().complete(false);continue;
                }
                var lane=lanes.computeIfAbsent(record.key(),Lane::new);
                if(!lane.running && lane.work.isEmpty()) ready.addLast(lane);
                lane.work.addLast(new Work(job,apply));pending++;
            }
            lock.notifyAll();
        }
        return jobs;
    }

    private void run() {
        while(true) {
            Lane lane; Work work;
            synchronized(lock) {
                try {while(ready.isEmpty() && !closed) lock.wait();}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return;}
                if(closed) return;
                lane=ready.removeFirst();lane.running=true;work=lane.work.removeFirst();active++;
            }
            boolean completed=false;
            try {work.apply().accept(work.job().record());completed=true;}
            catch(Exception failure) {
                log.warn("migration_trip_apply_retry partition={} offset={} error={}",
                        work.job().record().partition(),work.job().record().offset(),failure.getClass().getSimpleName());
            } finally {
                synchronized(lock) {
                    active--;pending--;lane.running=false;
                    work.job().completed().complete(completed);
                    // A failed earlier request must not be overtaken by the same user's later requests.
                    if(!completed) cancel(lane);
                    if(!lane.work.isEmpty()) ready.addLast(lane);
                    else lanes.remove(lane.key,lane);
                    lock.notifyAll();
                }
            }
        }
    }

    private void cancel(Lane lane) {
        while(!lane.work.isEmpty()) {pending--;lane.work.removeFirst().job().completed().complete(false);}
    }
    @EventListener(ContextClosedEvent.class)
    public void beginFence() {
        synchronized(lock) {
            fenced=true;ready.clear();
            var iterator=lanes.values().iterator();
            while(iterator.hasNext()) {var lane=iterator.next();cancel(lane);if(!lane.running) iterator.remove();}
            lock.notifyAll();
        }
    }
    public int active() {synchronized(lock) {return active;}}
    public int queued() {synchronized(lock) {return pending-active;}}
    public boolean fencing() {synchronized(lock) {return fenced;}}
    @Override @PreDestroy
    public void close() {
        beginFence();
        synchronized(lock) {closed=true;lock.notifyAll();}
        workers.shutdown();
        try {if(!workers.awaitTermination(30,TimeUnit.SECONDS)) throw new IllegalStateException("Migration work still active during shutdown");}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
    }
}
