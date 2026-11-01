package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.service.request.AttachmentConversionMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentConversionMessageTest {

    @Test
    void messageContainsOnlyVersionAndTaskId() {
        ObjectMapper mapper = new ObjectMapper();
        String taskId = "00000000-0000-0000-0000-000000000001";
        String wire = mapper.writeValueAsString(new AttachmentConversionMessage(1, taskId));
        Map<String, Object> wireMap = mapper.readValue(wire, new TypeReference<>() {});

        assertThat(wireMap.keySet()).containsExactlyInAnyOrder("schemaVersion", "taskId");
        assertThat(wireMap.get("schemaVersion")).isEqualTo(1);
        assertThat(wireMap.get("taskId")).isEqualTo(taskId);
        assertThat(mapper.readValue(wire, AttachmentConversionMessage.class))
                .isEqualTo(new AttachmentConversionMessage(1, taskId));
    }
}
