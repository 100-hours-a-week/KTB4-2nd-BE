package com.yeodam.yeodambe.trip.migration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Rehearsal control uses a separate, host-loopback-published port and scoped token. */
@Component
@ConditionalOnProperty(name="migration.source-control.port")
public class MigrationSourceControl {
    private final MigrationTripMode mode;
    private final KafkaListenerEndpointRegistry registry;
    private final ObjectMapper mapper;
    private final int port;
    private final String runId;
    private final byte[] token;
    private final String instance=UUID.randomUUID().toString();
    private final AtomicBoolean requested=new AtomicBoolean();
    private volatile boolean consumerStopped;
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    public MigrationSourceControl(MigrationTripMode mode,KafkaListenerEndpointRegistry registry,ObjectMapper mapper,
            @Value("${migration.source-control.port}") int port,
            @Value("${migration.source-control.run-id}") String runId,
            @Value("${migration.source-control.token-file}") String tokenFile) throws IOException {
        this.mode=mode;this.registry=registry;this.mapper=mapper;this.port=port;
        this.runId=UUID.fromString(runId).toString();this.token=Files.readString(Path.of(tokenFile)).trim().getBytes(StandardCharsets.UTF_8);
        if(token.length!=64 || !new String(token,StandardCharsets.UTF_8).matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid migration control token");
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() throws IOException {
        server=HttpServer.create(new InetSocketAddress("0.0.0.0",port),4);
        executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/state",e->handle(e,false));server.createContext("/fence",e->handle(e,true));server.start();
    }
    private void handle(HttpExchange e,boolean fence) throws IOException {
        try {
            String header=e.getRequestHeaders().getFirst("X-Migration-Control-Token");
            if(header==null || !MessageDigest.isEqual(token,header.getBytes(StandardCharsets.UTF_8))) {reply(e,403,Map.of("error","forbidden"));return;}
            if(!runId.equals(e.getRequestHeaders().getFirst("X-Migration-Run"))) {reply(e,409,Map.of("error","wrong_run"));return;}
            if(!(fence?"POST":"GET").equals(e.getRequestMethod())) {reply(e,405,Map.of("error","method"));return;}
            if(fence && requested.compareAndSet(false,true)) {
                mode.beginFence();
                var listener=registry.getListenerContainer("migrationTripApply");
                if(listener==null) throw new IllegalStateException("Source listener absent");
                listener.stop(()->consumerStopped=true);
            }
            var listener=registry.getListenerContainer("migrationTripApply");
            int assigned=listener==null || listener.getAssignedPartitions()==null?0:listener.getAssignedPartitions().size();
            reply(e,200,Map.of("runId",runId,"instance",instance,"fencing",mode.fencing(),
                    "activeDirect",mode.activeDirect(),"sourceConsumerStopped",consumerStopped,
                    "sourceConsumerRunning",listener!=null && listener.isRunning(),"sourceAssignedPartitions",assigned));
        } catch(RuntimeException failure) {reply(e,503,Map.of("error","control_unavailable"));}
        finally {e.close();}
    }
    private void reply(HttpExchange e,int status,Object value) throws IOException {
        byte[] body=mapper.writeValueAsBytes(value);e.getResponseHeaders().set("Content-Type","application/json");
        e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);
    }
    @PreDestroy public void close() {if(server!=null) server.stop(0);if(executor!=null) executor.close();}
}
