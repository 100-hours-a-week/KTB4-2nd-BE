package com.yeodam.yeodambe.story.service;

import com.yeodam.yeodambe.story.client.StoryGenerationPublisher;
import com.yeodam.yeodambe.story.entity.StoryGenerationOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoryGenerationPublicationService {

    private final StoryGenerationPublicationStateService stateService;
    private final StoryGenerationPublisher publisher;

    public CompletableFuture<Void> publish(Long generationId) {
        StoryGenerationOutbox outbox = stateService.begin(generationId);

        try {
            return publisher.publish(outbox.getPayload())
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            log.atInfo()
                                    .addKeyValue("event", "story_generation_published")
                                    .addKeyValue("result", "success")
                                    .addKeyValue("request_id", outbox.getRequestId())
                                    .addKeyValue("job_id", generationId)
                                    .log("Kafka 스토리 생성 요청 발행이 완료되었습니다.");
                            stateService.succeeded(
                                    outbox.getId(),
                                    LocalDateTime.now()
                            );
                        } else {
                            logPublishFailure(outbox, failure);
                            stateService.failed(
                                    outbox.getId(),
                                    failure.toString()
                            );
                        }
                    })
                    .thenApply(result -> null);
        } catch (RuntimeException failure) {
            logPublishFailure(outbox, failure);
            stateService.failed(
                    outbox.getId(),
                    failure.toString()
            );

            return CompletableFuture.failedFuture(failure);
        }
    }

    private void logPublishFailure(StoryGenerationOutbox outbox, Throwable failure) {
        log.atError()
                .addKeyValue("event", "story_generation_publish_failed")
                .addKeyValue("result", "failed")
                .addKeyValue("request_id", outbox.getRequestId())
                .addKeyValue("job_id", outbox.getGenerationId())
                .addKeyValue("failure_stage", "kafka_send")
                .setCause(failure)
                .log("Kafka 스토리 생성 요청 발행에 실패했습니다.");
    }
}
