package com.yeodam.yeodambe.user.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.apache.kafka.common.TopicPartition;
import java.nio.file.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationConsumerActivationTest {
    @TempDir Path directory;
    @Test void assignsPartitionsWhilePausedAndResumesOnlyAfterPromotion() throws Exception {
        var registry=mock(KafkaListenerEndpointRegistry.class);
        var trip=mock(MessageListenerContainer.class);var auth=mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("migrationTripApply")).thenReturn(trip);
        when(registry.getListenerContainer("migrationAuthApply")).thenReturn(auth);
        for(var c:List.of(trip,auth)) {
            var paused=new java.util.concurrent.atomic.AtomicBoolean(true);
            when(c.isContainerPaused()).thenAnswer(invocation->paused.get());
            doAnswer(invocation->{paused.set(false);return null;}).when(c).resume();
            when(c.isRunning()).thenReturn(true);
            when(c.getAssignedPartitions()).thenReturn(List.of(new TopicPartition("run",0),new TopicPartition("run",1),new TopicPartition("run",2),new TopicPartition("run",3)));
        }
        var enabled=directory.resolve("apply-enabled");
        var activation=new MigrationConsumerActivation(registry,enabled.toString());activation.ready();
        verify(trip,timeout(1000)).start();verify(auth,timeout(1000)).start();
        var until=System.nanoTime()+1_000_000_000L;
        while(!activation.health().getStatus().getCode().equals("UP") && System.nanoTime()<until) Thread.sleep(10);
        assertEquals("UP",activation.health().getStatus().getCode());
        verify(trip,never()).resume();verify(auth,never()).resume();
        Files.createFile(enabled);
        verify(trip,timeout(1000)).resume();verify(auth,timeout(1000)).resume();
    }
    @Test void eightTripPartitionsAndFourAuthPartitionsActivateAfterPromotion() throws Exception {
        var registry=mock(KafkaListenerEndpointRegistry.class);
        var trip=mock(MessageListenerContainer.class);var auth=mock(MessageListenerContainer.class);
        when(registry.getListenerContainer("migrationTripApply")).thenReturn(trip);
        when(registry.getListenerContainer("migrationAuthApply")).thenReturn(auth);
        var paused=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(auth.isContainerPaused()).thenAnswer(invocation->paused.get());
        doAnswer(invocation->{paused.set(false);return null;}).when(auth).resume();
        when(trip.isRunning()).thenReturn(true);
        when(trip.getAssignedPartitions()).thenReturn(List.of(new TopicPartition("trip",0),new TopicPartition("trip",1),new TopicPartition("trip",2),new TopicPartition("trip",3),new TopicPartition("trip",4),new TopicPartition("trip",5),new TopicPartition("trip",6),new TopicPartition("trip",7)));
        when(auth.getAssignedPartitions()).thenReturn(List.of(new TopicPartition("auth",0),new TopicPartition("auth",1),new TopicPartition("auth",2),new TopicPartition("auth",3)));
        var enabled=directory.resolve("deferred-enabled");
        var activation=new MigrationConsumerActivation(registry,enabled.toString(),true,8);activation.ready();
        verify(auth,timeout(3000)).start();verify(trip,never()).start();
        Files.createFile(enabled);verify(trip,timeout(3000)).start();verify(auth,timeout(3000)).resume();
        var end=System.nanoTime()+3_000_000_000L;
        while(!activation.health().getStatus().getCode().equals("UP") && System.nanoTime()<end) Thread.sleep(10);
        assertEquals("UP",activation.health().getStatus().getCode());
    }
}
