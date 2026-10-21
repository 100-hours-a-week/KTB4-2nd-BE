package com.yeodam.yeodambe.trip.migration;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
class MigrationTripKafkaConfigTest {
    @Test void handoffFinishesCurrentRecordWithoutDrainingFetchedBatch() {
        var factory=new MigrationTripKafkaConfig().migrationTripListenerFactory(mock(ConsumerFactory.class),4);
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getContainerProperties().isStopImmediate()).isTrue();
    }
}
