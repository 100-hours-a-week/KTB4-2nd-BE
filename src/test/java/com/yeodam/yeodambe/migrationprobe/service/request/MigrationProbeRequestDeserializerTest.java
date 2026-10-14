package com.yeodam.yeodambe.migrationprobe.service.request;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationProbeRequestDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void probeRejectsExtraFieldsWithoutChangingGlobalMapperPolicy() {
        String json = """
                {"runId":"run","requestId":"request","payloadHash":"hash","extra":"SECRET"}
                """;
        assertThatThrownBy(() -> mapper.readValue(json, MigrationProbeRequest.class))
                .hasMessageContaining("허용되지 않거나 중복된 필드");
        assertThat(mapper.readTree(json).path("extra").asString()).isEqualTo("SECRET");
    }

    @Test
    void duplicateAndTrailingJsonCannotBeSilentlyIgnored() {
        String json = """
                {"runId":"run","requestId":"request","payloadHash":"hash"}
                """;
        assertThatThrownBy(() -> mapper.readValue(json + "{}", MigrationProbeRequest.class))
                .hasMessageContaining("JSON 형식");
        assertThatThrownBy(() -> mapper.readValue(json.replace("}", ",\"runId\":\"another\"}"), MigrationProbeRequest.class))
                .hasMessageContaining("중복된 필드");
    }
}
