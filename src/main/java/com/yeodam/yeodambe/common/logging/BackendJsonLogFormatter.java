package com.yeodam.yeodambe.common.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.JsonWriterStructuredLogFormatter;
import org.springframework.core.env.Environment;

public class BackendJsonLogFormatter
        extends JsonWriterStructuredLogFormatter<ILoggingEvent> {

    public BackendJsonLogFormatter(
            Environment environment,
            ThrowableProxyConverter throwableProxyConverter
    ) {
        super(members -> addMembers(environment, throwableProxyConverter, members), null);
    }

    private static void addMembers(
            Environment environment,
            ThrowableProxyConverter throwableProxyConverter,
            JsonWriter.Members<ILoggingEvent> members
    ) {
        members.add("timestamp", event -> event.getInstant().toString());
        members.add("level", event -> event.getLevel().toString());
        members.add("service", event -> isKafkaDltEvent(event) ? "be" : "backend");
        members.add("logger", ILoggingEvent::getLoggerName);
        members.add("message", ILoggingEvent::getFormattedMessage);
        members.add("event", event -> contextValue(event, "event"));
        members.add("result", event -> contextValue(event, "result"));
        members.add("request_id", event -> contextValue(event, "request_id"));
        members.add("run_id", event -> contextValue(event, "run_id"));
        members.add("commit_result", event -> contextValue(event, "commit_result"));
        members.add("trip_id", event -> isKafkaDltEvent(event)
                ? contextValue(event, "trip_id")
                : numberValue(contextValue(event, "trip_id")));
        members.add("dlt_topic", event -> contextValue(event, "dlt_topic"));
        members.add("source_topic", event -> contextValue(event, "source_topic"));
        members.add("failure", event -> contextValue(event, "failure"));
        members.add("attempts", event -> numberValue(contextValue(event, "attempts")));
        members.add("job_id", event -> contextValue(event, "job_id"));
        members.add("duration_ms", event -> numberValue(contextValue(event, "duration_ms")));
        members.add("object_key", event -> contextValue(event, "object_key"));
        members.add("bytes_transferred", event -> numberValue(contextValue(event, "bytes_transferred")));
        members.add("failure_stage", event -> contextValue(event, "failure_stage"));
        members.add("error_code", event -> contextValue(event, "error_code"));
        members.add("expected_count", event -> numberValue(contextValue(event, "expected_count")));
        members.add("saved_count", event -> numberValue(contextValue(event, "saved_count")));
        members.add("workflow", event -> contextValue(event, "workflow"));
        members.add("stage", event -> contextValue(event, "stage"));
        members.add("stage_started_at", event -> contextValue(event, "stage_started_at"));
        members.add("stage_finished_at", event -> contextValue(event, "stage_finished_at"));
        members.add("message_type", event -> contextValue(event, "message_type"));
        members.add("batch_id", event -> numberValue(contextValue(event, "batch_id")));
        members.add("failed_count", event -> numberValue(contextValue(event, "failed_count")));
        members.add("generation_id", event -> numberValue(contextValue(event, "generation_id")));
        members.add("workflow_elapsed_ms", event -> numberValue(contextValue(event, "workflow_elapsed_ms")));
        members.add("batch_no", event -> numberValue(contextValue(event, "batch_no")));
        members.add("item_id", event -> numberValue(contextValue(event, "item_id")));
        members.add("photo_count", event -> numberValue(contextValue(event, "photo_count")));
        members.add("place_count", event -> numberValue(contextValue(event, "place_count")));
        members.add("unique_coordinate_count", event -> numberValue(contextValue(event, "unique_coordinate_count")));
        members.add("lookup_failure_count", event -> numberValue(contextValue(event, "lookup_failure_count")));
        members.add("fallback_count", event -> numberValue(contextValue(event, "fallback_count")));
        members.add("work_duration_ms", event -> numberValue(contextValue(event, "work_duration_ms")));
        members.add("stage_elapsed_ms", event -> numberValue(contextValue(event, "stage_elapsed_ms")));
        members.add("sample_count", event -> numberValue(contextValue(event, "sample_count")));
        members.add("release", environment.getProperty("RELEASE", "unknown"));
        members.add("stack_trace", event -> event.getThrowableProxy() == null
                ? null
                : throwableProxyConverter.convert(event));
    }

    private static boolean isKafkaDltEvent(ILoggingEvent event) {
        return "kafka_dlt_published".equals(contextValue(event, "event"));
    }

    private static Object contextValue(ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                if (key.equals(pair.key)) {
                    return pair.value;
                }
            }
        }

        return event.getMDCPropertyMap().get(key);
    }

    private static Long numberValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
