package com.yeodam.yeodambe.migrationprobe.service;

import com.yeodam.yeodambe.migrationprobe.exception.MigrationProbeInternalErrorMessage;
import com.yeodam.yeodambe.migrationprobe.exception.MigrationProbeHashConflictException;
import com.yeodam.yeodambe.migrationprobe.exception.MigrationProbeStoreUnavailableException;
import com.yeodam.yeodambe.migrationprobe.repository.MigrationProbeRepository;
import com.yeodam.yeodambe.migrationprobe.service.response.MigrationProbeResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@Profile("staging | prod")
public class MigrationProbeService {

    private final MigrationProbeRepository repository;
    private final TransactionTemplate transactions;

    public MigrationProbeService(
            MigrationProbeRepository repository,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public MigrationProbeResponse write(String runId, String requestId, String payloadHash) {
        long startedAt = System.nanoTime();

        try {
            String result = insertOrCompare(runId, requestId, payloadHash);
            logResult(log.atInfo(), runId, requestId, result, "committed", startedAt)
                    .log("DB 전환 시험 요청을 확인했습니다.");
            return new MigrationProbeResponse(runId, requestId, result);
        } catch (MigrationProbeHashConflictException exception) {
            logResult(log.atWarn(), runId, requestId, "conflict", "committed", startedAt)
                    .addKeyValue("error_code", "MIGRATION_WRITE_PROBE_HASH_CONFLICT")
                    .log("DB 전환 시험 요청의 Hash가 기존 값과 다릅니다.");
            throw exception;
        } catch (MigrationProbeStoreUnavailableException exception) {
            logResult(log.atWarn(), runId, requestId, "failure", "unknown", startedAt)
                    .addKeyValue("failure_stage", exception.getFailureStage())
                    .addKeyValue("error_code", "MIGRATION_WRITE_PROBE_STORE_UNAVAILABLE")
                    .log("DB 전환 시험 저장소 처리에 실패했습니다. 원인 타입: {}",
                            exception.getFailureType());
            throw exception;
        }
    }

    private String insertOrCompare(String runId, String requestId, String payloadHash) {
        try {
            transactions.executeWithoutResult(status -> insert(runId, requestId, payloadHash));
            return "created";
        } catch (DuplicateInsertException exception) {
            return compareExistingHash(runId, requestId, payloadHash);
        } catch (MigrationProbeStoreUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationProbeStoreUnavailableException("commit", exception);
        }
    }

    private void insert(String runId, String requestId, String payloadHash) {
        try {
            repository.insert(runId, requestId, payloadHash);
        } catch (DuplicateKeyException exception) {
            // INSERT 중복만 표시한다. 트랜잭션 종료 중 발생한 오류는 중복 성공으로 처리하지 않는다.
            throw new DuplicateInsertException();
        } catch (RuntimeException exception) {
            throw new MigrationProbeStoreUnavailableException("insert", exception);
        }
    }

    private String compareExistingHash(String runId, String requestId, String payloadHash) {
        String existingHash;
        try {
            existingHash = transactions.execute(status -> findExistingHash(runId, requestId));
        } catch (MigrationProbeStoreUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MigrationProbeStoreUnavailableException("commit", exception);
        }

        if (!payloadHash.equals(existingHash)) {
            throw new MigrationProbeHashConflictException();
        }

        return "already_exists";
    }

    private String findExistingHash(String runId, String requestId) {
        try {
            return repository.findPayloadHash(runId, requestId)
                    .orElseThrow(() -> new IllegalStateException(MigrationProbeInternalErrorMessage.STORED_RESULT_MISSING.message()));
        } catch (RuntimeException exception) {
            throw new MigrationProbeStoreUnavailableException("duplicate_read", exception);
        }
    }

    private LoggingEventBuilder logResult(
            LoggingEventBuilder event,
            String runId,
            String requestId,
            String result,
            String commitResult,
            long startedAt
    ) {
        return event.addKeyValue("event", "migration_write_probe")
                .addKeyValue("run_id", runId)
                .addKeyValue("request_id", requestId)
                .addKeyValue("result", result)
                .addKeyValue("commit_result", commitResult)
                .addKeyValue("duration_ms", (System.nanoTime() - startedAt) / 1_000_000);
    }

    private static class DuplicateInsertException extends RuntimeException {

        private DuplicateInsertException() {
            super(MigrationProbeInternalErrorMessage.DUPLICATE_KEY.message());
        }
    }
}
