package com.yeodam.yeodambe.trip.migration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class MigrationTripWorkSchedulerTest {
    private ConsumerRecord<String,String> record(long offset,String key,String value) {
        return new ConsumerRecord<>("requests",7,offset,key,value);
    }
    @Test void oneBusyPartitionSharesWorkersButSameUserNeverOvertakes() throws Exception {
        try(var scheduler=new MigrationTripWorkScheduler(2)) {
            var started=new CountDownLatch(1);var release=new CountDownLatch(1);var other=new CountDownLatch(1);
            var order=Collections.synchronizedList(new ArrayList<String>());
            var jobs=scheduler.submit(List.of(record(0,"A","a1"),record(1,"A","a2"),record(2,"B","b1")),r->{
                if(r.value().equals("a1")) {started.countDown();await(release);}
                order.add(r.value());if(r.value().equals("b1")) other.countDown();
            });
            try {
                assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(other.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(jobs.get(1).completed()).isNotDone();
                assertThat(order).containsExactly("b1");
            } finally {release.countDown();}
            for(var job:jobs) assertThat(job.completed().get(2,TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly("b1","a1","a2");
        }
    }
    @Test void fenceLeavesOnlyCurrentTransactionAndCancelsQueuedOrNewRequests() throws Exception {
        try(var scheduler=new MigrationTripWorkScheduler(1)) {
            var started=new CountDownLatch(1);var release=new CountDownLatch(1);
            var jobs=scheduler.submit(List.of(record(0,"A","first"),record(1,"A","queued")),r->{started.countDown();await(release);});
            try {
                assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();scheduler.beginFence();
                assertThat(scheduler.active()).isEqualTo(1);assertThat(scheduler.queued()).isZero();
                assertThat(jobs.get(1).completed().get(2,TimeUnit.SECONDS)).isFalse();
                assertThat(scheduler.submit(List.of(record(2,"B","late")),r->{throw new AssertionError("started after fence");}).getFirst().completed().join()).isFalse();
            } finally {release.countDown();}
            assertThat(jobs.getFirst().completed().get(2,TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.active()).isZero();
        }
    }
    @Test void failedEarlierRequestBlocksLaterRequestsForSameUserOnly() throws Exception {
        try(var scheduler=new MigrationTripWorkScheduler(2)) {
            var seen=Collections.synchronizedList(new ArrayList<String>());
            var jobs=scheduler.submit(List.of(record(0,"A","bad"),record(1,"A","later"),record(2,"B","other")),r->{
                if(r.value().equals("bad")) throw new IllegalStateException("retry");seen.add(r.value());
            });
            assertThat(jobs.get(0).completed().get(2,TimeUnit.SECONDS)).isFalse();
            assertThat(jobs.get(1).completed().get(2,TimeUnit.SECONDS)).isFalse();
            assertThat(jobs.get(2).completed().get(2,TimeUnit.SECONDS)).isTrue();
            assertThat(seen).containsExactly("other");
        }
    }
    private static void await(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test release missing");}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
    }
}
