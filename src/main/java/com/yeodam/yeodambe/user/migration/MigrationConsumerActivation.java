package com.yeodam.yeodambe.user.migration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;

@Component
@ConditionalOnProperty(name="migration.consumer.enable-file")
public class MigrationConsumerActivation {
    private final KafkaListenerEndpointRegistry registry;
    private final Path file;
    public MigrationConsumerActivation(KafkaListenerEndpointRegistry registry,@Value("${migration.consumer.enable-file}") String file) {
        this.registry=registry;this.file=Path.of(file);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        Thread.ofVirtual().name("migration-consumer-activation").start(()-> {
            try {
                while(!Files.exists(file)) Thread.sleep(50);
                for(String id: new String[]{"migrationTripApply","migrationAuthApply"}) {
                    var container=registry.getListenerContainer(id);
                    if(container==null) throw new IllegalStateException("Migration listener absent");
                    container.start();
                }
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
        });
    }
}
