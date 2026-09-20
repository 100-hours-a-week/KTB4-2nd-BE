package com.yeodam.yeodambe.integration.client;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import java.time.Instant;

@Component
public class AiEc2Starter {
    private final Ec2Client ec2;
    private final String instanceId;

    @Autowired
    public AiEc2Starter(@Value("${ai.server.instance-id}") String instanceId,
                        @Value("${aws.region}") String region) {
        this(Ec2Client.builder().region(Region.of(region)).build(), instanceId);
    }

    AiEc2Starter(Ec2Client ec2, String instanceId) {
        this.ec2 = ec2;
        this.instanceId = instanceId;
    }

    public void ensureRunning() {
        Instant deadline = Instant.now().plusSeconds(180);

        while (Instant.now().isBefore(deadline)) {
            var response = ec2.describeInstances(request -> request.instanceIds(instanceId));
            var instances = response.reservations().stream().flatMap(r -> r.instances().stream()).toList();

            if (instances.size() != 1) throw new IllegalStateException("AI EC2 인스턴스를 찾을 수 없습니다.");

            InstanceStateName state = instances.getFirst().state().name();

            if (state == InstanceStateName.RUNNING) return;

            if (state == InstanceStateName.STOPPED) {
                try {
                    ec2.startInstances(request -> request.instanceIds(instanceId));
                } catch (Ec2Exception e) {
                    if (e.awsErrorDetails() == null
                            || !"IncorrectInstanceState".equals(e.awsErrorDetails().errorCode())) throw e;
                }

            } else if (state != InstanceStateName.PENDING && state != InstanceStateName.STOPPING) {
                throw new IllegalStateException("AI EC2를 시작할 수 없는 상태입니다: " + state);
            }

            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AI EC2 기동 대기가 중단됐습니다.", e);
            }
        }
        throw new IllegalStateException("AI EC2 기동 시간이 초과됐습니다.");
    }

    @PreDestroy
    void close() {
        ec2.close();
    }
}
