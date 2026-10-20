package com.yeodam.yeodambe.user.migration;

import com.yeodam.yeodambe.trip.migration.MigrationTripMode;
import com.yeodam.yeodambe.trip.migration.MigrationTripAdmissionException;
import com.yeodam.yeodambe.common.response.ErrorMessage;
import com.yeodam.yeodambe.user.exception.RefreshTokenInvalidOrExpiredException;
import com.yeodam.yeodambe.user.security.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name="migration.auth.enabled", havingValue="true")
public class MigrationAuthGateway {
    private final KafkaTemplate<String,String> kafka;
    private final MigrationAuthCrypto crypto;
    private final ObjectMapper mapper;
    private final MigrationTripMode mode;
    private final TokenHasher hasher;
    private final String topic;
    private final ConcurrentMap<String,CompletableFuture<MigrationAuthReply>> waiting = new ConcurrentHashMap<>();
    public MigrationAuthGateway(KafkaTemplate<String,String> kafka, MigrationAuthCrypto crypto,
            ObjectMapper mapper, MigrationTripMode mode, TokenHasher hasher,
            @Value("${migration.auth.topic}") String topic) {
        this.kafka=kafka;this.crypto=crypto;this.mapper=mapper;this.mode=mode;this.hasher=hasher;this.topic=topic;
    }
    public boolean queueEnabled() { return mode.queueEnabled(); }
    public MigrationAuthReply request(String operation, String credential) {
        if (credential==null || credential.isBlank()) throw new RefreshTokenInvalidOrExpiredException();
        String id=hasher.hash(topic+":"+operation+":"+credential);
        CompletableFuture<MigrationAuthReply> own=new CompletableFuture<>();
        CompletableFuture<MigrationAuthReply> future=waiting.putIfAbsent(id,own);
        if(future==null) future=own;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        try {
            if(future==own) {
                var command=new MigrationAuthCommand(id,operation,credential,System.currentTimeMillis());
                kafka.send(topic,id,crypto.encrypt(mapper.writeValueAsString(command),topic))
                    .get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            }
            MigrationAuthReply reply=future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(reply.status()==401) throw new RefreshTokenInvalidOrExpiredException();
            if(reply.status()!=200) throw unavailable();
            return reply;
        } catch(RefreshTokenInvalidOrExpiredException e) { throw e;
        } catch(InterruptedException e) { Thread.currentThread().interrupt();throw unavailable();
        } catch(Exception e) { throw unavailable();
        } finally { waiting.remove(id,future); }
    }
    @KafkaListener(topics="${migration.auth.topic}.results", groupId="${migration.auth.result-group}",
        containerFactory="migrationAuthListenerFactory", autoStartup="${migration.auth.results-enabled:false}")
    public void result(String value) {
        MigrationAuthReply reply=mapper.readValue(crypto.decrypt(value,topic+".results"),MigrationAuthReply.class);
        var future=waiting.get(reply.requestId());if(future!=null) future.complete(reply);
    }
    private MigrationTripAdmissionException unavailable() {
        return new MigrationTripAdmissionException(HttpStatus.SERVICE_UNAVAILABLE, ErrorMessage.MIGRATION_TRIP_MODE_UNAVAILABLE);
    }
}
