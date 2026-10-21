package com.yeodam.yeodambe.trip.migration;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationTripBatchOffsetTest {
    private ConsumerRecord<String,String> record(int partition,long offset,String user,String value) {
        return new ConsumerRecord<>("requests",partition,offset,user,value);
    }
    @Test void completedAfterGapCannotAdvanceOffsetAndAnotherPartitionCanCommit() {
        try(var scheduler=new MigrationTripWorkScheduler(2)) {
            var listener=spy(new MigrationTripConsumer(mock(ObjectMapper.class),mock(MigrationTripAdmissionService.class),mock(MigrationTripApplyService.class),scheduler));
            doAnswer(call->{if(call.getArgument(0).equals("bad")) throw new IllegalStateException("retry");return null;}).when(listener).consume(anyString(),anyString(),anyInt());
            Consumer<String,String> kafka=mock(Consumer.class);
            listener.consumeBatch(List.of(record(0,10,"A","ok"),record(0,11,"B","bad"),record(0,12,"C","later"),record(1,20,"D","other")),kafka);
            verify(kafka).commitSync(Map.of(new TopicPartition("requests",0),new OffsetAndMetadata(11),new TopicPartition("requests",1),new OffsetAndMetadata(21)));
            verify(kafka).seek(new TopicPartition("requests",0),11L);
            verifyNoMoreInteractions(kafka);
            // A restart replays even already-applied records beyond the gap; the journal makes those idempotent.
            doNothing().when(listener).consume(anyString(),anyString(),anyInt());
            listener.consumeBatch(List.of(record(0,11,"B","bad"),record(0,12,"C","later")),kafka);
            verify(kafka).commitSync(Map.of(new TopicPartition("requests",0),new OffsetAndMetadata(13)));
        }
    }
    @Test void sourceFenceWaitsForTransactionBeforeCommittingAndLeavesQueuedRequest() throws Exception {
        try(var scheduler=new MigrationTripWorkScheduler(2)) {
            var listener=spy(new MigrationTripConsumer(mock(ObjectMapper.class),mock(MigrationTripAdmissionService.class),mock(MigrationTripApplyService.class),scheduler));
            var started=new CountDownLatch(1);var other=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{
                if(call.getArgument(0).equals("active")) {started.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("release missing");}
                else if(call.getArgument(0).equals("other")) other.countDown();
                else throw new AssertionError("queued request must stay in Kafka");
                return null;
            }).when(listener).consume(anyString(),anyString(),anyInt());
            Consumer<String,String> kafka=mock(Consumer.class);
            try(var caller=Executors.newVirtualThreadPerTaskExecutor()) {
                var future=caller.submit(()->listener.consumeBatch(List.of(record(7,0,"A","active"),record(7,1,"B","other"),record(7,2,"A","queued")),kafka));
                try {
                    assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();assertThat(other.await(2,TimeUnit.SECONDS)).isTrue();
                    scheduler.beginFence();verifyNoInteractions(kafka);assertThat(future.isDone()).isFalse();
                } finally {release.countDown();}
                future.get(2,TimeUnit.SECONDS);
            }
            verify(kafka).commitSync(Map.of(new TopicPartition("requests",7),new OffsetAndMetadata(2)));
            verify(kafka).seek(new TopicPartition("requests",7),2L);
            assertThat(scheduler.active()).isZero();
        }
    }
}
