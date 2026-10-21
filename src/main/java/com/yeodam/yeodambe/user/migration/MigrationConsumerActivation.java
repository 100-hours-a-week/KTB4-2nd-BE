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
    private volatile boolean activated;
    private final boolean deferTrip;
    private final int tripPartitions;
    public MigrationConsumerActivation(KafkaListenerEndpointRegistry registry,String file) { this(registry,file,false,4); }
    public MigrationConsumerActivation(KafkaListenerEndpointRegistry registry,String file,boolean deferTrip) { this(registry,file,deferTrip,4); }
    @org.springframework.beans.factory.annotation.Autowired
    public MigrationConsumerActivation(KafkaListenerEndpointRegistry registry,@Value("${migration.consumer.enable-file}") String file,
            @Value("${migration.consumer.defer-trip:false}") boolean deferTrip,
            @Value("${migration.trip.partitions:4}") int tripPartitions) {
        if(tripPartitions!=4 && tripPartitions!=8) throw new IllegalArgumentException("Invalid migration trip partition count");
        this.registry=registry;this.file=Path.of(file);this.deferTrip=deferTrip;this.tripPartitions=tripPartitions;
    }
    @Override
    public Health health() { return prepared && (!Files.exists(file) || activated) ? Health.up().build() : Health.down().build(); }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        Thread.ofVirtual().name("migration-consumer-activation").start(()-> {
            try {
                var ids=deferTrip?new String[]{"migrationAuthApply"}:new String[]{"migrationTripApply","migrationAuthApply"};
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
                        assigned &= c.isContainerPaused() && partitions!=null && partitions.size()==(id.equals("migrationTripApply")?tripPartitions:4);
                    }
                    if(assigned) {prepared=true;break;}
                    if(System.nanoTime()>deadline) throw new IllegalStateException("Migration partition assignment incomplete");
                    Thread.sleep(50);
                }
                while(!Files.exists(file)) Thread.sleep(20);
                if(deferTrip) registry.getListenerContainer("migrationTripApply").start();
                for(String id:ids) registry.getListenerContainer(id).resume();
                while(true) {
                    var trip=registry.getListenerContainer("migrationTripApply");
                    var parts=trip.getAssignedPartitions();
                    if(trip.isRunning() && parts!=null && parts.size()==tripPartitions && !trip.isContainerPaused()) break;
                    Thread.sleep(20);
                }
                activated=true;
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
        });
    }
}
