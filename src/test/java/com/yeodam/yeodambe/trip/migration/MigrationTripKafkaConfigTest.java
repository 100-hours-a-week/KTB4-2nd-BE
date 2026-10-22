package com.yeodam.yeodambe.trip.migration;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MigrationTripKafkaConfigTest {
    private Properties listenerProperties() throws Exception {
        var method = MigrationTripConsumer.class.getMethod("consumeBatch", java.util.List.class,
                org.apache.kafka.clients.consumer.Consumer.class);
        var result = new Properties();
        for (String property : method.getAnnotation(KafkaListener.class).properties()) {
            String[] pair = property.split("=", 2);
            result.setProperty(pair[0], pair[1]);
        }
        return result;
    }

    private MethodKafkaListenerEndpoint<String, String> endpoint(Properties properties) throws Exception {
        var endpoint = new MethodKafkaListenerEndpoint<String, String>();
        endpoint.setId("migration-config-test");
        endpoint.setTopics("rehearsal-test");
        endpoint.setBean(mock(MigrationTripConsumer.class));
        endpoint.setMethod(MigrationTripConsumer.class.getMethod("consumeBatch", java.util.List.class,
                org.apache.kafka.clients.consumer.Consumer.class));
        var handlers = new org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory();
        handlers.afterPropertiesSet();
        endpoint.setMessageHandlerMethodFactory(handlers);
        endpoint.setBatchListener(true);
        endpoint.setConsumerProperties(properties);
        return endpoint;
    }

    @Test void generatedContainersReceiveListenerOverridesAndManualCommit() throws Exception {
        var shared = mock(ConsumerFactory.class);
        for (int concurrency : new int[]{4, 8}) {
            var factory = new MigrationTripKafkaConfig().migrationTripListenerFactory(shared, concurrency);
            var container = factory.createListenerContainer(endpoint(listenerProperties()));
            assertThat(((org.springframework.kafka.listener.ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency()).isEqualTo(concurrency);
            assertThat(container.getContainerProperties().getKafkaConsumerProperties())
                    .containsEntry("max.poll.records", "32").containsEntry("enable.auto.commit", "false");
            assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            assertThat(container.getContainerProperties().isStopImmediate()).isTrue();
            org.mockito.Mockito.verifyNoInteractions(shared);
        }
    }

    @Test void missingOrWrongOverridesFailBeforeNetworkStart() throws Exception {
        var factory = new MigrationTripKafkaConfig().migrationTripListenerFactory(mock(ConsumerFactory.class), 4);
        assertThatThrownBy(() -> factory.createListenerContainer(endpoint(new Properties())))
                .isInstanceOf(IllegalStateException.class);
        for (String property : new String[]{"max.poll.records", "enable.auto.commit"}) {
            var wrong = listenerProperties();
            wrong.setProperty(property, property.equals("max.poll.records") ? "500" : "true");
            assertThatThrownBy(() -> factory.createListenerContainer(endpoint(wrong)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void rejectsUnboundedConcurrency() {
        assertThatThrownBy(() -> new MigrationTripKafkaConfig().migrationTripListenerFactory(mock(ConsumerFactory.class), 9))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
