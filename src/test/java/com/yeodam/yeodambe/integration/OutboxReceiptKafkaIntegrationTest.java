package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.SharedTestKafka;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.story.service.StoryGenerationPublicationStateService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "app.outbox.receipt-listener-enabled=true")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OutboxReceiptKafkaIntegrationTest {
    private static final String PREFIX = "receipt-" + UUID.randomUUID();
    private static final String TOPIC = PREFIX + ".requests";
    @Autowired private KafkaTemplate<String, String> kafka;
    @MockitoBean private StoryGenerationPublicationStateService receipts;
    @Autowired private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) throws Exception {
        SharedTestKafka.registerBootstrapServers(properties);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", SharedTestKafka.bootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)
                    .configs(Map.of("message.timestamp.type", "LogAppendTime")))).all().get(30, TimeUnit.SECONDS);
        }
        properties.add("app.kafka.topics.story-create-request", () -> TOPIC);
        properties.add("app.outbox.receipt-consumer-group", () -> PREFIX);
    }

    @Test
    void databaseFailureStopsUntilExplicitRestartWithoutCommittingFailedOffset() throws Exception {
        var attempts = new AtomicInteger();
        var confirmed = new CountDownLatch(1);
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("temporary test DB outage");
            }
            confirmed.countDown();
            return null;
        }).when(receipts).recordPublished(eq(7L), any());
        var preceding = new ProducerRecord<String, String>(TOPIC, "77", "{}");
        preceding.headers().add("outbox-source", "story_generation_outbox".getBytes(StandardCharsets.UTF_8));
        preceding.headers().add("id", "6".getBytes(StandardCharsets.UTF_8));
        kafka.send(preceding).get(10, TimeUnit.SECONDS);
        var record = new ProducerRecord<String, String>(TOPIC, "77", "{}");
        record.headers().add("outbox-source", "story_generation_outbox".getBytes(StandardCharsets.UTF_8));
        record.headers().add("id", "7".getBytes(StandardCharsets.UTF_8));
        var sent = kafka.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
        var listener = listeners.getListenerContainers().stream()
                .filter(value -> PREFIX.equals(value.getGroupId())).findFirst().orElseThrow();
        for (int attempt = 0; attempt < 100 && (attempts.get() == 0 || listener.isRunning()); attempt++) {
            Thread.sleep(100);
        }
        assertThat(listener.isRunning()).isFalse();
        assertThat(attempts.get()).isEqualTo(1);
        var failedPartition = new TopicPartition(TOPIC, sent.partition());
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", SharedTestKafka.bootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets(PREFIX).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            assertThat(offsets.get(failedPartition)).isNotNull();
            assertThat(offsets.get(failedPartition).offset()).isEqualTo(sent.offset());
        }
        listener.start();
        assertThat(confirmed.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(attempts.get()).isEqualTo(2);
        var partition = new TopicPartition(TOPIC, sent.partition());
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", SharedTestKafka.bootstrapServers()))) {
            Long committed = null;
            for (int attempt = 0; attempt < 50 && committed == null; attempt++) {
                var offsets = admin.listConsumerGroupOffsets(PREFIX).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                if (offsets.containsKey(partition) && offsets.get(partition).offset() == sent.offset() + 1) {
                    committed = offsets.get(partition).offset();
                } else {
                    Thread.sleep(100);
                }
            }
            assertThat(committed).isEqualTo(sent.offset() + 1);
        }
    }
}
