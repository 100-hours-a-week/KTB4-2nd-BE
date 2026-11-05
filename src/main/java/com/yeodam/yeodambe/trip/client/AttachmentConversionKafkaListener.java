package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.service.AttachmentConversionWorker;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.attachment-conversion.mode", havingValue = "KAFKA")
@ConditionalOnExpression("!${app.attachment-conversion.operations.enabled:false}"
        + " && ${app.attachment-conversion.consumer-enabled:true}")
public class AttachmentConversionKafkaListener {
    private final AttachmentConversionWorker worker;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final String deadLetterTopic;
    private final Duration backoff;
    private final TaskScheduler scheduler;
    private final KafkaListenerEndpointRegistry registry;
    private final Map<String, TopicState> topics = new HashMap<>();
    private boolean stopping;

    public AttachmentConversionKafkaListener(AttachmentConversionWorker worker, ObjectMapper mapper,
            KafkaTemplate<String, String> kafka, AttachmentConversionProperties properties,
            @Value("${app.attachment-conversion.topics.dead-letter:yeodam-image-conversion-dlt}") String deadLetterTopic,
            @Value("${app.attachment-conversion.retry.technical-backoff}") Duration backoff,
            @Qualifier("attachmentConversionKafkaScheduler") TaskScheduler scheduler, KafkaListenerEndpointRegistry registry) {
        Assert.hasText(deadLetterTopic, "변환 DLT 토픽이 필요합니다.");
        Assert.isTrue(backoff != null && backoff.isPositive(), "변환 기술 재시도 간격은 양수여야 합니다.");
        this.worker = worker;
        this.mapper = mapper;
        this.kafka = kafka;
        this.deadLetterTopic = deadLetterTopic;
        this.backoff = backoff;
        this.scheduler = scheduler;
        this.registry = registry;
        var configured = properties.topics();
        topics.put(configured.initialLight(), new TopicState());
        topics.put(configured.initialHeic(), new TopicState());
        topics.put(configured.additionalLight(), new TopicState());
        topics.put(configured.additionalHeic(), new TopicState());
        Assert.isTrue(!topics.containsKey(deadLetterTopic), "변환 DLT는 작업 토픽과 달라야 합니다.");
    }

    @KafkaListener(id = "attachment-conversion-initial-light",
            topics = "${app.attachment-conversion.topics.initial-light:yeodam-image-initial-light}",
            groupId = "${app.attachment-conversion.group-prefix:yeodam-image-conversion}-initial-light",
            containerFactory = "attachmentConversionKafkaListenerContainerFactory")
    public CompletableFuture<Void> initialLight(ConsumerRecord<String, String> record) {
        return handle(record, Kind.INITIAL, Lane.LIGHT);
    }

    @KafkaListener(id = "attachment-conversion-initial-heic",
            topics = "${app.attachment-conversion.topics.initial-heic:yeodam-image-initial-heic}",
            groupId = "${app.attachment-conversion.group-prefix:yeodam-image-conversion}-initial-heic",
            containerFactory = "attachmentConversionKafkaListenerContainerFactory")
    public CompletableFuture<Void> initialHeic(ConsumerRecord<String, String> record) {
        return handle(record, Kind.INITIAL, Lane.HEIC);
    }

    @KafkaListener(id = "attachment-conversion-additional-light",
            topics = "${app.attachment-conversion.topics.additional-light:yeodam-image-additional-light}",
            groupId = "${app.attachment-conversion.group-prefix:yeodam-image-conversion}-additional-light",
            containerFactory = "attachmentConversionKafkaListenerContainerFactory")
    public CompletableFuture<Void> additionalLight(ConsumerRecord<String, String> record) {
        return handle(record, Kind.ADDITIONAL, Lane.LIGHT);
    }

    @KafkaListener(id = "attachment-conversion-additional-heic",
            topics = "${app.attachment-conversion.topics.additional-heic:yeodam-image-additional-heic}",
            groupId = "${app.attachment-conversion.group-prefix:yeodam-image-conversion}-additional-heic",
            containerFactory = "attachmentConversionKafkaListenerContainerFactory")
    public CompletableFuture<Void> additionalHeic(ConsumerRecord<String, String> record) {
        return handle(record, Kind.ADDITIONAL, Lane.HEIC);
    }

    private synchronized CompletableFuture<Void> handle(ConsumerRecord<String, String> record, Kind kind, Lane lane) {
        var state = topics.get(record.topic());
        Assert.state(state != null, "변환 토픽이 유효하지 않습니다.");
        if (state.current != null && !state.current.completion.isDone() && state.current.valid) {
            Assert.state(state.current.record.partition() == record.partition()
                    && state.current.record.offset() == record.offset(), "변환 토픽의 이전 작업이 대기 중입니다.");
            return state.current.completion;
        }
        var ticket = new Ticket(record, kind, lane);
        state.current = ticket;
        attempt(ticket);
        return ticket.completion;
    }

    private void attempt(Ticket ticket) {
        synchronized (this) {
            if (!ticket.valid || stopping) {
                return;
            }
            if (!ticket.malformed) {
                try {
                    var message = parse(ticket.record.value());
                    worker.process(message.taskId(), ticket.kind, ticket.lane, ticket.record.topic(), ticket.record.partition())
                            .whenComplete((ignored, failure) -> completed(ticket, failure));
                } catch (RuntimeException failure) {
                    completed(ticket, failure);
                }
                return;
            }
            ticket.publishing = true;
        }
        try {
            publishDeadLetter(ticket);
        } catch (RuntimeException failure) {
            published(ticket, failure);
        }
    }

