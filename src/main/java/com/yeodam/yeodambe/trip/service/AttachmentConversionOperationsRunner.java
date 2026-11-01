package com.yeodam.yeodambe.trip.service;

import com.yeodam.yeodambe.trip.entity.AttachmentConversionRoute;
import com.yeodam.yeodambe.trip.entity.AttachmentConversionTask.Kind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.attachment-conversion.operations.enabled", havingValue = "true")
public class AttachmentConversionOperationsRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final String PREFIX = "app.attachment-conversion.operations.";
    private final AttachmentConversionMigrationService migration;
    private final Environment environment;
    private final ObjectMapper json;
    private int exitCode = 1;

    @Override
    public void run(ApplicationArguments args) {
        exitCode = execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    int execute(ApplicationArguments args) {
        try {
            Action action = Action.valueOf(required(args, "action"));
            Kind kind = Kind.valueOf(required(args, "kind"));
            long afterId = action == Action.RETURN ? 0 : Long.parseLong(required(args, "after-batch-id"));
            int limit = action == Action.RETURN ? 1 : Integer.parseInt(required(args, "limit"));
            Long batchId = action == Action.RETURN ? Long.valueOf(required(args, "batch-id")) : null;
            Assert.isTrue(afterId >= 0 && limit > 0 && (batchId == null || batchId > 0), "페이지 입력이 유효하지 않습니다.");
            if (action == Action.CHECK) {
                var page = migration.check(kind, afterId, limit);
                for (var candidate : page) {
                    log.info("변환 CHECK kind={} batch_id={} route={} legacy_lease={} claimed={} missing_execution={}",
                            kind, candidate.batchId(), candidate.route(), candidate.legacyLease(),
                            candidate.claimed(), candidate.missingExecution());
                }
                log.info("변환 CHECK 완료 kind={} checked={} last_batch_id={}", kind, page.size(),
                        page.isEmpty() ? afterId : page.getLast().batchId());
                return 0;
            }
            Instant deadline = validateEvidence(args, action, kind, afterId, limit, batchId);
            if (action == Action.RETURN) {
                Assert.state(migration.returnBatchToDb(kind, batchId, deadline), "복귀 가능한 배치가 없습니다.");
            } else {
                int count = migration.transferPendingBatches(kind, afterId, limit, deadline);
                log.info("변환 인계 페이지 완료 action={} kind={} transferred={}", action, kind, count);
            }
            return 0;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            log.warn("변환 운영 명령 거절: 입력 또는 전이 선행 조건을 확인하세요.");
            return 2;
        } catch (Exception failure) {
            // 외부 예외의 원문에는 접속 정보가 포함될 수 있다.
            log.error("변환 운영 명령 기술 실패 type={}", failure.getClass().getSimpleName());
            return 1;
        }
    }

    private Instant validateEvidence(ApplicationArguments args, Action action, Kind kind, long afterId, int limit, Long batchId)
            throws IOException {
        AttachmentConversionRoute expectedMode = action == Action.FINAL_SWEEP
                ? AttachmentConversionRoute.KAFKA : AttachmentConversionRoute.HOLD;
        Assert.isTrue(expectedMode.name().equals(environment.getProperty("app.attachment-conversion.mode", "DB")),
                "운영 모드가 단계와 일치하지 않습니다.");
        String target = required(args, "target");
        String sha = required(args, "deployment-sha");
        Set<String> expectedInstances = Arrays.stream(required(args, "instances").split(",", -1))
                .map(String::trim).collect(Collectors.toSet());
        Assert.isTrue(!expectedInstances.contains(""), "전체 인스턴스 목록이 필요합니다.");
        Duration maxAge = Duration.parse(required(args, "drain-max-age"));
        Assert.isTrue(maxAge.isPositive(), "drain 허용 시간은 양수여야 합니다.");
        Evidence evidence = json.readValue(Files.readString(Path.of(required(args, "drain-evidence"))), Evidence.class);
        Assert.isTrue(evidence != null && target.equals(evidence.target()) && sha.equals(evidence.deploymentSha())
                && action.name().equals(evidence.action()) && kind.name().equals(evidence.kind())
                && evidence.afterBatchId() == afterId && evidence.limit() == limit
                && Objects.equals(batchId, evidence.batchId()), "확인 자료 대상이 일치하지 않습니다.");
        Assert.hasText(evidence.operator(), "확인 담당자가 필요합니다.");
        Assert.hasText(evidence.observation(), "로그와 지표 확인 근거가 필요합니다.");
        Assert.isTrue(evidence.instances() != null && evidence.instances().size() == expectedInstances.size(),
                "전체 인스턴스 확인이 필요합니다.");
        Assert.isTrue(evidence.instances().stream().map(Instance::id).collect(Collectors.toSet()).equals(expectedInstances),
                "확인 인스턴스가 일치하지 않습니다.");
        for (Instance instance : evidence.instances()) {
            Assert.isTrue(sha.equals(instance.sha()) && expectedMode.name().equals(instance.mode())
                    && instance.newStartsStopped() && instance.ioDrained()
                    && (action == Action.FINAL_SWEEP || instance.claimsStopped() && instance.consumersStopped()),
                    "인스턴스 drain 확인이 완료되지 않았습니다.");
        }
        Instant observedAt = Instant.parse(evidence.observedAt());
        Instant deadline = observedAt.plus(maxAge);
        Assert.isTrue(!observedAt.isAfter(Instant.now()) && deadline.isAfter(Instant.now()), "drain 확인 자료가 만료되었습니다.");
        return deadline;
    }

    private String required(ApplicationArguments args, String key) {
        List<String> values = args.getOptionValues(PREFIX + key);
        Assert.isTrue(values == null || values.size() == 1, "명령 옵션을 한 번만 지정하세요.");
        String value = values == null ? environment.getProperty(PREFIX + key) : values.getFirst();
        Assert.hasText(value, "필수 운영 설정이 없습니다.");
        return value;
    }

    private enum Action {
        CHECK,
        TRANSFER,
        FINAL_SWEEP,
        RETURN
    }

    record Evidence(
            String target,
            String deploymentSha,
            String action,
            String kind,
            long afterBatchId,
            int limit,
            Long batchId,
            String operator,
            String observedAt,
            String observation,
            List<Instance> instances
    ) {
    }

    record Instance(
            String id,
            String sha,
            String mode,
            boolean newStartsStopped,
            boolean claimsStopped,
            boolean consumersStopped,
            boolean ioDrained
    ) {
    }
}
