package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.SharedTestKafka;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.trip.result-listener-enabled=true",
        "app.story.result-listener-enabled=true",
        "spring.kafka.consumer.properties.request.timeout.ms=1000"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class KafkaBrokerAvailabilityIntegrationTest {

    private static KafkaContainer broker;
    private static final String PREFIX = "availability-" + UUID.randomUUID();
    private static String bootstrapServers;
    private static final List<String> TOPIC_KEYS = List.of(
            "trip-create-request", "trip-create-result", "trip-update-request", "trip-update-result",
            "story-create-request", "story-create-result"
    );

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void unavailableBroker(DynamicPropertyRegistry registry) {
        broker = SharedTestKafka.broker();
        bootstrapServers = broker.getBootstrapServers();
        broker.getDockerClient().stopContainerCmd(broker.getContainerId()).withTimeout(0).exec();
        registry.add("app.trip.result-consumer-group", () -> PREFIX + ".create-consumer");
        registry.add("KAFKA_TRIP_UPDATE_RESULT_CONSUMER_GROUP", () -> PREFIX + ".update-consumer");
        registry.add("app.story.result-consumer-group", () -> PREFIX + ".story-consumer");
        registry.add("spring.kafka.bootstrap-servers", () -> bootstrapServers);
        for (String key : TOPIC_KEYS) {
            registry.add("app.kafka.topics." + key, () -> topic(key));
            registry.add("app.kafka.dead-letter-topics." + key, () -> topic(key) + "-dlt");
        }
    }

    @AfterAll
    static void restoreSharedBroker() {
        if (broker != null && !broker.isRunning()) {
            broker.getDockerClient().startContainerCmd(broker.getContainerId()).exec();
        }
    }

    @Test
    void 브로커_없이_시작하고_중단중에도_health를_유지하며_재연결한다() throws Exception {
        assertThat(listeners.getListenerContainers()).hasSize(3).allMatch(container -> container.isRunning());
        assertHealthy();
        broker.getDockerClient().startContainerCmd(broker.getContainerId()).exec();
        createTopics();
        awaitListenerAssignments();
        assertAllResultListenersRecover();

        broker.getDockerClient().stopContainerCmd(broker.getContainerId()).withTimeout(0).exec();
        assertHealthy();
        broker.getDockerClient().startContainerCmd(broker.getContainerId()).exec();
        assertAllResultListenersRecover();
    }

    private void awaitListenerAssignments() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean assigned = false;
        while (!assigned && System.nanoTime() < deadline) {
            assigned = listeners.getListenerContainers().stream().allMatch(container -> {
                var partitions = container.getAssignedPartitions();
                return partitions != null && partitions.size() == 2;
            });
            if (!assigned) {
                Thread.sleep(100);
            }
        }
        assertThat(assigned).as("three result consumers assigned before publishing").isTrue();
    }

    private void assertHealthy() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private void createTopics() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers))) {
            var topics = TOPIC_KEYS.stream()
                    .flatMap(key -> List.of(topic(key), topic(key) + "-dlt").stream())
                    .map(name -> new NewTopic(name, 2, (short) 1))
                    .toList();
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
        }
    }

    private void assertAllResultListenersRecover() throws Exception {
        String marker = "{broken-" + UUID.randomUUID();
        List<String> resultTopics = List.of(topic("trip-create-result"), topic("trip-update-result"),
                topic("story-create-result"));
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "availability-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ))) {
            consumer.subscribe(resultTopics.stream().map(name -> name + "-dlt").toList());
            for (String resultTopic : resultTopics) {
                kafka.send(resultTopic, "42", marker).get(30, TimeUnit.SECONDS);
            }
            Set<String> received = new HashSet<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (received.size() < resultTopics.size() && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (marker.equals(record.value())) {
                        assertThat(record.key()).isEqualTo("42");
                        received.add(record.topic());
                    }
                }
            }
            assertThat(received).containsExactlyInAnyOrderElementsOf(
                    resultTopics.stream().map(name -> name + "-dlt").toList());
        }
    }

    private static String topic(String key) {
        return PREFIX + "." + key;
    }

}
