package com.yeodam.yeodambe.integration.client;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiEc2StarterTest {
    private final Ec2Client ec2 = mock(Ec2Client.class);
    private final AiEc2Starter starter = new AiEc2Starter(ec2, "i-test");

    @Test
    void 실행중인_인스턴스는_다시_시작하지_않는다() {
        when(ec2.describeInstances(any(java.util.function.Consumer.class))).thenReturn(
                DescribeInstancesResponse.builder().reservations(Reservation.builder()
                        .instances(Instance.builder().state(InstanceState.builder()
                                .name(InstanceStateName.RUNNING).build()).build()).build()).build());

        starter.ensureRunning();

        verify(ec2, never()).startInstances(any(java.util.function.Consumer.class));
    }

    @Test
    void 인스턴스를_찾지_못하면_분석을_시작하지_않는다() {
        when(ec2.describeInstances(any(java.util.function.Consumer.class)))
                .thenReturn(DescribeInstancesResponse.builder().build());
        assertThrows(IllegalStateException.class, starter::ensureRunning);
    }
}
