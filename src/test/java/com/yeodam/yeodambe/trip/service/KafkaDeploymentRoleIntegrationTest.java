package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.SharedTestKafka;
import com.yeodam.yeodambe.TestcontainersConfiguration;
import com.yeodam.yeodambe.YeodamBeApplication;
import com.yeodam.yeodambe.story.service.StoryGenerationStatusScheduler;
import com.yeodam.yeodambe.trip.entity.Trip;
import com.yeodam.yeodambe.trip.entity.TripCreationExecution;
import com.yeodam.yeodambe.trip.repository.TripCreationExecutionRepository;
import com.yeodam.yeodambe.trip.repository.TripRepository;
import com.yeodam.yeodambe.trip.service.response.TripProcessingStatusResponse;
import com.yeodam.yeodambe.user.entity.User;
import com.yeodam.yeodambe.user.repository.UserRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListGroupsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class KafkaDeploymentRoleIntegrationTest {
    private static final List<String> IMAGE_TOPICS = List.of(
            "initial-light", "initial-heic", "additional-light", "additional-heic", "dead-letter");
    private static final List<String> AI_TOPICS = List.of(
            "trip-create-request", "trip-create-result", "trip-update-request", "trip-update-result",
            "story-create-request", "story-create-result");

    @ParameterizedTest
    @CsvSource({"converter,true,false,5", "result,false,true,4", "api,false,false,0"})
    void rolesStartAndStopOnlyTheirConsumersAndWorkers(
            String role,
            boolean conversionEnabled,
            boolean resultEnabled,
            int consumerCount
    ) throws Exception {
        String prefix = "role-" + role + "-" + UUID.randomUUID();
        List<MessageListenerContainer> containers;
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", SharedTestKafka.bootstrapServers()))) {
            createTopics(admin, prefix);
            SpringApplication application = new SpringApplication(YeodamBeApplication.class, TestcontainersConfiguration.class);
            application.setWebApplicationType(WebApplicationType.SERVLET);
            application.setRegisterShutdownHook(false);
            try (ConfigurableApplicationContext context = application.run(arguments(prefix, conversionEnabled, resultEnabled))) {
                KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);
                containers = new ArrayList<>(registry.getAllListenerContainers());
                List<MessageListenerContainer> running = containers.stream()
                        .filter(MessageListenerContainer::isRunning).toList();
                assertThat(running).hasSize(consumerCount);
                assertThat(context.getBeansOfType(AttachmentConversionWorker.class)).hasSize(conversionEnabled ? 1 : 0);
                assertThat(context.getBeansOfType(TripAnalysisResultWorker.class)).hasSize(resultEnabled ? 1 : 0);
                assertThat(context.getBeansOfType(AdditionalAttachmentResultWorker.class)).hasSize(resultEnabled ? 1 : 0);
                assertThat(context.getBeansOfType(TripUploadTimeoutService.class)).hasSize(resultEnabled ? 1 : 0);
                assertThat(context.getBeansOfType(TripProcessingStatusScheduler.class)).hasSize(resultEnabled ? 1 : 0);
                assertThat(context.getBeansOfType(StoryGenerationStatusScheduler.class)).hasSize(resultEnabled ? 1 : 0);

                Set<String> expectedGroups = switch (role) {
                    case "converter" -> Set.of(prefix + "-conversion-initial-light", prefix + "-conversion-initial-heic",
                            prefix + "-conversion-additional-light", prefix + "-conversion-additional-heic",
                            prefix + "-conversion-receipts");
                    case "result" -> Set.of(prefix + "-create", prefix + "-update", prefix + "-story", prefix + "-ai-receipts");
                    default -> Set.of();
                };
                assertThat(running.stream().map(MessageListenerContainer::getGroupId).collect(Collectors.toSet()))
                        .containsExactlyInAnyOrderElementsOf(expectedGroups);
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    assertThat(running).allSatisfy(container -> assertThat(container.getAssignedPartitions()).isNotEmpty());
                    Set<String> activeGroups = admin.listGroups(ListGroupsOptions.forConsumerGroups()).all().get(5, TimeUnit.SECONDS).stream()
                            .map(group -> group.groupId()).filter(group -> group.startsWith(prefix)).collect(Collectors.toSet());
                    assertThat(activeGroups).containsExactlyInAnyOrderElementsOf(expectedGroups);
                    if (!expectedGroups.isEmpty()) {
                        Map<String, ConsumerGroupDescription> descriptions =
                                admin.describeConsumerGroups(expectedGroups).all().get(5, TimeUnit.SECONDS);
                        assertThat(descriptions.values()).allSatisfy(group -> assertThat(group.members()).isNotEmpty());
                    }
                });
                if (role.equals("api")) {
                    assertPendingResultStaysStored(context);
                }
            }
            assertThat(containers).allSatisfy(container -> assertThat(container.isRunning()).isFalse());
        }
    }

    private void createTopics(AdminClient admin, String prefix) throws Exception {
        List<NewTopic> topics = Stream.concat(
                IMAGE_TOPICS.stream().map(suffix -> prefix + "-image-" + suffix),
                AI_TOPICS.stream().flatMap(suffix -> Stream.of(prefix + "-" + suffix, prefix + "-" + suffix + "-dlt"))
        ).map(name -> new NewTopic(name, 1, (short) 1).configs(Map.of("message.timestamp.type", "LogAppendTime"))).toList();
        admin.createTopics(topics).all().get(15, TimeUnit.SECONDS);
    }

    private String[] arguments(String prefix, boolean conversionEnabled, boolean resultEnabled) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.profiles.active=test", "--spring.config.import=", "--server.address=127.0.0.1", "--server.port=0",
                "--app.attachment-conversion.mode=KAFKA",
                "--app.attachment-conversion.operations.enabled=false",
                "--app.attachment-conversion.consumer-enabled=" + conversionEnabled,
                "--app.attachment-conversion.receipt-listener-enabled=" + conversionEnabled,
                "--app.attachment-conversion.recovery-enabled=" + conversionEnabled,
                "--app.attachment-conversion.group-prefix=" + prefix + "-conversion",
                "--app.attachment-conversion.retry.technical-backoff=100ms",
                "--app.attachment-conversion.kafka.fetch-max-bytes=1048576",
                "--app.attachment-conversion.kafka.partition-fetch-max-bytes=524288",
                "--app.attachment-conversion.kafka.max-message-bytes=262144",
                "--app.attachment-conversion.admission.max-unfinished-photos.LIGHT=100",
                "--app.attachment-conversion.admission.max-unfinished-photos.HEIC=100",
                "--app.attachment-conversion.admission.max-unfinished-bytes.LIGHT=100000",
                "--app.attachment-conversion.admission.max-unfinished-bytes.HEIC=100000",
                "--app.attachment-conversion.admission.max-unfinished-age=1h",
                "--app.attachment-conversion.admission.snapshot-max-age=30s",
                "--app.attachment-conversion.admission.sample-interval=10s",
                "--app.attachment-conversion.admission.reopen-ratio=0.5",
                "--app.attachment-conversion.recovery.interval=5m",
                "--app.attachment-conversion.recovery.page-size=10",
                "--app.attachment-conversion.recovery.max-pages-per-run=2",
                "--app.attachment-conversion.recovery.max-reissues-per-run=10",
                "--app.trip.result-worker-enabled=" + resultEnabled,
                "--app.trip.result-listener-enabled=" + resultEnabled,
                "--app.trip.status-polling-enabled=" + resultEnabled,
                "--app.story.result-listener-enabled=" + resultEnabled,
                "--app.story.status-polling-enabled=" + resultEnabled,
                "--app.outbox.receipt-listener-enabled=" + resultEnabled,
                "--app.trip.result-consumer-group=" + prefix + "-create",
                "--KAFKA_TRIP_UPDATE_RESULT_CONSUMER_GROUP=" + prefix + "-update",
                "--app.story.result-consumer-group=" + prefix + "-story",
                "--app.outbox.receipt-consumer-group=" + prefix + "-ai-receipts"));
        for (String suffix : IMAGE_TOPICS) {
            arguments.add("--app.attachment-conversion.topics." + suffix + "=" + prefix + "-image-" + suffix);
        }
        for (String suffix : AI_TOPICS) {
            arguments.add("--app.kafka.topics." + suffix + "=" + prefix + "-" + suffix);
            arguments.add("--app.kafka.dead-letter-topics." + suffix + "=" + prefix + "-" + suffix + "-dlt");
        }
        return arguments.toArray(String[]::new);
    }

    private void assertPendingResultStaysStored(ConfigurableApplicationContext context) {
        User owner = context.getBean(UserRepository.class).save(new User(UUID.randomUUID() + "@role.test", "역할"));
        Trip trip = context.getBean(TripRepository.class).save(
                new Trip(owner.getUserId(), "역할 검증", LocalDate.now(), LocalDate.now()));
        TripCreationExecution execution = new TripCreationExecution("role-execution", trip.getId(), owner.getUserId(), 2);
        execution.recordProcessPublished(LocalDateTime.now());
        execution.receiveResult("{}", "COMPLETED", LocalDateTime.now());
        TripCreationExecutionRepository executions = context.getBean(TripCreationExecutionRepository.class);
        executions.save(execution);

        TripProcessingStatusResponse response = context.getBean(TripProcessingStatusService.class)
                .findStatus(trip.getId(), owner.getUserId());
        TripCreationExecution stored = executions.findById(execution.getExecutionId()).orElseThrow();

        assertThat(response.status()).isEqualTo(TripProcessingStatusResponse.Status.FINALIZING);
        assertThat(response.statusVersion()).isEqualTo(execution.getStatusVersion());
        assertThat(stored.getResultRawPayload()).isEqualTo("{}");
        assertThat(stored.getResultClaimToken()).isNull();
        assertThat(stored.getResultAttempts()).isZero();
    }
}
