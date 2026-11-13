package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.integration.service.WorkflowTraceService;
import com.yeodam.yeodambe.trip.config.AttachmentConversionProperties;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionOutbox;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionOutboxRepository;
import com.yeodam.yeodambe.trip.repository.AttachmentConversionTaskRepository;
import com.yeodam.yeodambe.trip.service.request.AttachmentConversionSeed;
import io.opentelemetry.api.trace.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class AttachmentConversionEnqueueTraceTest {
    @Test
    void 접수시_두_lane의_Outbox에_동일한_샘플링_부모를_저장하되_payload는_유지한다() {
        var tasks = mock(AttachmentConversionTaskRepository.class);
        var boxes = mock(AttachmentConversionOutboxRepository.class);
        when(tasks.save(any())).thenAnswer(call -> call.getArgument(0));
        var properties = new AttachmentConversionProperties(null, null, null, null, null, null, null, null);
        var service = new AttachmentConversionEnqueueService(tasks, boxes, properties, new ObjectMapper(), new WorkflowTraceService());
        String parent = "00-11111111111111111111111111111111-2222222222222222-01";
        var context = new WorkflowTraceService().restore(new com.yeodam.yeodambe.integration.service.response.WorkflowTraceCarrier(parent, "test=fixture"));
        try (var scope = context.makeCurrent()) {
            service.enqueue(new AttachmentConversionSeed(AttachmentConversionTask.Kind.INITIAL, 1L, 2L, "request",
                    List.of(new AttachmentConversionSeed.Photo(1L, "image/jpeg", 10), new AttachmentConversionSeed.Photo(2L, "image/heic", 10))));
        }
        var saved = ArgumentCaptor.forClass(AttachmentConversionOutbox.class);
        verify(boxes, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(box -> {
            assertThat(box.getTraceparent()).isEqualTo(parent);
            assertThat(box.getTracestate()).isEqualTo("test=fixture");
            assertThat(box.getPayload()).doesNotContain("traceparent", "tracestate");
        });
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }
}
