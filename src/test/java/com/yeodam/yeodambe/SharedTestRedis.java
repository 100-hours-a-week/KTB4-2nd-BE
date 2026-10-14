package com.yeodam.yeodambe;

import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

final class SharedTestRedis {

    private static final int DATABASE_COUNT = 512;
    private static int databaseSequence;

    private SharedTestRedis() {
    }

    static synchronized DataRedisConnectionDetails createDatabase() {
        if (databaseSequence >= DATABASE_COUNT) {
            throw new IllegalStateException("공유 테스트 Redis의 논리 DB를 모두 사용했습니다.");
        }
        var redis = Holder.REDIS;
        var standalone = DataRedisConnectionDetails.Standalone.of(
                redis.getHost(), redis.getMappedPort(6379), databaseSequence++
        );
        return new DataRedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return standalone;
            }
        };
    }

    private static final class Holder {

        private static final GenericContainer<?> REDIS = startRedis();

        private static GenericContainer<?> startRedis() {
            var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .withCommand(
                            "redis-server", "--databases", Integer.toString(DATABASE_COUNT),
                            "--save", "", "--appendonly", "no",
                            "--maxmemory", "64mb", "--maxmemory-policy", "noeviction"
                    );
            // 컨텍스트가 아닌 테스트 JVM의 수명을 따르며 종료 후 Ryuk이 정리한다.
            redis.start();
            return redis;
        }
    }
}