    private AttachmentConversionMessage parse(String raw) {
        try {
            var root = mapper.readTree(raw);
            if (root == null || !root.isObject() || !root.path("schemaVersion").isIntegralNumber()
                    || !root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").intValue() != 1
                    || !root.path("taskId").isString()) {
                throw new InvalidAttachmentConversionMessageException();
            }
            String taskId = root.path("taskId").asString();
            if (!UUID.fromString(taskId).toString().equalsIgnoreCase(taskId)) {
                throw new InvalidAttachmentConversionMessageException();
            }
            return new AttachmentConversionMessage(1, taskId);
        } catch (JacksonException | IllegalArgumentException failure) {
            throw new InvalidAttachmentConversionMessageException();
        }
    }

    private synchronized void completed(Ticket ticket, Throwable failure) {
        if (!ticket.valid || stopping) {
            return;
        }
        if (failure == null) {
            ticket.completion.complete(null);
            return;
        }
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof InvalidAttachmentConversionMessageException) {
            ticket.malformed = true;
        }
        log.atWarn().addKeyValue("event", "attachment_conversion_listener_retry")
                .addKeyValue("topic", ticket.record.topic()).addKeyValue("partition", ticket.record.partition())
                .addKeyValue("offset", ticket.record.offset()).addKeyValue("failure", ticket.malformed ? "FORMAT" : "TECHNICAL")
                .log("이미지 변환 메시지를 재확인합니다.");
        ticket.retry = scheduler.schedule(() -> attempt(ticket), Instant.now().plus(backoff));
    }

    private void publishDeadLetter(Ticket ticket) {
        var original = ticket.record;
        var output = new ProducerRecord<String, String>(deadLetterTopic, original.key(), original.value());
        output.headers().add("conversion-original-topic", bytes(original.topic()));
        output.headers().add("conversion-original-partition", bytes(Integer.toString(original.partition())));
        output.headers().add("conversion-original-offset", bytes(Long.toString(original.offset())));
        output.headers().add("conversion-error", bytes("INVALID_MESSAGE"));
        kafka.execute(producer -> producer.send(output, (metadata, failure) -> published(ticket, failure)));
    }

    private synchronized void published(Ticket ticket, Throwable failure) {
        ticket.publishing = false;
        if (!ticket.valid || stopping) {
            ticket.settled.complete(null);
        } else {
            completed(ticket, failure);
        }
    }

    private void retire(Ticket ticket) {
        ticket.valid = false;
        if (ticket.retry != null) {
            ticket.retry.cancel(false);
        }
        if (!ticket.publishing) {
            ticket.settled.complete(null);
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public synchronized void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        revoke(partitions);
    }

    public synchronized void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        revoke(partitions);
    }

    private void revoke(Collection<TopicPartition> partitions) {
        var byTopic = partitions.stream().collect(Collectors.groupingBy(TopicPartition::topic,
                Collectors.mapping(TopicPartition::partition, Collectors.toSet())));
        byTopic.forEach((topic, revoked) -> {
            var state = topics.get(topic);
            state.generation++;
            state.assigned = false;
            var retired = CompletableFuture.<Void>completedFuture(null);
            if (state.current != null && revoked.contains(state.current.record.partition())) {
                retire(state.current);
                retired = state.current.settled;
            }
            state.cleanup = CompletableFuture.allOf(state.cleanup, retired, worker.revoke(topic, revoked));
        });
    }

    public synchronized void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        for (String topic : partitions.stream().map(TopicPartition::topic).distinct().toList()) {
            var state = topics.get(topic);
            long generation = ++state.generation;
            state.assigned = true;
            var container = container(topic, consumer.groupMetadata().groupId());
            if (!state.cleanup.isDone()) {
                Assert.state(container != null, "변환 listener container가 필요합니다.");
                container.pause();
                consumer.pause(partitions.stream().filter(partition -> partition.topic().equals(topic)).toList());
                state.paused = true;
                var cleanup = state.cleanup;
                cleanup.thenRun(() -> resume(state, generation, cleanup, container));
            } else if (state.paused && container != null) {
                state.paused = false;
                container.resume();
            }
        }
    }

    private synchronized void resume(TopicState state, long generation, CompletableFuture<Void> cleanup,
            MessageListenerContainer container) {
        if (!stopping && state.assigned && state.generation == generation && state.cleanup == cleanup) {
            state.paused = false;
            container.resume();
        }
    }

    private MessageListenerContainer container(String topic, String groupId) {
        return registry.getListenerContainers().stream().filter(container -> {
            var assigned = container.getAssignedPartitions();
            return groupId.equals(container.getGroupId()) && assigned != null
                    && assigned.stream().anyMatch(partition -> partition.topic().equals(topic));
        }).findFirst().orElse(null);
    }

    @PreDestroy
    public synchronized void close() {
        stopping = true;
        topics.values().forEach(state -> {
            if (state.current != null) {
                retire(state.current);
            }
        });
    }

    private static final class TopicState {
        private Ticket current;
        private CompletableFuture<Void> cleanup = CompletableFuture.completedFuture(null);
        private long generation;
        private boolean assigned;
        private boolean paused;
    }

    private static final class Ticket {
        private final ConsumerRecord<String, String> record;
        private final Kind kind;
        private final Lane lane;
        private final CompletableFuture<Void> completion = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };
        private final CompletableFuture<Void> settled = new CompletableFuture<>();
        private ScheduledFuture<?> retry;
        private boolean valid = true;
        private boolean malformed;
        private boolean publishing;

        private Ticket(ConsumerRecord<String, String> record, Kind kind, Lane lane) {
            this.record = record;
            this.kind = kind;
            this.lane = lane;
        }
    }
}
