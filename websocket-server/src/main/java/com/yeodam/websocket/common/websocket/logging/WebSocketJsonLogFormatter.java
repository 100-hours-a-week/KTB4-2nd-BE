package com.yeodam.websocket.common.websocket.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.JsonWriterStructuredLogFormatter;
import org.springframework.core.env.Environment;

public class WebSocketJsonLogFormatter extends JsonWriterStructuredLogFormatter<ILoggingEvent> {

    public WebSocketJsonLogFormatter(Environment environment, ThrowableProxyConverter throwableProxyConverter) {
        super(members -> addMembers(environment, throwableProxyConverter, members), null);
    }

    private static void addMembers(Environment environment, ThrowableProxyConverter converter,
                                   JsonWriter.Members<ILoggingEvent> members) {
        members.add("timestamp", event -> event.getInstant().toString());
        members.add("level", event -> event.getLevel().toString());
        members.add("service", "websocket");
        members.add("logger", ILoggingEvent::getLoggerName);
        members.add("message", ILoggingEvent::getFormattedMessage);
        for (String key : new String[]{"event", "result", "failure", "event_id", "connection_id", "cause_type"}) {
            members.add(key, event -> contextValue(event, key));
        }
        members.add("connections", event -> contextValue(event, "connections"));
        members.add("subscriptions", event -> contextValue(event, "subscriptions"));
        members.add("redis_subscription_ready", event -> contextValue(event, "redis_subscription_ready"));
        members.add("release", environment.getProperty("RELEASE", "unknown"));
        members.add("stack_trace", event -> event.getThrowableProxy() == null
                ? null : converter.convert(event));
    }

    private static Object contextValue(ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                if (key.equals(pair.key)) return pair.value;
            }
        }
        return event.getMDCPropertyMap().get(key);
    }
}
