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
    private final MigrationAuthApplyService apply;
    private final MigrationAuthRedis redis;
    private final ConcurrentMap<String,CompletableFuture<MigrationAuthReply>> waiting = new ConcurrentHashMap<>();
    public MigrationAuthGateway(KafkaTemplate<String,String> kafka, MigrationAuthCrypto crypto,
            ObjectMapper mapper, MigrationTripMode mode, TokenHasher hasher, MigrationAuthApplyService apply, MigrationAuthRedis redis,
            @Value("${migration.auth.topic}") String topic) {
        this.kafka=kafka;this.crypto=crypto;this.mapper=mapper;this.mode=mode;this.hasher=hasher;this.topic=topic;this.apply=apply;this.redis=redis;
    }
    public boolean queueEnabled() { return mode.queueEnabled(); }
    public MigrationAuthReply direct(String operation, String credential) {
        if (credential==null || credential.isBlank()) throw new RefreshTokenInvalidOrExpiredException();
        var command=new MigrationAuthCommand(hasher.hash(topic+":"+operation+":"+credential),operation,credential,System.currentTimeMillis());
        var cached=apply.recover(command);
        if(cached.isPresent()) return cached.get();
        try {
            // Source rotation and its encrypted reply commit in the same transaction.
            return apply.apply(command);
        } catch(org.springframework.dao.DuplicateKeyException duplicate) {
            // A retry can race the original HTTP request before its commit is visible.
            return apply.recover(command).orElseThrow(this::unavailable);
        }
    }
    public MigrationAuthReply request(String operation, String credential) {
        if (credential==null || credential.isBlank()) throw new RefreshTokenInvalidOrExpiredException();
        String id=hasher.hash(topic+":"+operation+":"+credential);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        var command=new MigrationAuthCommand(id,operation,credential,System.currentTimeMillis());
        // A lost HTTP response is recovered from the committed result, without queueing behind new work.
        try {
            var cached=apply.recover(command);
            if(cached.isPresent()) {
                if(!redis.applied(command,cached.get())) throw unavailable();
                return cached.get();
            }
        } catch(RefreshTokenInvalidOrExpiredException e) { throw e;
        } catch(RuntimeException e) { throw unavailable(); }
        CompletableFuture<MigrationAuthReply> own=new CompletableFuture<>();
        CompletableFuture<MigrationAuthReply> future=waiting.putIfAbsent(id,own);
        if(future==null) future=own;
        try {
            if(future==own) {
                kafka.send(topic,apply.routingKey(command),crypto.encrypt(mapper.writeValueAsString(command),topic))
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
