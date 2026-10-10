package com.yeodam.yeodambe.integration.event;

import com.yeodam.yeodambe.common.event.EventPayload;
import com.yeodam.yeodambe.common.event.EventInternalErrorMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;

@Component
public class EventJsonCodec {

    private final ObjectMapper objectMapper;

    public EventJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(EventPayload payload) {
        Assert.notNull(payload, EventInternalErrorMessage.EVENT_PAYLOAD_MISSING.message());
        return objectMapper.writeValueAsString(payload);
    }

    public <T extends EventPayload> T deserialize(String json, Class<T> payloadType) {
        Assert.hasText(json, EventInternalErrorMessage.EVENT_JSON_BLANK.message());
        Assert.notNull(payloadType, EventInternalErrorMessage.EVENT_PAYLOAD_TYPE_MISSING.message());
        T payload = objectMapper.readValue(json, payloadType);
        Assert.notNull(payload, EventInternalErrorMessage.EVENT_JSON_NULL_PAYLOAD.message());
        return payload;
    }
}
