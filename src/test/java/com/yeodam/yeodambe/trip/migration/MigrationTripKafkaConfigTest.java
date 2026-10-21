package com.yeodam.yeodambe.trip.migration;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
class MigrationTripKafkaConfigTest {
    @Test void targetSupportsEightConsumersAndRejectsUnboundedConcurrency() {
        var config=new MigrationTripKafkaConfig();
        var factory=config.migrationTripListenerFactory(mock(ConsumerFactory.class),8);
        assertThat(((ConcurrentMessageListenerContainer<?,?>)factory.createContainer("rehearsal-test")).getConcurrency()).isEqualTo(8);
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getContainerProperties().isStopImmediate()).isTrue();
        assertThatThrownBy(()->config.migrationTripListenerFactory(mock(ConsumerFactory.class),9))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void handoffFinishesCurrentRecordWithoutDrainingFetchedBatch() {
        var factory=new MigrationTripKafkaConfig().migrationTripListenerFactory(mock(ConsumerFactory.class),4);
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        assertThat(factory.getContainerProperties().isStopImmediate()).isTrue();
    }
}
