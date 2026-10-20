package com.yeodam.yeodambe.user.migration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import java.nio.file.*;

/** Join groups before cutover; pause prevents writes until the Target is promoted. */
@Component
@ConditionalOnProperty(name="migration.consumer.enable-file")
public class MigrationConsumerActivation implements HealthIndicator {
    private final KafkaListenerEndpointRegistry registry;
    private final Path file;
    private volatile boolean prepared;
    public MigrationConsumerActivation(KafkaListenerEndpointRegistry registry,@Value("${migration.consumer.enable-file}") String file) {
        this.registry=registry;this.file=Path.of(file);
    }
    @Override
    public Health health() { return prepared ? Health.up().build() : Health.down().build(); }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        Thread.ofVirtual().name("migration-consumer-activation").start(()-> {
            try {
                var ids=new String[]{"migrationTripApply","migrationAuthApply"};
                for(String id:ids) {
                    var container=registry.getListenerContainer(id);
                    if(container==null) throw new IllegalStateException("Migration listener absent");
                    container.pause();container.start();
                }
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
                while(true) {
                    boolean assigned=true;
                    for(String id:ids) {
                        var c=registry.getListenerContainer(id);
                        var partitions=c.getAssignedPartitions();
                        assigned &= c.isContainerPaused() && partitions!=null && partitions.size()==4;
                    }
                    if(assigned) {prepared=true;break;}
                    if(System.nanoTime()>deadline) throw new IllegalStateException("Migration partition assignment incomplete");
                    Thread.sleep(50);
                }
                while(!Files.exists(file)) Thread.sleep(20);
                for(String id:ids) registry.getListenerContainer(id).resume();
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
        });
    }
}
