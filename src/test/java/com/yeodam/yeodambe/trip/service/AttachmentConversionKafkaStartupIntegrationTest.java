package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.SharedTestKafka;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "app.attachment-conversion.mode=KAFKA",
        "app.attachment-conversion.retry.technical-backoff=100ms",
        "app.attachment-conversion.kafka.fetch-max-bytes=1048576",
        "app.attachment-conversion.kafka.partition-fetch-max-bytes=524288",
        "app.attachment-conversion.kafka.max-message-bytes=262144",
        "app.attachment-conversion.admission.max-unfinished-photos.LIGHT=100",
        "app.attachment-conversion.admission.max-unfinished-photos.HEIC=100",
        "app.attachment-conversion.admission.max-unfinished-bytes.LIGHT=100000",
        "app.attachment-conversion.admission.max-unfinished-bytes.HEIC=100000",
        "app.attachment-conversion.admission.max-unfinished-age=1h",
        "app.attachment-conversion.admission.snapshot-max-age=30s",
        "app.attachment-conversion.admission.sample-interval=10s",
        "app.attachment-conversion.admission.reopen-ratio=0.5"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AttachmentConversionKafkaStartupIntegrationTest {
    private static final String PREFIX = "startup-" + UUID.randomUUID();
    private static final List<String> SUFFIXES = List.of("initial-light", "initial-heic", "additional-light", "additional-heic");
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private AttachmentConversionWorker worker;

    @DynamicPropertySource
    static void topics(DynamicPropertyRegistry properties) throws Exception {
        properties.add("app.attachment-conversion.group-prefix", () -> PREFIX);
        for (String suffix : SUFFIXES) {
            properties.add("app.attachment-conversion.topics." + suffix, () -> PREFIX + "-" + suffix);
        }
        properties.add("app.attachment-conversion.topics.dead-letter", () -> PREFIX + "-dlt");
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", SharedTestKafka.bootstrapServers()))) {
            admin.createTopics(java.util.stream.Stream.concat(SUFFIXES.stream(), java.util.stream.Stream.of("dlt"))
                    .map(suffix -> new NewTopic(PREFIX + "-" + suffix, 1, (short) 1)
                            .configs(Map.of("message.timestamp.type", "LogAppendTime"))).toList()).all().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void kafkaModeStartsFourIndependentContainersWithoutCircularReferences() throws Exception {
        assertThat(worker).isNotNull();
        var containers = SUFFIXES.stream().map(suffix -> registry.getListenerContainer("attachment-conversion-" + suffix)).toList();
        assertThat(containers).doesNotContainNull().allSatisfy(container -> assertThat(container.isRunning()).isTrue());
        assertThat(containers).extracting(container -> container.getGroupId())
                .containsExactlyElementsOf(SUFFIXES.stream().map(suffix -> PREFIX + "-" + suffix).toList());
        for (String suffix : SUFFIXES) {
            var metadata = kafka.send(PREFIX + "-" + suffix, "7", "{").get(10, TimeUnit.SECONDS).getRecordMetadata();
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", SharedTestKafka.bootstrapServers()))) {
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    var offset = admin.listConsumerGroupOffsets(PREFIX + "-" + suffix).partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS).get(new TopicPartition(metadata.topic(), metadata.partition()));
                    assertThat(offset).isNotNull();
                    assertThat(offset.offset()).isEqualTo(metadata.offset() + 1);
                });
            }
        }
    }
}
