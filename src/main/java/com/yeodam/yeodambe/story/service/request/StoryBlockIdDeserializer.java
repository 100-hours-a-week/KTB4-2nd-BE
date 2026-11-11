package com.yeodam.yeodambe.story.service.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class StoryBlockIdDeserializer extends ValueDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_STRING
                && parser.getString().isBlank()) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }

        return context.readValue(parser, Long.class);
    }
}