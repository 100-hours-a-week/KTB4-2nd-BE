package com.yeodam.yeodambe;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class SharedTestMongoTest {
    @Test
    void 실패한_컨테이너를_제거하기_전에_종료_상태와_표준_오류를_보존한다() {
        try (var mongo = new SharedTestMongo.MongoTestContainer()
                .withCommand("sh", "-c", "echo mongo-startup-regression >&2; sleep 2; exit 1")
                .waitingFor(Wait.forLogMessage("never-ready\\n", 1)
                        .withStartupTimeout(Duration.ofSeconds(5)))) {
            Throwable failure = catchThrowable(mongo::start);

            assertThat(failure).isNotNull();
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                messages.append(cause.getMessage()).append('\n');
            }
            assertThat(messages.toString())
                    .contains("Exit code: 1", "OOM killed: false", "mongo-startup-regression");
        }
    }
}
