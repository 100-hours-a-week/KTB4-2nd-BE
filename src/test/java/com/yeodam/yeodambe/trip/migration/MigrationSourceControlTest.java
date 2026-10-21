package com.yeodam.yeodambe.trip.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MigrationSourceControlTest {
    @TempDir Path directory;
    @Test void fenceRequiresScopedCredentialsAndStopsSourceOnlyOnce() throws Exception {
        String run=UUID.randomUUID().toString(),token="a".repeat(64);
        Path key=directory.resolve("token"),marker=directory.resolve("mode");
        Files.writeString(key,token);Files.writeString(marker,"source-queue");
        var mode=new MigrationTripMode(marker.toString());
        var registry=mock(KafkaListenerEndpointRegistry.class);
        var listener=mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("migrationTripApply")).thenReturn(listener);
        doAnswer(invocation -> {((Runnable)invocation.getArgument(0)).run();return null;}).when(listener).stop(any(Runnable.class));
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var scheduler=mock(MigrationTripWorkScheduler.class);
        var control=new MigrationSourceControl(mode,registry,new ObjectMapper(),scheduler,port,run,key.toString());
        try {
            control.ready();var client=HttpClient.newHttpClient();
            var uri=URI.create("http://127.0.0.1:"+port+"/fence");
            assertThat(client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            assertThat(mode.fencing()).isFalse();
            var wrong=HttpRequest.newBuilder(uri).header("X-Migration-Control-Token",token).header("X-Migration-Run",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThat(client.send(wrong,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
            var request=HttpRequest.newBuilder(uri).header("X-Migration-Control-Token",token).header("X-Migration-Run",run).POST(HttpRequest.BodyPublishers.noBody()).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new ObjectMapper().readTree(response.body()).get("sourceConsumerStopped").asBoolean()).isTrue();
            assertThat(mode.fencing()).isTrue();
            verify(scheduler).beginFence();
            assertThat(new ObjectMapper().readTree(response.body()).get("activeTrip").asInt()).isZero();
            client.send(request,HttpResponse.BodyHandlers.ofString());verify(listener,times(1)).stop(any(Runnable.class));
        } finally {control.close();}
    }
}
