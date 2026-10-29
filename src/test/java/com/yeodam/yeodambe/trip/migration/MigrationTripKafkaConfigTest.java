package com.yeodam.yeodambe.trip.migration;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
class MigrationTripKafkaConfigTest {
    @Test void handoffFinishesBoundedBatchAndSynchronouslyCommitsOffsets() {
        var factory=new MigrationTripKafkaConfig().migrationTripListenerFactory(mock(ConsumerFactory.class),4);
        assertThat(factory.isBatchListener()).isTrue();
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
        var container=factory.createContainer("trip-batch-test");
        assertThat(container.getContainerProperties().getKafkaConsumerProperties().getProperty("max.poll.records")).isEqualTo("10");
        assertThat(factory.getContainerProperties().isSyncCommits()).isTrue();
        assertThat(factory.getContainerProperties().isStopImmediate()).isTrue();
    }
}
