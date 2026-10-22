package com.yeodam.yeodambe.story.config;

import com.yeodam.yeodambe.story.exception.StoryGenerationInternalErrorMessage;
import com.yeodam.yeodambe.story.exception.InvalidStoryResultException;
import com.yeodam.yeodambe.story.service.StoryGenerationResultRecoveryService;
import com.yeodam.yeodambe.integration.service.KafkaDltLogService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StoryGenerationKafkaConfigurationTest {
    @Test
    @SuppressWarnings("unchecked")
    void failedDeadLetterPublishDoesNotRecoverOrFailJobAndCanBeRetried() {
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var recovery = mock(StoryGenerationResultRecoveryService.class);
        var dltLogs = mock(KafkaDltLogService.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(
                        new ProducerRecord<>("story-dlt", "7", "{broken"),
                        new RecordMetadata(new TopicPartition("story-dlt", 0), 0, 0, 0, 1, 7))));
        var factory = new StoryGenerationKafkaConfiguration(dltLogs).storyResultKafkaListenerContainerFactory(
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class),
                mock(ConsumerFactory.class), kafka, recovery, "story-dlt");
        var handler = (DefaultErrorHandler) org.springframework.test.util.ReflectionTestUtils.getField(factory, "commonErrorHandler");
        var record = new ConsumerRecord<>("ai-results", 0, 10, "7", "{broken");
        var consumer = mock(Consumer.class);
        var container = mock(MessageListenerContainer.class);
        var failure = new InvalidStoryResultException(
                StoryGenerationInternalErrorMessage.RESULT_MESSAGE_UNREADABLE
        );

        assertThat(handler.handleOne(failure, record, consumer, container)).isFalse();
        verifyNoInteractions(recovery, dltLogs);
        assertThat(handler.handleOne(failure, record, consumer, container)).isTrue();
        var ordered = inOrder(dltLogs, recovery);
        ordered.verify(dltLogs).logPublished(record, failure, "story-dlt");
        ordered.verify(recovery).failIdentifiedJob("{broken");
        assertThat(factory.getContainerProperties().isDeliveryAttemptHeader()).isTrue();
    }
    @Test
    @SuppressWarnings("unchecked")
    void DLT_성공_후_작업_복구가_실패해도_발행_로그를_남긴다() {
        var kafka = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        var recovery = mock(StoryGenerationResultRecoveryService.class);
        var dltLogs = mock(KafkaDltLogService.class);
        var published = new ProducerRecord<>("story-dlt", "7", "{broken");
        var metadata = new RecordMetadata(new TopicPartition("story-dlt", 0), 0, 0, 0, 1, 7);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(published, metadata)));
        doThrow(new IllegalStateException("database unavailable"))
                .when(recovery).failIdentifiedJob("{broken");
        var factory = new StoryGenerationKafkaConfiguration(dltLogs).storyResultKafkaListenerContainerFactory(
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class),
                mock(ConsumerFactory.class), kafka, recovery, "story-dlt");
        var handler = (DefaultErrorHandler) org.springframework.test.util.ReflectionTestUtils
                .getField(factory, "commonErrorHandler");
        var record = new ConsumerRecord<>("story-result", 0, 10, "7", "{broken");
        var failure = new InvalidStoryResultException(
                StoryGenerationInternalErrorMessage.RESULT_MESSAGE_UNREADABLE
        );

        assertThat(handler.handleOne(failure, record, mock(Consumer.class),
                mock(MessageListenerContainer.class))).isFalse();
        var ordered = inOrder(dltLogs, recovery);
        ordered.verify(dltLogs).logPublished(record, failure, "story-dlt");
        ordered.verify(recovery).failIdentifiedJob("{broken");
    }
}
