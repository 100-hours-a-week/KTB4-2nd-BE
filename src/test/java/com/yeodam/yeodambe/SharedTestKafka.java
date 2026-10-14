package com.yeodam.yeodambe;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class SharedTestKafka {

    private SharedTestKafka() {
    }

    public static KafkaContainer broker() {
        return Holder.BROKER;
    }

    public static String bootstrapServers() {
        return Holder.BOOTSTRAP_SERVERS;
    }

    public static void registerBootstrapServers(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", SharedTestKafka::bootstrapServers);
    }

    public static void createTopics(String... names) throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrapServers()))) {
            var topics = Arrays.stream(names)
                    .map(name -> new NewTopic(name, 2, (short) 1))
                    .toList();
            admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
        }
    }

    private static int availablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new UncheckedIOException("테스트 Kafka의 호스트 포트를 할당하지 못했습니다.", failure);
        }
    }

    private static final class Holder {

        private static final KafkaContainer BROKER = startKafka();
        private static final String BOOTSTRAP_SERVERS = BROKER.getBootstrapServers();

        private static KafkaContainer startKafka() {
            int port = availablePort();
            var kafka = new KafkaContainer("apache/kafka:3.9.1")
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms64m -Xmx256m")
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                    .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(9092))
                    ));
            // Docker 재시작 후에도 광고 주소와 호스트 포트가 일치하도록 바인딩을 고정한다.
            // 컨텍스트가 아닌 테스트 JVM의 수명을 따르며 종료 후 Ryuk이 정리한다.
            kafka.start();
            return kafka;
        }
    }
}
