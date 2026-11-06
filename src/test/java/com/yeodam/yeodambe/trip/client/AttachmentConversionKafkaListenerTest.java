package com.yeodam.yeodambe.trip.client;

import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.service.AttachmentConversionWorker;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AttachmentConversionKafkaListenerTest {
    private final AttachmentConversionWorker worker = mock(AttachmentConversionWorker.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final AttachmentConversionProperties properties = new AttachmentConversionProperties(
            null,
            null,
            null,
            null,
            null,
            true,
            true,
            true
    );
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final AttachmentConversionKafkaListener listener;

    AttachmentConversionKafkaListenerTest() {
        scheduler.setPoolSize(4);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        executeViaProducer(kafka);
        listener = new AttachmentConversionKafkaListener(worker, JsonMapper.builder().build(), kafka,
                properties, "conversion-dlt", Duration.ofMillis(50), scheduler, registry);
        when(worker.revoke(anyString(), anySet())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @AfterEach
    void close() {
        listener.close();
        scheduler.shutdown();
    }

    @Test
    void 네_진입점은_고정된_종류와_레인과_레코드_위치를_전달한다() throws Exception {
        when(worker.process(anyString(), any(), any(), anyString(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        var topics = properties.topics();
        listener.initialLight(record(topics.initialLight(), valid())).get(1, TimeUnit.SECONDS);
        listener.initialHeic(record(topics.initialHeic(), valid())).get(1, TimeUnit.SECONDS);
        listener.additionalLight(record(topics.additionalLight(), valid())).get(1, TimeUnit.SECONDS);
        listener.additionalHeic(record(topics.additionalHeic(), valid())).get(1, TimeUnit.SECONDS);
        verify(worker).process(anyString(), eq(Kind.INITIAL), eq(Lane.LIGHT), eq(topics.initialLight()), eq(2));
        verify(worker).process(anyString(), eq(Kind.INITIAL), eq(Lane.HEIC), eq(topics.initialHeic()), eq(2));
        verify(worker).process(anyString(), eq(Kind.ADDITIONAL), eq(Lane.LIGHT), eq(topics.additionalLight()), eq(2));
        verify(worker).process(anyString(), eq(Kind.ADDITIONAL), eq(Lane.HEIC), eq(topics.additionalHeic()), eq(2));
        verifyNoInteractions(kafka);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "[]", "{\"schemaVersion\":2,\"taskId\":\"00000000-0000-0000-0000-000000000001\"}",
            "{\"schemaVersion\":1,\"taskId\":\"1-1-1-1-1\"}", "{\"schemaVersion\":1,\"taskId\":\"invalid\"}",
            "{\"schemaVersion\":1.5,\"taskId\":\"00000000-0000-0000-0000-000000000001\"}",
            "{\"schemaVersion\":\"1\",\"taskId\":\"00000000-0000-0000-0000-000000000001\"}"})
    void 잘못된_메시지는_DLT_게시_성공_후에만_ACK한다(String payload) throws Exception {
        var published = new CompletableFuture<SendResult<String, String>>();
        var sent = new CountDownLatch(1);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            ProducerRecord<String, String> dlt = call.getArgument(0);
            assertThat(dlt.topic()).isEqualTo("conversion-dlt");
            assertThat(dlt.partition()).isNull();
            assertThat(dlt.value()).isEqualTo(payload);
            assertThat(dlt.headers()).extracting(header -> header.key()).containsExactlyInAnyOrder(
                    "conversion-original-topic", "conversion-original-partition", "conversion-original-offset", "conversion-error");
            assertThat(new String(dlt.headers().lastHeader("conversion-original-offset").value(), StandardCharsets.UTF_8))
                    .isEqualTo("9");
            sent.countDown();
            return published;
        });
        var completion = listener.initialLight(record(properties.topics().initialLight(), payload));
        assertThat(sent.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(completion).isNotDone();
        published.complete(null);
        completion.get(1, TimeUnit.SECONDS);
        verifyNoInteractions(worker);
    }

    @Test
    void DLT_전송이_막혀도_다른_토픽_리스너의_모니터를_점유하지_않는다() throws Exception {
        var sending = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            sending.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return CompletableFuture.completedFuture(null);
        });
        when(worker.process(anyString(), any(), any(), anyString(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(null));
        var heic = listener.initialHeic(record(properties.topics().initialHeic(), "{"));
        try (var poll = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try {
                assertThat(sending.await(1, TimeUnit.SECONDS)).isTrue();
                var light = poll.submit(() -> listener.initialLight(record(properties.topics().initialLight(), valid())));
                light.get(500, TimeUnit.MILLISECONDS).get(500, TimeUnit.MILLISECONDS);
                assertThat(heic).isNotDone();
            } finally {
                release.countDown();
            }
        }
        heic.get(1, TimeUnit.SECONDS);
    }

    @Test
    void DLT_실패시_콜백_재시도_중에도_외부_Future는_대기한다() throws Exception {
        var attempts = new AtomicInteger();
        var retried = new CountDownLatch(1);
        var publication = new CompletableFuture<SendResult<String, String>>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(new IllegalStateException("sensitive-url?secret=hidden"));
            }
            retried.countDown();
            return publication;
        });
        var result = listener.initialLight(record(properties.topics().initialLight(), "{"));
        assertThat(retried.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(result).isNotDone();
        assertThat(result.cancel(true)).isFalse();
        publication.complete(null);
        result.get(1, TimeUnit.SECONDS);
    }

    @Test
    void 동기와_비동기_기술_실패가_ACK_Future의_예외_완료로_전파되지_않는다() throws Exception {
        var attempts = new AtomicInteger();
        var retried = new CountDownLatch(1);
        var databaseAndCleanup = new CompletableFuture<Void>();
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenAnswer(call -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new ClassCastException("sensitive input");
            }
            if (attempt == 2) {
                return CompletableFuture.failedFuture(new TransientDataAccessResourceException("DB unavailable"));
            }
            retried.countDown();
            return databaseAndCleanup;
        });
        var result = listener.initialLight(record(properties.topics().initialLight(), valid()));
        assertThat(retried.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(result).isNotDone();
        databaseAndCleanup.complete(null);
        result.get(1, TimeUnit.SECONDS);
        verifyNoInteractions(kafka);
    }

    @Test
    void 작업_Future_취소시_ACK_Future를_취소하지_않고_재시도한다() throws Exception {
        var cancelled = new CompletableFuture<Void>();
        cancelled.cancel(true);
        var pending = new CompletableFuture<Void>();
        var retried = new CountDownLatch(1);
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenReturn(cancelled).thenAnswer(call -> {
            retried.countDown();
            return pending;
        });
        var completion = listener.initialLight(record(properties.topics().initialLight(), valid()));
        assertThat(retried.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(completion).isNotDone();
        pending.complete(null);
        completion.get(1, TimeUnit.SECONDS);
        verifyNoInteractions(kafka);
    }

    @Test
    void 작업_실패_중_잘못된_DB_라우팅만_DLT로_보낸다() throws Exception {
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenReturn(
                CompletableFuture.failedFuture(new InvalidAttachmentConversionMessageException()));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        listener.initialLight(record(properties.topics().initialLight(), valid())).get(1, TimeUnit.SECONDS);
        verify(kafka).send(any(ProducerRecord.class));
    }

    @Test
    void 파티션을_잃으면_미완료_작업을_ACK하지_않는다() {
        var current = new CompletableFuture<Void>();
        when(worker.process(anyString(), any(), any(), anyString(), anyInt())).thenReturn(current);
        var record = record(properties.topics().initialLight(), valid());
        var result = listener.initialLight(record);
        listener.onPartitionsLost(mock(Consumer.class), List.of(new TopicPartition(record.topic(), record.partition())));
        current.complete(null);
        assertThat(result).isNotDone();
        verify(worker).revoke(record.topic(), Set.of(2));
    }

    @Test
    void 재할당시_해당_정리_세대가_끝날_때까지_컨슈머_스레드에서_일시정지한다() {
        var cleanup = new CompletableFuture<Void>();
        String topic = properties.topics().initialLight();
        var partitions = List.of(new TopicPartition(topic, 2));
        var container = mock(MessageListenerContainer.class);
        when(container.getAssignedPartitions()).thenReturn(partitions);
        when(container.getGroupId()).thenReturn("work-group");
        when(registry.getListenerContainers()).thenReturn(List.of(container));
        when(worker.revoke(topic, Set.of(2))).thenReturn(cleanup);
        var consumer = consumer();
        listener.onPartitionsRevokedBeforeCommit(consumer, partitions);
        listener.onPartitionsAssigned(consumer, partitions);
        verify(consumer).pause(partitions);
        verify(container).pause();
        listener.onPartitionsRevokedBeforeCommit(consumer, partitions);
        cleanup.complete(null);
        verify(container, never()).resume();
    }

    @Test
    void 할당_회수시_원본을_ACK하지_않고_예약된_재시도를_제거한다() {
        var waiting = new AttachmentConversionKafkaListener(worker, JsonMapper.builder().build(), kafka,
                properties, "conversion-dlt", Duration.ofSeconds(30), scheduler, registry);
        when(worker.process(anyString(), any(), any(), anyString(), anyInt()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("technical retry")));
        var record = record(properties.topics().initialLight(), valid());
        var completion = waiting.initialLight(record);
        assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).hasSize(1);
        waiting.onPartitionsRevokedBeforeCommit(mock(Consumer.class), List.of(new TopicPartition(record.topic(), 2)));
        assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
        assertThat(completion).isNotDone();
        waiting.close();
    }

    @Test
    void 재할당시_이전_세대를_ACK하지_않고_회수된_DLT_게시를_기다린다() throws Exception {
        var publication = new CompletableFuture<SendResult<String, String>>();
        var sent = new CountDownLatch(1);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            sent.countDown();
            return publication;
        });
        String topic = properties.topics().initialLight();
        var partitions = List.of(new TopicPartition(topic, 2));
        var container = mock(MessageListenerContainer.class);
        when(container.getAssignedPartitions()).thenReturn(partitions);
        when(container.getGroupId()).thenReturn("work-group");
        when(registry.getListenerContainers()).thenReturn(List.of(container));
        var completion = listener.initialLight(record(topic, "{"));
        assertThat(sent.await(1, TimeUnit.SECONDS)).isTrue();
        var consumer = consumer();
        listener.onPartitionsRevokedBeforeCommit(consumer, partitions);
        listener.onPartitionsAssigned(consumer, partitions);
        verify(consumer).pause(partitions);
        verify(container, never()).resume();
        publication.complete(null);
        verify(container).resume();
        assertThat(completion).isNotDone();
    }

    @Test
    void 실제_프로듀서_실패시_DLT_페이로드와_예외를_로그에_남기지_않고_재시도한다() throws Exception {
        var nativeProducer = new org.apache.kafka.clients.producer.MockProducer<String, String>(false, null,
                new org.apache.kafka.common.serialization.StringSerializer(), new org.apache.kafka.common.serialization.StringSerializer());
        var producers = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(java.util.Map.of("bootstrap.servers", "unused:9092")) {
            @Override
            protected org.apache.kafka.clients.producer.Producer<String, String> createRawProducer(java.util.Map<String, Object> settings) {
                return nativeProducer;
            }
        };
        var actualTemplate = new KafkaTemplate<>(producers);
        var defaultProducerListener = org.springframework.test.util.ReflectionTestUtils.getField(actualTemplate, "producerListener");
        assertThat(defaultProducerListener).isInstanceOf(org.springframework.kafka.support.LoggingProducerListener.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.springframework.kafka.support.LoggingProducerListener.class);
        logger.addAppender(logs);
        var actualListener = new AttachmentConversionKafkaListener(worker, JsonMapper.builder().build(), actualTemplate,
                properties, "conversion-dlt", Duration.ofMillis(50), scheduler, registry);
        String payload = "{\"private\":\"https://private?secret=task9\"}";
        try {
            var completion = actualListener.initialHeic(record(properties.topics().initialHeic(), payload));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> nativeProducer.history().size() == 1);
            assertThat(nativeProducer.errorNext(new IllegalStateException("private-producer-detail"))).isTrue();
            assertThat(completion).isNotDone();
            assertThat(logs.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(payload, "secret=task9", "private-producer-detail");
                assertThat(event.getThrowableProxy()).isNull();
            });
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> nativeProducer.history().size() == 2);
            assertThat(nativeProducer.completeNext()).isTrue();
            completion.get(1, TimeUnit.SECONDS);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(actualTemplate, "producerListener"))
                    .isSameAs(defaultProducerListener);
        } finally {
            actualListener.close();
            actualTemplate.destroy();
            producers.destroy();
            logger.detachAppender(logs);
            logs.stop();
        }
        assertThat(nativeProducer.closed()).isTrue();
    }

    @Test
    void 토픽을_공유하는_수신_확인_컨테이너를_작업_그룹_대신_선택하지_않는다() {
        String topic = properties.topics().initialLight();
        var partitions = List.of(new TopicPartition(topic, 2));
        var receipt = mock(MessageListenerContainer.class);
        var work = mock(MessageListenerContainer.class);
        when(receipt.getAssignedPartitions()).thenReturn(partitions);
        when(work.getAssignedPartitions()).thenReturn(partitions);
        when(receipt.getGroupId()).thenReturn("receipts");
        when(work.getGroupId()).thenReturn("work-group");
        when(registry.getListenerContainers()).thenReturn(List.of(receipt, work));
        var cleanup = new CompletableFuture<Void>();
        when(worker.revoke(topic, Set.of(2))).thenReturn(cleanup);
        var consumer = consumer();
        listener.onPartitionsRevokedBeforeCommit(consumer, partitions);
        listener.onPartitionsAssigned(consumer, partitions);
        verify(work).pause();
        verify(receipt, never()).pause();
        cleanup.complete(null);
        verify(work).resume();
        verify(receipt, never()).resume();
    }

    @Test
    void 작업_토픽으로_되돌아가는_DLT_토픽을_거부한다() {
        assertThatThrownBy(() -> new AttachmentConversionKafkaListener(worker, JsonMapper.builder().build(), kafka,
                properties, properties.topics().initialLight(), Duration.ofMillis(50), scheduler, registry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void executeViaProducer(KafkaTemplate<String, String> template) {
        var producer = mock(org.apache.kafka.clients.producer.Producer.class);
        when(template.execute(any())).thenAnswer(call -> {
            org.springframework.kafka.core.KafkaOperations.ProducerCallback<String, String, Object> callback = call.getArgument(0);
            return callback.doInKafka(producer);
        });
        when(producer.send(any(ProducerRecord.class), any(org.apache.kafka.clients.producer.Callback.class))).thenAnswer(call -> {
            ProducerRecord<String, String> record = call.getArgument(0);
            org.apache.kafka.clients.producer.Callback callback = call.getArgument(1);
            return template.send(record).whenComplete((result, failure) -> callback.onCompletion(
                    result == null ? null : result.getRecordMetadata(), failure == null ? null : (Exception) failure))
                    .thenApply(result -> result == null ? null : result.getRecordMetadata());
        });
    }

    private Consumer<?, ?> consumer() {
        var consumer = mock(Consumer.class);
        var metadata = mock(org.apache.kafka.clients.consumer.ConsumerGroupMetadata.class);
        when(metadata.groupId()).thenReturn("work-group");
        when(consumer.groupMetadata()).thenReturn(metadata);
        return consumer;
    }

    private ConsumerRecord<String, String> record(String topic, String payload) {
        return new ConsumerRecord<>(topic, 2, 9, "trip-7", payload);
    }

    private String valid() {
        return "{\"schemaVersion\":1,\"taskId\":\"" + UUID.randomUUID() + "\"}";
    }
}
