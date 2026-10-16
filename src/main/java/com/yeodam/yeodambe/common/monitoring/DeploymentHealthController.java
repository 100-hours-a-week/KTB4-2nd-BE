package com.yeodam.yeodambe.common.monitoring;

import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointWebExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("internal-metrics")
public class DeploymentHealthController {

    private final HealthEndpointWebExtension healthEndpoint;

    public DeploymentHealthController(HealthEndpointWebExtension healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    // 관리 포트를 분리해도 기존 배포 헬스체크 경로와 상태 코드 매핑을 유지한다.
    @GetMapping("/actuator/health")
    public ResponseEntity<HealthDescriptor> health() {
        WebEndpointResponse<HealthDescriptor> response = healthEndpoint.health(
                ApiVersion.V3,
                WebServerNamespace.SERVER,
                SecurityContext.NONE
        );
        return ResponseEntity.status(response.getStatus()).body(response.getBody());
    }
}
