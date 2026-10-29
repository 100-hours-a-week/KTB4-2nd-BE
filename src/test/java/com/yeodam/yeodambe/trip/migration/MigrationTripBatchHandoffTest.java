package com.yeodam.yeodambe.trip.migration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.support.TopicPartitionOffset;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Spring listener container with a MockConsumer; no external broker dependency. */
class MigrationTripBatchHandoffTest {
    @Test void stopCallbackWaitsForCurrentBatchAndItsOffsetCommit() throws Exception {
        var partition = new TopicPartition("trips", 0);
        var dbCommitted = new AtomicBoolean();
        var offsetBeforeDb = new AtomicBoolean();
        var lastCommitted = new AtomicLong(-1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var mock = new MockConsumer<String, String>("earliest") {
            @Override public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets, Duration timeout) {
                var offset = offsets.get(partition);
                if (offset != null) {
                    if (offset.offset() > 0 && !dbCommitted.get()) offsetBeforeDb.set(true);
                    lastCommitted.set(offset.offset());
                }
                super.commitSync(offsets, timeout);
            }
        };
        mock.updateBeginningOffsets(Map.of(partition, 0L));
        mock.setMaxPollRecords(10);
        mock.schedulePollTask(() -> {
            for (int i = 0; i < 10; i++) mock.addRecord(new ConsumerRecord<>("trips", 0, i, "7", "command"));
        });
        var cf = new DefaultKafkaConsumerFactory<String, String>(Map.of(
                "group.id", "batch-handoff", "enable.auto.commit", false)) {
            @Override protected Consumer<String, String> createRawConsumer(Map<String, Object> properties) {
                assertThat(properties.get("max.poll.records").toString()).isEqualTo("10");
                return mock;
            }
        };
        var factory = new MigrationTripKafkaConfig().migrationTripListenerFactory(cf, 1);
        var container = factory.createContainer(new TopicPartitionOffset("trips", 0));
        container.getContainerProperties().setPollTimeout(10);
        container.getContainerProperties().setMessageListener((BatchMessageListener<String, String>) records -> {
            assertThat(records).hasSize(10);
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test release missing");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
            }
            dbCommitted.set(true); // listener returns only after the transactional service returns
        });
        try {
            container.start();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            container.stop(stopped::countDown);
            assertThat(stopped.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(lastCommitted.get()).isLessThanOrEqualTo(0);
            release.countDown();
            assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(offsetBeforeDb).isFalse();
            assertThat(lastCommitted.get()).isEqualTo(10);
        } finally {
            release.countDown(); container.stop();
        }
    }
}
