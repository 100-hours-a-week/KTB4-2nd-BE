package com.yeodam.yeodambe.migrationprobe.service.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MigrationProbeRequestDeserializer extends ValueDeserializer<MigrationProbeRequest> {

    private static final Set<String> FIELDS = Set.of("runId", "requestId", "payloadHash");

    @Override
    public MigrationProbeRequest deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            return context.reportInputMismatch(MigrationProbeRequest.class, "시험 요청은 JSON 객체여야 합니다.");
        }

        Map<String, String> values = new HashMap<>();
        JsonToken token = parser.nextToken();
        while (token == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            if (!FIELDS.contains(name) || values.containsKey(name)) {
                return context.reportInputMismatch(MigrationProbeRequest.class, "허용되지 않거나 중복된 필드입니다.");
            }
            if (parser.nextToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(MigrationProbeRequest.class, "시험 요청 필드는 문자열이어야 합니다.");
            }
            values.put(name, parser.getString());
            token = parser.nextToken();
        }

        if (token != JsonToken.END_OBJECT || !values.keySet().equals(FIELDS) || parser.nextToken() != null) {
            return context.reportInputMismatch(MigrationProbeRequest.class, "시험 요청 JSON 형식이 올바르지 않습니다.");
        }

        return new MigrationProbeRequest(values.get("runId"), values.get("requestId"), values.get("payloadHash"));
    }
}
