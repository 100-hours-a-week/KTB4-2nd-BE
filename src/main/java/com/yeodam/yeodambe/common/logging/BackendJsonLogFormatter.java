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
        members.add("service", "backend");
        members.add("logger", ILoggingEvent::getLoggerName);
        members.add("message", ILoggingEvent::getFormattedMessage);
        members.add("event", event -> contextValue(event, "event"));
        members.add("result", event -> contextValue(event, "result"));
        members.add("request_id", event -> contextValue(event, "request_id"));
        members.add("trip_id", event -> numberValue(contextValue(event, "trip_id")));
        members.add("job_id", event -> contextValue(event, "job_id"));
        members.add("duration_ms", event -> numberValue(contextValue(event, "duration_ms")));
        members.add("failure_stage", event -> contextValue(event, "failure_stage"));
        members.add("error_code", event -> contextValue(event, "error_code"));
        members.add("expected_count", event -> numberValue(contextValue(event, "expected_count")));
        members.add("saved_count", event -> numberValue(contextValue(event, "saved_count")));
        members.add("release", environment.getProperty("RELEASE", "unknown"));
        members.add("stack_trace", event -> event.getThrowableProxy() == null
                ? null
                : throwableProxyConverter.convert(event));
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
