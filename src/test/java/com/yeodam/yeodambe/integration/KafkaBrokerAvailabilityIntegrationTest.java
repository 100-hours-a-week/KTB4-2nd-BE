package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
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

@EnabledIfEnvironmentVariable(named = "YEODAM_KAFKA_INTEGRATION", matches = "true")
@SpringBootTest(properties = {
        "app.trip.result-listener-enabled=true",
        "app.story.result-listener-enabled=true",
        "spring.kafka.consumer.properties.request.timeout.ms=1000"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class KafkaBrokerAvailabilityIntegrationTest {

    private static GenericContainer<?> broker;
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
    static void unavailableBroker(DynamicPropertyRegistry registry) throws IOException {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        bootstrapServers = "localhost:" + port;
        broker = new TestBroker(port);
        broker.start();
        broker.getDockerClient().stopContainerCmd(broker.getContainerId()).withTimeout(0).exec();
        registry.add("spring.kafka.bootstrap-servers", () -> bootstrapServers);
        for (String key : TOPIC_KEYS) {
            registry.add("app.kafka.topics." + key, () -> topic(key));
            registry.add("app.kafka.dead-letter-topics." + key, () -> topic(key) + "-dlt");
        }
    }

    @AfterAll
    static void stopOwnedBroker() {
        if (broker != null) {
            broker.stop();
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
        return "availability.yeodam." + key;
    }

    private static class TestBroker extends GenericContainer<TestBroker> {

        TestBroker(int port) {
            super(DockerImageName.parse("apache/kafka:3.9.1"));
            addFixedExposedPort(port, 9092);
            withEnv("CLUSTER_ID", "MkU3OEVBNTcwNTJENDM2Qk");
            withEnv("KAFKA_NODE_ID", "1");
            withEnv("KAFKA_PROCESS_ROLES", "broker,controller");
            withEnv("KAFKA_LISTENERS", "CLIENT://:9092,CONTROLLER://:9093");
            withEnv("KAFKA_ADVERTISED_LISTENERS", "CLIENT://localhost:" + port);
            withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CLIENT:PLAINTEXT,CONTROLLER:PLAINTEXT");
            withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "CLIENT");
            withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
            withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093");
            withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
            withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
            withStartupTimeout(Duration.ofSeconds(60));
        }
    }
}
