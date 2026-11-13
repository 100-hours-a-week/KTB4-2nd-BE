package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionOutbox;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Lane;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionOutboxRepository;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionMessage;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
import lombok.RequiredArgsConstructor;
import com.yeodam.yeodambe.integration.service.WorkflowTraceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentConversionEnqueueService {
    private final AttachmentConversionTaskRepository tasks;
    private final AttachmentConversionOutboxRepository outboxes;
    private final AttachmentConversionProperties properties;
    private final ObjectMapper json;
    private final WorkflowTraceService traces;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> enqueue(AttachmentConversionSeed seed) {
        List<String> taskIds = new ArrayList<>();
        for (Lane lane : Lane.values()) {
            var photos = seed.photos().stream()
                    .filter(photo -> ("image/heic".equals(photo.contentType()) ? Lane.HEIC : Lane.LIGHT) == lane)
                    .toList();
            if (photos.isEmpty()) {
                continue;
            }
            String taskId = UUID.randomUUID().toString();
            var task = tasks.save(new AttachmentConversionTask(
                    taskId, seed.kind(), seed.batchId(), seed.tripId(), lane, photos.size(),
                    photos.stream().mapToLong(AttachmentConversionSeed.Photo::sizeBytes).sum()));
            outboxes.save(new AttachmentConversionOutbox(
                    UUID.randomUUID().toString(), taskId, task.getLatestDeliveryNo(), seed.tripId(),
                    topic(seed.kind(), lane), json.writeValueAsString(new AttachmentConversionMessage(1, taskId)),
                    seed.requestId(), traces.capture()));
            taskIds.add(taskId);
        }
        return List.copyOf(taskIds);
    }

    private String topic(Kind kind, Lane lane) {
        var topics = properties.topics();
        return switch (kind) {
            case INITIAL -> lane == Lane.LIGHT ? topics.initialLight() : topics.initialHeic();
            case ADDITIONAL -> lane == Lane.LIGHT ? topics.additionalLight() : topics.additionalHeic();
        };
    }
}
