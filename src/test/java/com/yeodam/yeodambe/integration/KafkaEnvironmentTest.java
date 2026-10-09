package com.yeodam.yeodambe.integration;

import com.yeodam.yeodambe.integration.config.MskKafkaConfiguration;
import com.yeodam.yeodambe.integration.config.KafkaEventConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEnvironmentTest {

    @TempDir
    Path environmentDirectory;

    @BeforeEach
    void 빈_환경변수_파일을_준비한다() throws IOException {
        Files.writeString(environmentDirectory.resolve("empty.env"), "");
    }

    @Test
    void 로컬에서는_AWS_인증_없이_문자열_메시지를_사용한다() {
        context("local").run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(KafkaTemplate.class);
            var properties = application.getBean(KafkaProperties.class);
            var producer = properties.buildProducerProperties();
            assertThat(properties.getBootstrapServers()).containsExactly("localhost:9092");
            assertThat(producer).containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
            assertThat(producer).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            assertThat(producer).doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG);
        });
    }

    @Test
    void 자동_커밋과_토픽_자동_생성을_끄고_발행_확인을_사용한다() {
        context("test").run(application -> {
            assertThat(application).hasNotFailed();
            var properties = application.getBean(KafkaProperties.class);
            ConsumerFactory<?, ?> factory = application.getBean(ConsumerFactory.class);
            var consumer = factory.getConfigurationProperties();
            assertThat(consumer).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            assertThat(consumer).containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
            assertThat(properties.getAdmin().isAutoCreate()).isFalse();
            assertThat(properties.buildProducerProperties()).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
            assertThat(properties.buildProducerProperties()).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        });
    }

    @Test
    void MSK_프로필은_모든_클라이언트에_IAM과_TLS를_적용한다() {
        context("test,msk")
                .withPropertyValues("KAFKA_BOOTSTRAP_SERVERS=broker-a.example:9098,broker-b.example:9098")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    var properties = application.getBean(KafkaProperties.class);
                    assertThat(properties.getBootstrapServers())
                            .containsExactly("broker-a.example:9098", "broker-b.example:9098");
                    var producer = properties.buildProducerProperties();
                    var consumer = properties.buildConsumerProperties();
                    var admin = properties.buildAdminProperties();
                    List.of(producer, consumer, admin).forEach(client -> {
                        assertThat(client).containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
                        assertThat(client).containsEntry(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
                        assertThat(client).containsEntry(SaslConfigs.SASL_JAAS_CONFIG,
                                "software.amazon.msk.auth.iam.IAMLoginModule required;");
                        assertThat(client).containsEntry(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                                "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
                    });
                    assertThat(Class.forName("software.amazon.msk.auth.iam.IAMClientCallbackHandler")).isNotNull();
                });
    }

    @Test
    void MSK_주소가_없으면_로컬_주소로_대체하지_않고_시작을_거부한다() {
        context("test,msk").run(application -> assertThat(application).hasFailed());
    }

    @Test
    void MSK_주소가_공백이면_시작을_거부한다() {
        context("test,msk").withPropertyValues("KAFKA_BOOTSTRAP_SERVERS= ")
                .run(application -> assertThat(application).hasFailed());
    }

    private ApplicationContextRunner context(String profiles) {
        return new ApplicationContextRunner()
                .withInitializer(application -> {
                    var sources = application.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.addFirst(new MapPropertySource("isolated-kafka-test", Map.of(
                            "DOTENV_PATH", environmentDirectory.resolve("empty.env").toString(),
                            "spring.profiles.active", profiles
                    )));
                    new ConfigDataApplicationContextInitializer().initialize(application);
                })
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withUserConfiguration(MskKafkaConfiguration.class, KafkaEventConfiguration.class)
                .withPropertyValues(
                        "KAFKA_TRIP_CREATE_REQUEST_TOPIC=test.trip-create.request",
                        "KAFKA_TRIP_CREATE_RESULT_TOPIC=test.trip-create.result",
                        "KAFKA_TRIP_UPDATE_REQUEST_TOPIC=test.trip-update.request",
                        "KAFKA_TRIP_UPDATE_RESULT_TOPIC=test.trip-update.result",
                        "KAFKA_STORY_CREATE_REQUEST_TOPIC=test.story-create.request",
                        "KAFKA_STORY_CREATE_RESULT_TOPIC=test.story-create.result"
                );
    }
}
